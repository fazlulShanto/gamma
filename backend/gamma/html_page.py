"""The small page a browser tab lands on outside the app: the end of a
sign-in (the Zotero round trip's callback, the ChatGPT sign-in caught on
this machine) or a fetch handoff's ``/go``. A title over a sentence, in
light and dark, with what the page needs besides: a form or a link under
the text, an address to go on to at once, a one-line script.

None of these pages involves the frontend build or its theme tokens (the
ChatGPT listener is not even this server), so the palette is spelled out
here. ``app.py``'s page for a data directory this build cannot upgrade is
a richer page of its own.
"""

import html

_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
{refresh}<title>{title} · Gamma</title>
<style>
:root {{ color-scheme: light dark; --fg: #1f2328; --muted: #59636e; --bg: #ffffff; --accent: #3a7bd5; }}
@media (prefers-color-scheme: dark) {{
  :root {{ --fg: #e6edf3; --muted: #9198a1; --bg: #16181d; --accent: #5b9bf0; }}
}}
body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--bg);
  color: var(--fg); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }}
main {{ max-width: 34rem; padding: 24px 16px; }}
h1 {{ font-size: 1.25rem; margin: 0 0 8px; overflow-wrap: anywhere; }}
p {{ margin: 0; color: var(--muted); overflow-wrap: anywhere; }}
p:not(:last-child) {{ margin-bottom: 16px; }}
button {{ font: inherit; padding: 6px 18px; border: 0; border-radius: 6px; background: var(--fg); color: var(--bg);
  cursor: pointer; }}
a {{ display: inline-block; padding: 8px 14px; border-radius: 8px; background: var(--accent);
  color: #fff; text-decoration: none; font-weight: 600; overflow-wrap: anywhere; }}
</style></head>
<body><main><h1>{title}</h1><p>{text}</p>{below}</main>{script}</body></html>
"""


def page(title: str, text: str, *, below: str = "", refresh: str = "", script: str = "") -> str:
    """The page: ``title``, which also names the tab, over ``text``, both
    plain text escaped here. ``below`` is markup put under the text as it
    is (a form, a link), ``refresh`` an address the tab goes on to at once,
    and ``script`` a line of JavaScript the page runs (``headers`` must
    then allow it)."""
    return _PAGE.format(
        title=html.escape(title), text=html.escape(text), below=below,
        refresh=f'<meta http-equiv="refresh" content="0;url={html.escape(refresh)}">\n' if refresh else "",
        script=f"<script>{script}</script>" if script else "")


def headers(*, script: bool = False) -> dict:
    """The page's response headers: never cached, and a policy under which
    it loads nothing, is framed nowhere and posts a form only to its own
    server. ``script`` lets it run its inline script."""
    runs = "script-src 'unsafe-inline'; " if script else ""
    return {"Cache-Control": "no-store",
            "Content-Security-Policy": f"default-src 'none'; style-src 'unsafe-inline'; {runs}form-action 'self'; "
                                       "frame-ancestors 'none'"}
