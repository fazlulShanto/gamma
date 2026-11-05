"""Long work as background jobs (docs/dev/tasks.md): a workspace's delete
and an account's, which zip a final copy first and can be stopped until it
is written, never after; the admin's database check; and a server
snapshot's download, streamed as it is zipped."""

import io
import threading
import zipfile

import pytest

from conftest import account_of, drop_user, login, make_user
from gamma import jobs, workspaces, ws_backup
from gamma.db import ws_dir

PW = "dj-pass-12345"


@pytest.fixture(scope="module")
def boss(client):
    make_user("dj_boss", PW, is_admin=1)
    yield login("dj_boss", PW)
    drop_user("dj_boss")


def _account(name):
    home = make_user(name, PW)
    return home, login(name, PW)


def _start(c, route, body):
    r = c.post(f"/api/jobs/{route}", json=body)
    assert r.status_code == 200, r.text
    return r.json()


class Gate:
    """Holds a run at one point until the test lets it go."""

    def __init__(self):
        self.reached, self.go = threading.Event(), threading.Event()

    def hold(self):
        self.reached.set()
        assert self.go.wait(10)

    def wait(self):
        assert self.reached.wait(10)


def _copies(ws):
    d = ws_backup.deleted_dir()
    return sorted(d.glob(f"*{ws}*")) if d.is_dir() else []


def test_a_workspace_delete_job_keeps_a_final_copy_then_deletes():
    _home, c = _account("dj_plain")
    doomed = c.post("/api/workspaces", json={"name": "Old lab"}).json()["id"]
    c.post("/api/pages", json={"title": "Kept in the copy"}, headers={"X-Gamma-Workspace": doomed})
    started = _start(c, "workspace-delete", {"ws": doomed})
    assert started["kind"] == "workspace-delete" and started["workspace"] == doomed
    assert started["params"] == {"ws": doomed, "name": "Old lab"} and started["title"] == "Delete Old lab"
    job = jobs.wait(started["id"])
    assert job["state"] == "done", job["error"]
    assert job["progress"]["phase"] == "deleting" and job["progress"]["item"] == "Old lab"
    assert job["result"]["warning"] == "" and job["result"]["final_copy"].startswith(f"{doomed}-Old-lab-")
    assert not workspaces.get(doomed) and not ws_dir(doomed).exists() and not workspaces.is_deleting(doomed)
    assert (ws_backup.deleted_dir() / job["result"]["final_copy"]).is_file()
    # the starter still sees the row of a workspace that is gone
    assert any(j["id"] == job["id"] for j in c.get("/api/jobs").json()["jobs"])


def test_a_stop_during_the_final_copy_deletes_nothing_and_keeps_no_copy(monkeypatch):
    _home, c = _account("dj_stop")
    doomed = c.post("/api/workspaces", json={"name": "Stop me"}).json()["id"]
    gate = Gate()

    def half_written(ws, dest, *, progress=None, **kw):
        dest.write_bytes(b"half a zip")
        progress(done=10, total=100, unit="bytes")
        gate.hold()
        progress(done=20, total=100, unit="bytes")  # where the stop lands

    monkeypatch.setattr(ws_backup, "write_zip", half_written)
    started = _start(c, "workspace-delete", {"ws": doomed})
    gate.wait()
    running = c.get(f"/api/jobs/{started['id']}").json()
    assert running["progress"] == {"phase": "keeping", "done": 10, "total": 100, "unit": "bytes", "item": "Stop me"}
    assert running["stoppable"] is True
    assert c.post(f"/api/jobs/{started['id']}/cancel").json()["stopping"] is True
    gate.go.set()
    assert jobs.wait(started["id"])["state"] == "cancelled"
    assert workspaces.get(doomed) and ws_dir(doomed).is_dir() and not workspaces.is_deleting(doomed)
    assert _copies(doomed) == []  # neither the copy nor its .part


def test_a_stop_just_after_the_copy_still_deletes_nothing(monkeypatch):
    """Stop pressed once the copy is written, before the first row goes:
    the point of no return honours it rather than swallowing it."""
    _home, c = _account("dj_edge")
    doomed = c.post("/api/workspaces", json={"name": "Edge case"}).json()["id"]
    real, gate = ws_backup.keep_final_copy, Gate()

    def copied_then_held(ws, **kw):
        name = real(ws, **kw)
        gate.hold()
        return name

    monkeypatch.setattr(ws_backup, "keep_final_copy", copied_then_held)
    started = _start(c, "workspace-delete", {"ws": doomed})
    gate.wait()
    assert c.post(f"/api/jobs/{started['id']}/cancel").json()["stopping"] is True
    gate.go.set()
    assert jobs.wait(started["id"])["state"] == "cancelled"
    assert workspaces.get(doomed) and ws_dir(doomed).is_dir()
    assert _copies(doomed) == []


def test_a_delete_past_its_final_copy_can_no_longer_be_stopped(monkeypatch):
    _home, c = _account("dj_late")
    doomed = c.post("/api/workspaces", json={"name": "Too late"}).json()["id"]
    real, gate = workspaces.remove_files, Gate()

    def held(ws):
        gate.hold()
        return real(ws)

    monkeypatch.setattr(workspaces, "remove_files", held)
    started = _start(c, "workspace-delete", {"ws": doomed})
    gate.wait()
    running = c.get(f"/api/jobs/{started['id']}").json()
    assert running["stoppable"] is False and running["progress"]["phase"] == "deleting"
    assert c.post(f"/api/jobs/{started['id']}/cancel").status_code == 409
    gate.go.set()
    assert jobs.wait(started["id"])["state"] == "done"
    assert not workspaces.get(doomed)


def test_what_a_delete_start_refuses_at_once(monkeypatch):
    home, c = _account("dj_refuse")
    assert c.post("/api/jobs/workspace-delete", json={"ws": home}).status_code == 400  # the last personal one
    other = c.post("/api/workspaces", json={"name": "Held"}).json()["id"]
    monkeypatch.setattr(workspaces, "_deleting", {other})  # a delete holds it
    r = c.post("/api/jobs/workspace-delete", json={"ws": other})
    assert r.status_code == 400 and "already being deleted" in r.json()["detail"]
    assert c.delete(f"/api/workspaces/{other}").status_code == 400
    assert c.post("/api/jobs/workspace-delete", json={"ws": "nope"}).status_code == 404
    _stranger, s = _account("dj_stranger")
    assert s.post("/api/jobs/workspace-delete", json={"ws": other}).status_code == 404


def test_a_second_start_answers_the_running_delete(monkeypatch):
    _home, c = _account("dj_twice")
    doomed = c.post("/api/workspaces", json={"name": "Twice"}).json()["id"]
    real, gate = ws_backup.keep_final_copy, Gate()

    def held(ws, **kw):
        gate.hold()
        return real(ws, **kw)

    monkeypatch.setattr(ws_backup, "keep_final_copy", held)
    first = _start(c, "workspace-delete", {"ws": doomed})
    gate.wait()
    assert _start(c, "workspace-delete", {"ws": doomed})["id"] == first["id"]
    gate.go.set()
    assert jobs.wait(first["id"])["state"] == "done"
    assert len(_copies(doomed)) == 1


def test_a_failed_final_copy_fails_the_job_with_its_sentence(monkeypatch):
    _home, c = _account("dj_full")
    doomed = c.post("/api/workspaces", json={"name": "Disk full"}).json()["id"]

    def full(*a, **k):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(ws_backup, "write_zip", full)
    job = jobs.wait(_start(c, "workspace-delete", {"ws": doomed})["id"])
    assert job["state"] == "failed"
    assert job["error"].startswith("nothing was deleted — workspace “Disk full”") and "unexpected" not in job["error"]
    assert workspaces.get(doomed) and _copies(doomed) == []


def test_an_account_delete_job(boss, monkeypatch):
    home = make_user("dj_leaver", PW)
    lab = boss.post("/api/workspaces", json={"name": "Leaver lab", "kind": "shared", "owner": "dj_leaver"}).json()["id"]
    real = ws_backup.keep_final_copy

    def lab_fails(ws, **kw):
        if ws == lab:
            raise ws_backup.BackupError("its final copy could not be written (disk full)")
        return real(ws, **kw)

    monkeypatch.setattr(ws_backup, "keep_final_copy", lab_fails)
    started = _start(boss, "account-delete", {"username": "dj_leaver"})
    assert started["kind"] == "account-delete" and started["params"] == {"username": "dj_leaver", "guest": False}
    failed = jobs.wait(started["id"])
    assert failed["state"] == "failed" and "Leaver lab" in failed["error"] and "unexpected" not in failed["error"]
    assert account_of("dj_leaver") and workspaces.get(home) and _copies(home) == []
    monkeypatch.setattr(ws_backup, "keep_final_copy", real)
    job = jobs.wait(_start(boss, "account-delete", {"username": "dj_leaver"})["id"])
    assert job["state"] == "done", job["error"]
    assert set(job["result"]["deleted_workspaces"]) == {home, lab} and not account_of("dj_leaver")
    assert job["progress"] == {"phase": "deleting", "item": "dj_leaver"}
    assert _copies(home) and _copies(lab)


def test_an_account_delete_start_refuses_a_workspace_another_delete_holds(boss, monkeypatch):
    home = make_user("dj_heldacct", PW)
    monkeypatch.setattr(workspaces, "_deleting", {home})
    r = boss.post("/api/jobs/account-delete", json={"username": "dj_heldacct"})
    assert r.status_code == 400 and "already being deleted" in r.json()["detail"]


def test_two_co_owners_deleted_together_never_take_their_workspace_without_a_copy(boss, monkeypatch):
    """X and Y own a lab together, and both accounts are deleted at once:
    Y's list of what goes was taken while X still owned it, so once X went
    the lab would go with Y uncopied. Account deletes pass their final
    copies one at a time, and Y's finds its list changed and stops."""
    from gamma import cloud_auth, zotero_account

    x_home, y_home = make_user("dj_cox", PW), make_user("dj_coy", PW)
    lab = boss.post("/api/workspaces", json={"name": "Co lab", "kind": "shared", "owner": "dj_cox"}).json()["id"]
    assert boss.put(f"/api/workspaces/{lab}/members/dj_coy", json={"role": "owner"}).status_code == 200
    x, y, by = account_of("dj_cox"), account_of("dj_coy"), account_of("dj_boss")
    real_copies, real_grant, real_disconnect = workspaces.keep_final_copies, cloud_auth.grant_of, zotero_account.disconnect
    y_copying, y_checked, outcome = threading.Event(), threading.Event(), {}

    def copies(ids, **kw):
        if y_home in ids:
            y_copying.set()
        return real_copies(ids, **kw)

    def grant(user_id):  # the first step past the list's check
        if user_id == y:
            y_checked.set()
        return real_grant(user_id)

    def delete_y():
        try:
            outcome["y"] = workspaces.delete_account(y, by=by)
        except ValueError as e:
            outcome["y"] = e

    def disconnect(user_id):  # X past its final copies, its rows not gone yet
        if user_id == x:
            outcome["thread"] = threading.Thread(target=delete_y)
            outcome["thread"].start()
            assert y_copying.wait(10)
            outcome["y waited"] = not y_checked.wait(0.5)  # Y may not check its list while X is here
        return real_disconnect(user_id)

    monkeypatch.setattr(workspaces, "keep_final_copies", copies)
    monkeypatch.setattr(cloud_auth, "grant_of", grant)
    monkeypatch.setattr(zotero_account, "disconnect", disconnect)
    assert workspaces.delete_account(x, by=by) == [x_home]
    outcome["thread"].join(10)
    assert outcome["y waited"]
    assert isinstance(outcome["y"], ValueError) and "changed" in str(outcome["y"])
    assert workspaces.get(lab) and account_of("dj_coy") and _copies(y_home) == []
    assert sorted(workspaces.delete_account(y, by=by)) == sorted([y_home, lab])
    assert _copies(lab)


def test_a_queued_delete_checks_again_who_may_delete(boss, monkeypatch):
    _home, c = _account("dj_queued")
    make_user("dj_coowner", PW)
    lab = boss.post("/api/workspaces", json={"name": "Queued lab", "kind": "shared", "owner": "dj_queued"}).json()["id"]
    assert boss.put(f"/api/workspaces/{lab}/members/dj_coowner", json={"role": "owner"}).status_code == 200
    monkeypatch.setattr(jobs, "MAX_RUNNING_PER_ACCOUNT", 0)  # it waits its turn
    started = _start(c, "workspace-delete", {"ws": lab})
    assert started["state"] == "queued"
    assert boss.put(f"/api/workspaces/{lab}/members/dj_queued", json={"role": "editor"}).status_code == 200
    monkeypatch.setattr(jobs, "MAX_RUNNING_PER_ACCOUNT", 2)
    with jobs._lock:
        jobs._pump()
    job = jobs.wait(started["id"])
    assert job["state"] == "failed" and job["error"] == "only a workspace owner can delete it"
    assert workspaces.get(lab) and _copies(lab) == []


def test_an_account_delete_job_is_refused_what_the_admin_api_refuses(boss):
    _home, plain = _account("dj_nonadmin")
    assert plain.post("/api/jobs/account-delete", json={"username": "dj_boss"}).status_code == 403
    assert boss.post("/api/jobs/account-delete", json={"username": "dj_boss"}).status_code == 400
    assert boss.post("/api/jobs/account-delete", json={"username": "nobody-here"}).status_code == 404


def test_the_database_check_as_a_job(boss):
    _home, plain = _account("dj_dbplain")
    assert plain.post("/api/jobs/db-check", json={}).status_code == 403
    job = jobs.wait(_start(boss, "db-check", {})["id"])
    assert job["state"] == "done", job["error"]
    assert job["result"]["ok"] is True and any(f["file"] == "users.db" for f in job["result"]["files"])
    assert job["progress"]["unit"] == "files" and job["progress"]["total"] >= len(job["result"]["files"])


def test_the_check_leaves_out_a_workspace_being_deleted(boss, monkeypatch):
    from gamma import integrity

    ws = make_user("dj_going", PW)
    monkeypatch.setattr(workspaces, "_deleting", {ws})
    assert not [f for f in integrity.check_all()["files"] if f["workspace"] == ws]


def test_a_server_snapshot_downloads_as_a_stream(boss):
    b = boss.post("/api/admin/backups", json={"label": "dj-stream", "uploads": False}).json()
    r = boss.get(f"/api/admin/backups/{b['name']}/download")
    assert r.status_code == 200 and r.headers["content-type"] == "application/zip"
    assert f'filename="gamma-backup-{b["name"]}.zip"' in r.headers["content-disposition"]
    z = zipfile.ZipFile(io.BytesIO(r.content))
    assert "manifest.json" in z.namelist() and "users.db" in z.namelist() and z.testzip() is None
    assert boss.get("/api/admin/backups/20990101-000000-nope/download").status_code == 404
    boss.delete(f"/api/admin/backups/{b['name']}")
