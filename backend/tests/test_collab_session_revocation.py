"""A revoked session cannot keep a page socket's access to private edits."""

import pytest
from starlette.websockets import WebSocketDisconnect

from conftest import drop_user, login, make_page, make_user, recv_hello
from gamma import collab
from gamma.db import connect_users_db


@pytest.mark.parametrize("revoke", ["logout", "password", "external", "expiry"])
def test_revoked_session_receives_no_later_page_edits(client, revoke):
    name = "socket_session_" + revoke
    make_user(name, "old-password")
    owner = login(name, "old-password")
    stolen = login(name, "old-password")
    page = make_page(owner, "Private live document")
    session = stolen.cookies.get("session")
    with stolen.websocket_connect(f"/api/ws/page/{page['id']}?client=OLD") as sock:
        recv_hello(sock)
        if revoke == "logout":
            stolen.post("/api/logout").raise_for_status()
        elif revoke == "password":
            make_user("socket_reset_admin", "admin-password", is_admin=1)
            admin = login("socket_reset_admin", "admin-password")
            admin.put(f"/api/admin/users/{name}", json={"password": "new-password"}).raise_for_status()
            owner = login(name, "new-password")
            drop_user("socket_reset_admin")
        else:
            # A CLI / other process may change users.db without notifying rooms.
            with connect_users_db() as conn:
                if revoke == "external":
                    conn.execute("DELETE FROM sessions WHERE token = ?", (session,))
                else:
                    conn.execute("UPDATE sessions SET created_at = '2000-01-01T00:00:00Z' WHERE token = ?",
                                 (session,))
        owner.post(f"/api/pages/{page['id']}/ops", json={"client": "NEW", "ops": [
            {"op": "insert", "id": "secret_" + revoke, "parent": page["id"],
             "content": "Written after session revocation"},
        ]}).raise_for_status()
        with pytest.raises(WebSocketDisconnect) as closed:
            while True:
                msg = sock.receive_json()
                assert msg["t"] != "ops", "revoked session received private content"
        assert closed.value.code == collab.CLOSE_REVOKED


@pytest.mark.parametrize("kind", ["cursor", "ink-live"])
def test_revoked_session_cannot_send_presence(client, kind):
    name = "socket_send_" + kind.replace("-", "_")
    make_user(name, "password")
    owner = login(name, "password")
    observer = login(name, "password")
    page = make_page(owner)
    with observer.websocket_connect(f"/api/ws/page/{page['id']}?client=WATCH") as watch:
        recv_hello(watch)
        with owner.websocket_connect(f"/api/ws/page/{page['id']}?client=OLD") as sock:
            recv_hello(sock)
            assert watch.receive_json()["t"] == "join"
            owner.post("/api/logout").raise_for_status()
            sock.send_json({"t": kind, "block": page["id"], "id": "stroke", "samples": []})
            # Before the fix, the observer receives the unauthorized cursor/stroke;
            # after it, only the revoked sender's departure is announced.
            assert watch.receive_json()["t"] == "leave"
            with pytest.raises(WebSocketDisconnect) as closed:
                while True:
                    assert sock.receive_json()["t"] in ("join", "leave")
            assert closed.value.code == collab.CLOSE_REVOKED
