"""Outbound-fetch safety: block SSRF to internal/loopback/metadata hosts and
non-HTTP schemes.

Every server-side fetch of a user-supplied URL (the PDF proxy, PDF resolution,
the AI PDF re-download) must go through :func:`guarded_urlopen` so an attacker
can't turn the server into a request forwarder for ``file://`` reads,
``http://127.0.0.1`` / ``169.254.169.254`` probes, or reaching private-network
services the box can see but the internet can't.

The transport resolves and validates all addresses immediately before opening
a socket, then connects to that numeric address without resolving again. The
original hostname remains the HTTP Host and TLS certificate/SNI name. These
fetches bypass environment proxies, whose DNS resolution we cannot validate.
"""

import ipaddress
import http.client
import socket
import urllib.parse
from contextlib import contextmanager
from contextvars import ContextVar
from urllib.error import URLError
from urllib.request import (
    BaseHandler, HTTPCookieProcessor, HTTPHandler, HTTPRedirectHandler,
    HTTPSHandler, ProxyHandler, build_opener,
)

from .publisher_sessions import browser_profile

_ALLOWED_SCHEMES = ("http", "https")


class BlockedUrlError(URLError):
    """Raised when a URL is refused by the SSRF guard (subclass of URLError so
    existing ``except URLError`` handlers surface it as a clean 400)."""

    def __init__(self, reason: str):
        super().__init__(reason)


def _ip_is_blocked(ip: str) -> bool:
    addr = ipaddress.ip_address(ip)
    return (
        not addr.is_global
        or addr.is_loopback
        or addr.is_link_local      # 169.254/16 — cloud metadata lives here
        or addr.is_multicast
        or addr.is_reserved
        or addr.is_unspecified
        or (addr.version == 6 and addr.ipv4_mapped is not None and _ip_is_blocked(str(addr.ipv4_mapped)))
    )


def validate_public_url(url: str) -> str:
    """Return the URL unchanged if it is an http(s) URL whose host resolves only
    to public addresses; otherwise raise BlockedUrlError."""
    try:
        parsed = urllib.parse.urlparse(url)
        host = parsed.hostname
        port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    except ValueError as exc:
        raise BlockedUrlError("invalid URL host or port") from exc
    if parsed.scheme.lower() not in _ALLOWED_SCHEMES:
        raise BlockedUrlError(f"blocked URL scheme: {parsed.scheme or '(none)'}")
    if not host:
        raise BlockedUrlError("URL has no host")
    _public_addresses(host, port)
    return url


def _public_addresses(host, port):
    """Validate the complete DNS answer before any address is connected."""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        raise BlockedUrlError(f"cannot resolve host: {host}")
    if not infos:
        raise BlockedUrlError(f"cannot resolve host: {host}")
    for info in infos:
        ip = info[4][0]
        try:
            if _ip_is_blocked(ip):
                raise BlockedUrlError(f"blocked internal address for {host}: {ip}")
        except ValueError:
            raise BlockedUrlError(f"unparseable address for {host}: {ip}")
    return infos


def _public_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None):
    """Like create_connection, without a second DNS lookup after validation."""
    host, port = address
    infos = _public_addresses(host, port)
    last_error = None
    for family, socktype, proto, _, sockaddr in infos:
        sock = None
        try:
            sock = socket.socket(family, socktype, proto)
            if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
                sock.settimeout(timeout)
            if source_address:
                sock.bind(source_address)
            sock.connect(sockaddr)
            return sock
        except OSError as exc:
            last_error = exc
            if sock is not None:
                sock.close()
    raise last_error


class _PublicHTTPConnection(http.client.HTTPConnection):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._create_connection = _public_connection


class _PublicHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._create_connection = _public_connection


class _PublicHTTPHandler(HTTPHandler):
    def http_open(self, req):
        return self.do_open(_PublicHTTPConnection, req)


class _PublicHTTPSHandler(HTTPSHandler):
    def https_open(self, req):
        return self.do_open(_PublicHTTPSConnection, req, context=self._context)


class _GuardedRedirectHandler(HTTPRedirectHandler):
    """Re-validate every redirect target so a public URL can't 302 to an
    internal one."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class _BrowserAgent(BaseHandler):
    """Present a connected host's own browser User-Agent (the one that
    imported its cookies) on every request to that host, redirects included."""

    def __init__(self, agents: dict):
        self.agents = agents

    def https_request(self, req):
        agent = self.agents.get(urllib.parse.urlsplit(req.full_url).hostname or "")
        if agent:
            req.add_header("User-Agent", agent)
        return req


# The (jar, agents) of the browsing session this thread is in, if any.
_session = ContextVar("net_guard_session", default=None)


@contextmanager
def browsing_session():
    """Share one cookie jar between the guarded fetches inside the block,
    the way a browser tab does: what a landing page sets (a session id, an
    institutional-access handshake) reaches the PDF request it advertises.
    Seeded like any fetch's jar; it lives in this thread's context for the
    block only, so nothing carries across operations or users."""
    token = _session.set(browser_profile())
    try:
        yield
    finally:
        _session.reset(token)


def guarded_urlopen(req, timeout=30):
    """Drop-in for urllib.request.urlopen that validates the URL (and every
    redirect) against the SSRF guard first. ``req`` may be a str or a Request.

    Keep publisher cookies through this fetch's redirects (e.g. Nature's
    institutional-access handshake). Each call gets a fresh, in-memory jar
    unless it runs inside ``browsing_session``: cookies are never shared
    between users. Authenticated PDF operations can seed the jar from that
    user's explicitly connected publisher sessions.
    """
    url = req.full_url if hasattr(req, "full_url") else req
    validate_public_url(url)
    jar, agents = _session.get() or browser_profile()
    opener = build_opener(
        ProxyHandler({}), _PublicHTTPHandler(), _PublicHTTPSHandler(),
        _GuardedRedirectHandler, HTTPCookieProcessor(jar), _BrowserAgent(agents),
    )
    try:
        return opener.open(req, timeout=timeout)
    except URLError as exc:
        # AbstractHTTPHandler wraps socket exceptions, including our refusal.
        if isinstance(exc.reason, BlockedUrlError):
            raise exc.reason from exc
        raise
