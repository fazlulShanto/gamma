"""Recovery must invalidate credentials prepared by the previous account holder."""

from contextlib import closing

import pytest
from conftest import last_link, register, verify

from gammacloud import accounts, db, identities, providers
from gammacloud.providers import Identity


@pytest.mark.parametrize("recovery", ["password", "reset", "provider", "revoke-all"])
def test_recovery_cancels_pending_email_change(client, recovery):
    account = register(client, email="victim@example.org")
    assert client.post("/api/email/change", json={
        "new_email": "attacker@example.org", "password": "correct horse battery",
    }).status_code == 200
    pending = last_link("/email/confirm")

    if recovery == "password":
        response = client.post("/api/me/password", json={
            "current": "correct horse battery", "new": "recovered password",
        })
        assert response.status_code == 200
    elif recovery == "reset":
        assert client.post("/api/reset/request", json={"email": "victim@example.org"}).status_code == 200
        response = client.post("/api/reset/confirm", json={
            "token": last_link("/reset/confirm"), "password": "recovered password",
        })
        assert response.status_code == 200
    elif recovery == "provider":
        with closing(db.connect()) as conn:
            claimed = identities.resolve(conn, Identity("google", "victim-sub", "victim@example.org", True))
            assert claimed["id"] == account["id"]
            conn.commit()
    else:
        assert client.post("/api/devices/revoke-all").status_code == 200

    client.cookies.clear()
    response = client.post("/api/email/confirm", json={"token": pending})
    assert response.status_code == 400
    with closing(db.connect()) as conn:
        assert accounts.by_id(conn, account["id"])["email"] == "victim@example.org"


def test_email_change_cancels_reset_sent_to_old_inbox(client):
    register(client)
    verify(client)
    assert client.post("/api/reset/request", json={"email": "alice@example.org"}).status_code == 200
    old_reset = last_link("/reset/confirm")
    assert client.post("/api/email/change", json={
        "new_email": "alice@new.example", "password": "correct horse battery",
    }).status_code == 200
    assert client.post("/api/email/confirm", json={"token": last_link("/email/confirm")}).status_code == 200
    client.cookies.clear()
    assert client.post("/api/reset/confirm", json={
        "token": old_reset, "password": "attacker password",
    }).status_code == 400


def test_unverified_account_cannot_link_another_identity(client, monkeypatch):
    register(client, email="victim@example.org")
    monkeypatch.setattr(providers, "enabled", lambda: ["github"])
    response = client.post("/api/oauth/github/start", json={"link": True})
    assert response.status_code == 403


@pytest.mark.parametrize("recovery", ["reset", "provider"])
def test_recovery_drops_identity_linked_before_address_was_verified(client, recovery):
    account = register(client, email="victim@example.org")
    # Simulate an association made before linking required a verified address.
    with closing(db.connect()) as conn:
        conn.execute("INSERT INTO identities (provider, subject, account_id, email, created_at) "
                     "VALUES (?, ?, ?, ?, ?)",
                     ("github", "attacker-sub", account["id"], "attacker@example.org", db.now()))
        conn.commit()

    if recovery == "reset":
        assert client.post("/api/reset/request", json={"email": "victim@example.org"}).status_code == 200
        assert client.post("/api/reset/confirm", json={
            "token": last_link("/reset/confirm"), "password": "victim password",
        }).status_code == 200
    else:
        with closing(db.connect()) as conn:
            identities.resolve(conn, Identity("google", "victim-sub", "victim@example.org", True))
            conn.commit()

    with closing(db.connect()) as conn:
        attacker = identities.resolve(conn, Identity("github", "attacker-sub", "attacker@example.org", True))
    assert attacker is None
