# Security review — 5 November 2026

A report against Gamma Cloud, the account server at account.gammapdf.com,
said `/reset/confirm?token=` was a reflected XSS. It was reproduced on
`dev` at `cb9b95c0` and fixed on the `web-safety` branch, along with
everything a sweep of the same class turned up. The sweep covered every
page the account server, the self-hosted server and the site worker build
on the server, and a review of the fixes found one more. This was a
source review with isolated regression tests, not a test of a deployed
instance.

## Confirmed findings

| Finding | Severity | Prerequisite | Status |
|---|---|---|---|
| `/reset/confirm?token=` closed the page's inline `<script>` and ran the rest | High | A victim opens a crafted link while signed in | Fixed |
| A browser's user agent was shown as HTML on Devices | Medium | A portal session on the victim's account (a known password, an old reset link, a pre-registered address) | Fixed |
| An authorize error for a self-connected server's client redirected to that client's origin | Low | A confirmed account, which may connect a server for any https origin | Fixed |
| `/invite/continue` sent a signed-in, confirmed visitor on to the inviting server from a bare link | Low | An invitation from a server the attacker connected, to an address the attacker reads | Fixed |
| `/zotero/callback` sent whoever opened a passed-on callback to the flow's server | Low | The Zotero broker on, and a server the attacker connected | Fixed |
| The account's mails greeted by the display name as typed (line breaks, links, a lure) at an address nobody confirmed | Low | Registering with the victim's address, or moving an account to it | Fixed |
| The invitation mail's link filter missed non-ASCII domains, invisible characters inside a name, and names ending in `.md`, `.py` or `.zip` | Low | A confirmed account that connected a server | Fixed |

### The reset page's token

`pages.reset_confirm_page` wrote the token with `json.dumps`, which leaves
`<`, `>` and `&` as they are, so `</script>` in the token ended the script
block for the HTML parser. The file already had `_js` (the same JSON with
those three as `\u` escapes), which the token pages next to it used. The
page sends no Content-Security-Policy. The script ran on the portal's
origin, so the same-origin check let its `/api` calls through, and the
`SameSite=Lax` session cookie comes with a clicked link. An account made
through Google or GitHub has no password, so `accounts.confirm_ok` lets
`/api/me/password` set one without the current one, which takes the
account over.

### The Devices page's browsers

`_platform` returns the first 40 characters of a user agent that names no
system or browser it knows. `_browser_row` passed that to `_row`, whose
title is HTML, without `esc` (the server rows escape theirs). The header
is stored on every portal sign-in, so whoever can sign in to the account
can plant `<svg onload=…>`, and it runs when the owner opens Devices to
look for sessions they do not recognise.

### Redirects off the domain

`/authorize` redirected an error after the client and redirect URI checked
out, as OAuth asks. A `server` client's redirect URI is an origin any
confirmed account picks through the connect flow, so
`/authorize?client_id=<theirs>&response_type=x` sent anyone who opened it
to that origin without a click. RFC 9700 §4.11.2 asks an authorization
server to redirect automatically only to a URI it trusts. Such a client's
errors are now the error page. `/invite/continue` was a GET that accepted
the invitation and redirected to the server. Anyone can invite themselves
and pass the link on, so it became a POST from the invitation page, which
names the server first. The old GET lands on that page.

The review of those fixes found a third such path: `/zotero/callback`
was not tied to the browser that started the connection. Anyone can
start one for a server they connected (`zotero.known` takes any origin
with a client), copy the request token from the redirect to zotero.org,
and pass on `/zotero/callback?denied=<token>`, which sent whoever opened
it to that server with `error=access_denied`. With a verifier from their
own approval at Zotero, it sent a code. The connect answer now sets a
cookie derived from the request token (`gc_zotero`, for the request's
lifetime), and a callback without it is an error page that leaves the
request for the browser that started it. The cookie is the same kind as
the external sign-in's `gc_ext`: a page on a sibling `*.gammapdf.com`
host that could set cookies for the parent domain could plant one, as it
could for that flow.

### Words in mail from this domain

`accounts._hi` greeted by the display name as stored, with line breaks
and URLs. A registration with someone else's address mailed them "Hi
there. Your account is locked. Restore it at https://…" over several
lines. Running the name through the link filter was tried first. It left
the lure ("Call +1 888 …" needs no link) and turned ordinary names such
as `St.Clair` or `J.R.R.Tolkien` into "[link]". The mails now greet by
the display name, on one line, only at the account's confirmed address,
where the name is the owner's own words to the owner, and by the
username anywhere else.

The link filter's label and top-level classes were ASCII, so
`аккаунт.рф/login` passed. Its exemption for file extensions kept `.md`,
`.py` and `.zip`, which are top-level domains as well. A soft hyphen or a
zero-width space inside a name stopped the match, and a browser resolves
`evil​.com` as `evil.com`; the display name was the one field that
kept such characters. The classes now take letters of other scripts,
those three no longer count as file extensions, and invisible format
characters are dropped first (not ZWJ and ZWNJ, which emoji and some
scripts need). The wider classes first marked `Node.jsの使い方` as a
link, because the CJK letters joined the extension. A top-level domain
is written in one script, so the extension is now the last part's ASCII
letters.

## Checked and clean

The finders' own lists of sinks they traced to an escape or to a value
the server makes, with the verifiers' spot checks:

- **The portal's script literals.** Every inline-script interpolation in
  `pages*.py` goes through `_js`: `next`, the social options, the
  connect body, the invitation's `here` and token, `request_id`,
  `HAS_PW` and `PAID`. An AST scan of the script f-strings found nothing
  else. `_js` output holds no `<`, `>` or `&`, and its `ensure_ascii`
  escapes U+2028 and U+2029.
- **The portal's HTML.** Every value a person, another server or Stripe
  chose goes through `esc`: names, e-mail addresses, server names and
  URLs (https only, `servers.norm_url`), client names, the grant's user
  agent and device name, invitation words. The admin, billing, fleet and
  machines scripts run everything through the script's own `esc` before
  `innerHTML`, apart from numbers and server-made ids.
- **`next`.** `identities.safe_next` keeps it a local path, and the
  pages write it through `_js` or `esc`.
- **Other redirects to a server.** The authorize page's *Continue* and
  *Cancel*, the connect page's buttons and the external sign-in's return
  each follow a click on a page that names the host.
- **The self-hosted server.** `html_page.page` escapes its text and sends
  `default-src 'none'`. SVG and HTML uploads are attachments under a
  sandbox CSP, and the PDF proxy answers `application/pdf`. Zotero note
  links run only inside Zotero. The site worker only redirects.

Two consistency fixes went in with no attack behind them: `esc` on a
server-made account id in the admin script, and on Stripe's status in
the Overview's plan line.

## Not done

- **A Content-Security-Policy on the portal.** A strict one fits: every
  page comes from three shells, with two inline scripts and no inline
  event handlers, so the change is a per-request nonce on them,
  `strict-dynamic`, and origins for Turnstile and Google's one-tap. It was
  declined for now. Nothing in the tests loads Google's script, Turnstile
  or what Cloudflare injects on the proxied hostname (analytics, e-mail
  obfuscation, bot checks), so it would have to start as report-only.
  Until then, the escaping is the whole defence.
- **Top-level domains in scripts with combining marks.** Python's `\w`
  and `[^\W\d_]` leave out marks (Mn, Mc), so `gamma.भारत` passes the
  link filter and `gamma.இந்தியா` comes out half marked. Mark ranges
  could be added to both classes. Fullwidth and ideographic dots
  (`evil．com`, `evil。com`) also pass, on purpose: treating them as dots
  would mark ordinary CJK prose.
- **The connect flow does not prove control of an origin**
  ([email-invitations.md](email-invitations.md) "Still open"). It is
  what lets an account get a `server` client for any https origin.

## Validation

Each finding has a regression test, and each test was seen to fail with
its fix taken out:

- `test_accounts`: markup in every query value a page shows (the mailed
  links' tokens, the register fields, a local `next`, a connect request's
  `state`), each asserted to reach the page escaped; a user agent on
  Devices; the mails' greeting at an unconfirmed address, the account's
  confirmed one and a new one.
- `test_connect`: a connected server's authorize errors are pages.
- `test_invitations`: *Continue* posts, a GET stops at the invitation and
  a cross-site post is refused; the link filter's new cases.
- `test_zotero`: a callback from another browser is a page, and the
  request still works for the browser that started it.

A one-off Playwright drive in Chromium checked the pages themselves,
against the app served from a throwaway test with the suite's own
seeding. The reset page's payload did not run, and its script posted the
token as a string. Devices showed the user agent as text. The old
*Continue* link stopped at the invitation, and the button went on to the
server's sign-in with the invitation's id. No page raised a script
error.

The account server's whole suite passes.
