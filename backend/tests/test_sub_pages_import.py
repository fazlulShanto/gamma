"""Notion's pages inside pages come in as sub-pages (docs/research/sub-pages.md):
the markdown-zip import (gamma/markdown_zip_import.py) reads a directory
``Title <id>/`` beside the page's ``Title <id>.md``, or a database's
``Title <id>.csv``, as that page's, its notes sub-pages of the page, filed
where their top is, and the directory makes no folder. A directory that pairs
with nothing stays a folder, a front-matter ``parent:`` wins, a parent left
out of the selection leaves the note filed by its directory, a parent
imported before is named as it is, and the report's ``parent`` says where
each page goes. Vaults and plain zips are untouched."""

import io
import json
import zipfile
from urllib.parse import quote

import pytest

from conftest import folder_names, login, make_folder, make_user, workspace_of
from gamma import jobs, markdown_zip_import
from gamma.db import connect_pages_db

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32


@pytest.fixture(scope="module")
def owner():
    """An account of the module's own: a Notion id already imported by
    another module's page would be passed by here."""
    make_user("spim_owner", "pw-spim-1")
    return login("spim_owner", "pw-spim-1")


def _name(title: str, n: int) -> str:
    """A Notion file or directory name: the title and a 32-hex id."""
    return f"{title} {n:032x}"


def _zip(files: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in files.items():
            zf.writestr(name, data if isinstance(data, bytes) else data.encode("utf-8"))
    return buf.getvalue()


def _import(c, data: bytes, folder: str, selected=None) -> dict:
    """The report's page entries by the note's path in the zip."""
    form = {"folder": folder} if selected is None else {"folder": folder, "selected": json.dumps(selected)}
    r = c.post("/api/import/markdown-zip", files={"file": ("export.zip", data, "application/zip")}, data=form)
    assert r.status_code == 200, r.text
    return {p["selection_ids"][0]: p for p in r.json()["pages"]}


def _preview(c, data: bytes, folder: str) -> dict:
    r = c.post("/api/import/markdown-zip/preview", files={"file": ("export.zip", data, "application/zip")},
               data={"folder": folder})
    assert r.status_code == 200, r.text
    return {p["selection_ids"][0]: p for p in r.json()["pages"]}


def _props(c, page_id) -> dict:
    r = c.get(f"/api/blocks/{page_id}")
    assert r.status_code == 200, r.text
    return r.json()["properties"]


def _children(c, page_id) -> list[str]:
    return [b["content"] for b in c.get(f"/api/blocks/{page_id}/subtree").json()["block"]["children"]]


def _below(c, top: str) -> list[list[str]]:
    """The folder paths at and below the folder named ``top``."""
    return sorted(p for p in folder_names(c).values() if p[0] == top)


def _notion(base: int) -> tuple[dict, dict]:
    """A Notion "Markdown & CSV" export, its ids from ``base``: Home holds
    Sub (which holds Deep), a picture and the database Tasks (whose row is
    Row); Other is a page of its own; Loose/ pairs with no page. Every
    note's text names ``base`` too (an import passes by a note whose bytes
    it holds already). Returns the zip's files and the note paths by name."""
    home, sub, deep = _name("Home", base), _name("Sub", base + 1), _name("Deep", base + 2)
    tasks, row, other = _name("Tasks", base + 3), _name("Row", base + 4), _name("Other", base + 5)
    loose, orphan = _name("Loose", base + 6), _name("Orphan", base + 7)
    path = {"home": f"{home}.md", "sub": f"{home}/{sub}.md", "deep": f"{home}/{sub}/{deep}.md",
            "tasks": f"{home}/{tasks}_all.csv", "row": f"{home}/{tasks}/{row}.md", "other": f"{other}.md",
            "orphan": f"{loose}/{orphan}.md"}
    files = {
        path["home"]: f"# Home\n\nSee [Sub]({quote(path['sub'])}).\n\n![diagram.png]({quote(home)}/diagram.png)\n",
        f"{home}/diagram.png": PNG,
        path["sub"]: f"# Sub\n\nBack to [Home](../{quote(home)}.md).\n",
        path["deep"]: f"# Deep\n\nThe bottom of {base:x}.\n",
        f"{home}/{tasks}.csv": f"Name,Status\nRow {base:x},Done\n",
        path["tasks"]: f"Name,Status\nRow {base:x},Done\nMore,Todo\n",
        path["row"]: f"# Row\n\nStatus: Done in {base:x}\n",
        path["other"]: f"# Other\n\nA page of its own, {base:x}.\n",
        path["orphan"]: f"# Orphan\n\nIn a directory of no page, {base:x}.\n",
    }
    return files, path


def test_a_notion_page_tree_comes_in_as_sub_pages(owner):
    files, path = _notion(0x5100)
    dest = make_folder(owner, "SPIM tree")
    pages = _import(owner, _zip(files), dest)
    page = {k: pages[p] for k, p in path.items()}
    parent = {"home": "", "sub": page["home"]["id"], "deep": page["sub"]["id"], "tasks": page["home"]["id"],
              "row": page["tasks"]["id"], "other": "", "orphan": ""}
    assert {k: p["parent"] for k, p in page.items()} == parent
    for k, up in parent.items():
        props = _props(owner, page[k]["id"])
        assert props.get("parent_page", "") == up
        # the destination files the top pages only; a sub-page is where its top is
        assert ("folders" in props) == (not up)
    assert [page[k]["folders"] for k in ("home", "sub", "deep", "tasks", "row", "other")] == [[["SPIM tree"]]] * 6
    assert page["orphan"]["folders"] == [["SPIM tree", "Loose"]]
    assert _below(owner, "SPIM tree") == [["SPIM tree"], ["SPIM tree", "Loose"]]  # no folder for a page's directory
    family = owner.get(f"/api/pages/{page['home']['id']}/family").json()
    assert {p["id"] for p in family["sub_pages"]} == {page["sub"]["id"], page["tasks"]["id"]}
    # links and the picture in a page's directory resolve as before
    home = _children(owner, page["home"]["id"])
    assert home[0] == f"See [[{page['sub']['id']}]]."
    assert home[1].startswith("![diagram.png](/api/uploads/") and owner.get(home[1][15:-1]).status_code == 200
    assert _children(owner, page["sub"]["id"]) == [f"Back to [[{page['home']['id']}]]."]
    assert _children(owner, page["tasks"]["id"])[0].startswith("| Name | Status |")


def test_a_preview_names_the_parents_and_makes_no_folder(owner):
    files, path = _notion(0x5200)
    dest = make_folder(owner, "SPIM preview")
    pages = _preview(owner, _zip(files), dest)
    page = {k: pages[p] for k, p in path.items()}
    assert page["deep"]["parent"] == page["sub"]["id"] and page["sub"]["parent"] == page["home"]["id"]
    assert page["row"]["parent"] == page["tasks"]["id"] and page["tasks"]["parent"] == page["home"]["id"]
    assert page["home"]["parent"] == page["other"]["parent"] == page["orphan"]["parent"] == ""
    assert page["deep"]["folders"] == [["SPIM preview"]] and page["orphan"]["folders"] == [["SPIM preview", "Loose"]]
    assert _below(owner, "SPIM preview") == [["SPIM preview"]]
    review = owner.post("/api/import/review", data={"source": "markdown-zip", "folder": dest},
                        files={"file": ("export.zip", _zip(files), "application/zip")})
    assert review.status_code == 200, review.text
    entries = {p["selection_ids"][0]: p for p in review.json()["pages"]}
    assert entries[path["deep"]]["parent"] == entries[path["sub"]]["id"]


def test_part_zips_pair_across_parts(owner):
    """Notion splits a big export into ``Part-N.zip`` members, each in an
    ``Export-<uuid>/`` wrapper: a page and the pages inside it may sit in
    different parts."""
    top, kid = _name("Split", 0x5300), _name("Kid", 0x5301)
    wrap = "Export-1b2c3d4e-0000-1111-2222-333344445555"
    data = _zip({"Part-1.zip": _zip({f"{wrap}/{top}.md": "# Split\n\nPart one.\n"}),
                 "Part-2.zip": _zip({f"{wrap}/{top}/{kid}.md": "# Kid\n\nPart two.\n"})})
    pages = _import(owner, data, make_folder(owner, "SPIM parts"))
    assert pages[f"{top}/{kid}.md"]["parent"] == pages[f"{top}.md"]["id"]
    assert _props(owner, pages[f"{top}/{kid}.md"]["id"])["parent_page"] == pages[f"{top}.md"]["id"]
    assert _below(owner, "SPIM parts") == [["SPIM parts"]]


def test_a_re_import_adds_nothing_and_names_the_pages_imported_before(owner):
    files, path = _notion(0x5400)
    dest = make_folder(owner, "SPIM again")
    first = _import(owner, _zip(files), dest)
    before = {p["id"]: _props(owner, p["id"]) for p in first.values()}
    again = _import(owner, _zip(files), dest)
    assert {p: (e["id"], e["parent"], e["folders"], e["action"]) for p, e in again.items()} == {
        p: (e["id"], e["parent"], e["folders"], "skip") for p, e in first.items()}
    assert {p["id"]: _props(owner, p["id"]) for p in again.values()} == before
    # a page added inside a page imported before goes inside that page, which is not changed
    home = first[path["home"]]["id"]
    new = f"{path['home'][:-3]}/{_name('New', 0x5408)}.md"
    pages = _import(owner, _zip({**files, new: "# New\n\nAdded later.\n"}), dest)
    assert pages[new]["action"] == "create" and pages[new]["parent"] == home
    assert pages[new]["folders"] == [["SPIM again"]]
    assert _props(owner, pages[new]["id"])["parent_page"] == home and "folders" not in _props(owner, pages[new]["id"])
    assert _props(owner, home) == before[home]
    assert _below(owner, "SPIM again") == [["SPIM again"], ["SPIM again", "Loose"]]


def test_a_deselected_parent_leaves_the_note_filed_by_its_directory(owner):
    files, path = _notion(0x5500)
    data = _zip(files)
    dest = make_folder(owner, "SPIM pick")
    pages = _import(owner, data, dest, selected=[path["home"], path["deep"], path["row"], path["tasks"]])
    assert path["sub"] not in pages
    assert pages[path["deep"]]["parent"] == "" and "parent_page" not in _props(owner, pages[path["deep"]]["id"])
    assert pages[path["deep"]]["folders"] == [["SPIM pick", "Home", "Sub"]]
    assert pages[path["row"]]["parent"] == pages[path["tasks"]]["id"]
    assert pages[path["tasks"]]["parent"] == pages[path["home"]]["id"]
    assert _below(owner, "SPIM pick") == [["SPIM pick"], ["SPIM pick", "Home"], ["SPIM pick", "Home", "Sub"]]
    # its top left out, Sub is a library page in Home's directory, Deep still inside it
    files, path = _notion(0x5600)
    pages = _import(owner, _zip(files), make_folder(owner, "SPIM pick top"), selected=[path["sub"], path["deep"]])
    assert pages[path["sub"]]["parent"] == "" and pages[path["sub"]]["folders"] == [["SPIM pick top", "Home"]]
    assert pages[path["deep"]]["parent"] == pages[path["sub"]]["id"]
    assert pages[path["deep"]]["folders"] == [["SPIM pick top", "Home"]]


def test_the_review_says_where_a_sub_page_goes_without_its_parent(owner):
    """The preview, made with every note chosen, reports for each new
    sub-page where it is filed when the page it is inside is left out: the
    review shows a page ticked without its parent there, and an import with
    that selection files it exactly there."""
    files, path = _notion(0x5700)
    data = _zip(files)
    preview = _preview(owner, data, make_folder(owner, "SPIM alone"))
    alone = {k: preview[p].get("folders_alone") for k, p in path.items()}
    assert alone == {"home": None, "sub": [["SPIM alone", "Home"]], "deep": [["SPIM alone", "Home", "Sub"]],
                     "tasks": [["SPIM alone", "Home"]], "row": [["SPIM alone", "Home", "Tasks"]],
                     "other": None, "orphan": None}
    # Home left out: Sub, Tasks and Row go where the review said; Deep stays inside Sub
    pages = _import(owner, data, make_folder(owner, "SPIM alone 2"),
                    selected=[path["sub"], path["deep"], path["tasks"], path["row"]])
    shown = {k: preview[path[k]]["folders_alone"][0][1:] for k in ("sub", "tasks")}
    for k in ("sub", "tasks"):
        assert pages[path[k]]["parent"] == ""
        assert pages[path[k]]["folders"] == [["SPIM alone 2", *shown[k]]]
    assert pages[path["deep"]]["parent"] == pages[path["sub"]]["id"]
    assert pages[path["row"]]["parent"] == pages[path["tasks"]]["id"]

def test_a_parent_left_out_is_no_parent_and_nothing_takes_its_place(owner):
    """A ``parent:`` left out of the selection names no page: neither
    another note of the same name nor the Notion page whose directory holds
    the note takes its place, so the note goes where the review said."""
    # another note of the parent's name elsewhere in the zip
    files = {"A/Notes.md": "---\nfolder: A\n---\n# Notes\n\nA's notes, 5900.\n",
             "A/Notes/Child.md": "---\nparent: ../Notes.md\n---\n# Child\n\nUnder A's notes, 5900.\n",
             "B/Notes.md": "---\nfolder: B\n---\n# Notes\n\nB's notes, 5900.\n"}
    data = _zip(files)
    preview = _preview(owner, data, make_folder(owner, "SPIM same"))
    assert preview["A/Notes/Child.md"]["folders_alone"] == [["SPIM same", "A", "Notes"]]
    pages = _import(owner, data, make_folder(owner, "SPIM same 2"), selected=["A/Notes/Child.md", "B/Notes.md"])
    assert pages["A/Notes/Child.md"]["parent"] == ""
    assert pages["A/Notes/Child.md"]["folders"] == [["SPIM same 2", "A", "Notes"]]
    # a Notion page's directory holds a note whose parent: names another page
    home, sub = _name("Home", 0x5901), _name("Sub", 0x5902)
    files = {f"{home}.md": "# Home\n\nThe directory's page, 5901.\n", "Other.md": "# Other\n\nThe named parent, 5901.\n",
             f"{home}/{sub}.md": "---\nparent: ../Other.md\n---\n# Sub\n\nNamed under Other, 5901.\n"}
    data = _zip(files)
    preview = _preview(owner, data, make_folder(owner, "SPIM named"))
    assert preview[f"{home}/{sub}.md"]["parent"] == preview["Other.md"]["id"]
    pages = _import(owner, data, make_folder(owner, "SPIM named 2"), selected=[f"{home}.md", f"{home}/{sub}.md"])
    assert pages[f"{home}/{sub}.md"]["parent"] == ""
    assert pages[f"{home}/{sub}.md"]["folders"] == [["SPIM named 2", *preview[f"{home}/{sub}.md"]["folders_alone"][0][1:]]]


def test_a_cycle_cut_in_the_preview_stays_cut_whatever_is_ticked(owner):
    """Parents that close a cycle are dropped over the whole zip, so leaving
    one of the pages out does not make the others a chain the review never
    showed."""
    files = {"X.md": "---\nparent: Z.md\n---\n# X\n\n5a10\n", "Y.md": "---\nparent: X.md\n---\n# Y\n\n5a10\n",
             "Z.md": "---\nparent: Y.md\n---\n# Z\n\n5a10\n"}
    data = _zip(files)
    preview = _preview(owner, data, make_folder(owner, "SPIM cycle"))
    assert {p["parent"] for p in preview.values()} == {""} and not any("folders_alone" in p for p in preview.values())
    pages = _import(owner, data, make_folder(owner, "SPIM cycle 2"), selected=["Y.md", "Z.md"])
    assert pages["Y.md"]["parent"] == pages["Z.md"]["parent"] == ""
    assert pages["Z.md"]["folders"] == [["SPIM cycle 2"]]


def test_a_stopped_import_leaves_no_page_inside_one_it_never_wrote(owner):
    """A database's CSV is read after every note, yet its table page is
    written before its rows: an import stopped at the table leaves no row
    naming a page that is not there, and importing again puts the row
    inside the table."""
    home, tasks, row = _name("Home", 0x5a00), _name("Tasks", 0x5a01), _name("Row", 0x5a02)
    files = {f"{home}.md": "# Home\n\nthe stopped import's home\n", f"{home}/{tasks}.csv": "Name\nRow\n",
             f"{home}/{tasks}/{row}.md": "# Row\n\nthe stopped import's row\n"}
    dest = make_folder(owner, "SPIM stop")
    ws = workspace_of("spim_owner")
    heard = []

    def progress(item, **_):
        heard.append(item)
        if item == "Tasks":
            raise jobs.Cancelled()

    with connect_pages_db(ws) as conn:
        with pytest.raises(jobs.Cancelled):
            markdown_zip_import.import_markdown_zip(ws, zipfile.ZipFile(io.BytesIO(_zip(files))), conn, dest,
                                                    actor="spim_owner", progress=progress)
        missing = conn.execute(
            "SELECT c.content FROM unified_blocks c LEFT JOIN unified_blocks p ON p.id = c.parent_page "
            "WHERE c.parent_id = 'root' AND c.parent_page IS NOT NULL AND p.id IS NULL").fetchall()
    assert missing == [] and heard == ["Home", "Tasks"]
    pages = _import(owner, _zip(files), dest)
    assert pages[f"{home}/{tasks}/{row}.md"]["action"] == "create"
    assert _props(owner, pages[f"{home}/{tasks}/{row}.md"]["id"])["parent_page"] == pages[f"{home}/{tasks}.csv"]["id"]


def test_two_databases_of_one_name_keep_a_table_and_rows_each(owner):
    """Notion leaves many an inline database "Untitled": two in one page
    are two table pages, each with its own rows inside it."""
    home, first, second = _name("Home", 0x5b00), _name("Untitled", 0x5b01), _name("Untitled", 0x5b02)
    row_a, row_b = _name("Row A", 0x5b03), _name("Row B", 0x5b04)
    files = {f"{home}.md": "# Home\n\nthe two databases' home\n"}
    for table, row in ((first, row_a), (second, row_b)):
        files |= {f"{home}/{table}.csv": f"Name\n{row}\n", f"{home}/{table}_all.csv": f"Name\n{row}\n",
                  f"{home}/{table}/{row}.md": f"# {row[:5]}\n\na row of {table}\n"}
    pages = _import(owner, _zip(files), make_folder(owner, "SPIM untitled"))
    tables = [pages[f"{home}/{table}_all.csv"]["id"] for table in (first, second)]
    assert len(set(tables)) == 2
    assert [pages[f"{home}/{table}/{row}.md"]["parent"] for table, row in ((first, row_a), (second, row_b))] == tables
    assert [_children(owner, t)[0].splitlines()[2] for t in tables] == [f"| {row_a} |", f"| {row_b} |"]


def test_a_front_matter_parent_wins_over_the_directory(owner):
    top, other, kid = _name("Top", 0x5700), _name("Elsewhere", 0x5701), _name("Kid", 0x5702)
    data = _zip({f"{top}.md": "# Top\n\nthe front test's top\n", f"{other}.md": "# Elsewhere\n\nelsewhere\n",
                 f"{top}/{kid}.md": f"---\nparent: ../{quote(other)}.md\n---\n# Kid\n\nkid\n"})
    pages = _import(owner, data, make_folder(owner, "SPIM front"))
    assert pages[f"{top}/{kid}.md"]["parent"] == pages[f"{other}.md"]["id"]


def test_the_depth_counts_the_library_s_pages_above(owner, monkeypatch):
    """A chain cut at MAX_PAGE_DEPTH counts the pages already in the library
    that a new note's parent sits below."""
    top, mid, low, lowest = (_name(t, 0x5800 + n) for n, t in enumerate(("Top", "Mid", "Low", "Lowest")))
    files = {f"{top}.md": "# Top\n\nthe depth test's top\n", f"{top}/{mid}.md": "# Mid\n\nmid\n"}
    dest = make_folder(owner, "SPIM deep")
    first = _import(owner, _zip(files), dest)
    monkeypatch.setattr(markdown_zip_import, "MAX_PAGE_DEPTH", 3)
    files |= {f"{top}/{mid}/{low}.md": "# Low\n\nlow\n", f"{top}/{mid}/{low}/{lowest}.md": "# Lowest\n\nlowest\n"}
    pages = _import(owner, _zip(files), dest)
    assert pages[f"{top}/{mid}/{low}.md"]["parent"] == first[f"{top}/{mid}.md"]["id"]
    assert pages[f"{top}/{mid}/{low}/{lowest}.md"]["parent"] == ""
    assert pages[f"{top}/{mid}/{low}/{lowest}.md"]["folders"] == [["SPIM deep", "Top", "Mid", "Low"]]


@pytest.mark.parametrize("vault", [True, False])
def test_a_vault_or_a_plain_zip_keeps_its_folders(owner, vault):
    """``Topic.md`` beside ``Topic/`` carries no Notion id: the directory is a
    folder, as before."""
    top = f"SPIM {'vault' if vault else 'plain'}"
    files = {"Topic.md": f"# Topic\n\nin {top}\n", "Topic/Note.md": f"# Note\n\nin {top}\n"}
    if vault:
        files[".obsidian/app.json"] = "{}"
    pages = _import(owner, _zip(files), make_folder(owner, top))
    assert pages["Topic/Note.md"]["parent"] == "" and "parent_page" not in _props(owner, pages["Topic/Note.md"]["id"])
    assert pages["Topic/Note.md"]["folders"] == [[top, "Topic"]]
    assert _below(owner, top) == [[top], [top, "Topic"]]
