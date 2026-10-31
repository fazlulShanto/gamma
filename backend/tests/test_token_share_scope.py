"""Share URLs cannot enlarge an HTTP integration token's authority."""

import pytest

from conftest import account_of, fresh_client, login, make_page, make_user
from gamma.integrations import create_token


@pytest.fixture(scope="module")
def library(client):
    ws = make_user("token_share_owner", "pw")
    owner = login("token_share_owner", "pw")
    page = make_page(owner, "Shared within token workspace")
    share = owner.post(f"/api/share/{page['id']}", json={"audience": "list", "role": "view"}).json()["token"]
    other_ws = owner.post("/api/workspaces", json={"name": "Outside token workspace"}).json()["id"]
    owner.headers["X-Gamma-Workspace"] = other_ws
    other_page = make_page(owner, "Private other workspace")
    other_share = owner.post(f"/api/share/{other_page['id']}",
                             json={"audience": "list", "role": "view"}).json()["token"]
    owner.headers["X-Gamma-Workspace"] = ws
    return owner, ws, page, share, other_ws, other_page, other_share


def bearer(ws, scope="read"):
    token = create_token(account_of("token_share_owner"), ws, "share boundary", 90, scope=scope)["token"]
    c = fresh_client()
    c.headers["Authorization"] = f"Bearer {token}"
    return c


def test_view_share_cannot_turn_a_read_token_into_a_writer(library):
    owner, ws, page, share, *_ = library
    c = bearer(ws)
    # No secret beyond the read token is needed: share settings are readable.
    assert c.get(f"/api/share-settings/{page['id']}").json()["token"] == share
    assert c.get(f"/api/blocks/{page['id']}", params={"share": share}).status_code == 200
    result = c.post(f"/api/pages/{page['id']}/ops", params={"share": share}, json={
        "client": "token-share-test", "ops": [
            {"op": "insert", "id": "token_share_injected", "parent": page["id"],
             "position": "a0", "content": "Unauthorized write"},
        ],
    })
    assert result.status_code == 403, result.text
    assert owner.get("/api/blocks/token_share_injected").status_code == 404
    resolved = c.get(f"/api/share/{share}")
    assert resolved.status_code == 200 and resolved.json()["can_edit"] is False


@pytest.mark.parametrize("scope", ["read", "write"])
def test_share_cannot_select_another_workspace(library, scope):
    _, ws, _, _, _, other_page, other_share = library
    c = bearer(ws, scope)
    for path, params in (
        (f"/api/blocks/{other_page['id']}", {"share": other_share}),
        (f"/api/pages/{other_page['id']}/ops", {"share": other_share}),
        (f"/api/share/{other_share}", {}),
    ):
        result = c.get(path, params=params)
        assert result.status_code == 403, (path, result.text)
    result = c.post(f"/api/pages/{other_page['id']}/comments", params={"share": other_share},
                    json={"block_id": other_page["id"], "body": "Outside token scope"})
    assert result.status_code == 403, result.text


def test_write_token_can_still_edit_its_shared_page(library):
    _, ws, page, share, *_ = library
    c = bearer(ws, "write")
    result = c.post(f"/api/pages/{page['id']}/ops", params={"share": share}, json={
        "client": "token-share-test", "ops": [
            {"op": "insert", "id": "token_share_allowed", "parent": page["id"],
             "position": "a1", "content": "Authorized write"},
        ],
    })
    assert result.status_code == 200, result.text
    assert c.get(f"/api/share/{share}").json()["can_edit"] is True
