"""The small standalone page of gamma/html_page.py, which the Zotero
sign-in's callback, a fetch handoff's /go and the ChatGPT sign-in's local
listener answer with: its title and text are escaped, its extra parts go
where they belong, and its policy lets a script run only when asked."""

from gamma import html_page


def test_the_title_and_text_are_escaped():
    page = html_page.page('<b>Zotero</b> & "you"', "it's <script>alert(1)</script>")
    assert "<title>&lt;b&gt;Zotero&lt;/b&gt; &amp; &quot;you&quot; · Gamma</title>" in page
    assert "<h1>&lt;b&gt;Zotero&lt;/b&gt; &amp; &quot;you&quot;</h1>" in page
    assert "<p>it&#x27;s &lt;script&gt;alert(1)&lt;/script&gt;</p>" in page
    assert "<script>" not in page and "http-equiv" not in page


def test_the_extra_parts_go_where_they_belong():
    link = '<a href="https://a.example/x?y=1&amp;z=2">Continue to a.example</a>'
    page = html_page.page("Opening a.example…", "Sign in there.", below=link,
                          refresh='https://a.example/x?y=1&z="2"', script="window.close()")
    head, body = page.split("</head>")
    assert '<meta http-equiv="refresh" content="0;url=https://a.example/x?y=1&amp;z=&quot;2&quot;">' in head
    assert f"<p>Sign in there.</p>{link}</main><script>window.close()</script></body>" in body


def test_only_a_page_that_runs_its_script_may():
    quiet, closing = html_page.headers(), html_page.headers(script=True)
    assert quiet["Cache-Control"] == closing["Cache-Control"] == "no-store"
    assert "script-src" not in quiet["Content-Security-Policy"]
    assert "script-src 'unsafe-inline'" in closing["Content-Security-Policy"]
    for policy in (quiet["Content-Security-Policy"], closing["Content-Security-Policy"]):
        assert "default-src 'none'" in policy and "form-action 'self'" in policy
        assert "frame-ancestors 'none'" in policy
