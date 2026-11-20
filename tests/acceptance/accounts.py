"""Authorization, staff provisioning and assignment through the shared Keycloak realm."""
import concurrent.futures
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

from workflow import ISSUER, actor_id, auth, key, require_isolated, send


def admin_headers():
    name = "KEYCLOAK_SUPER_ADMIN_PASSWORD"
    password = os.environ.get(name)
    if not password:
        env_file = Path(__file__).resolve().parents[2] / ".env"
        password = next(
            (line.split("=", 1)[1] for line in env_file.read_text().splitlines() if line.startswith(name + "=")),
            "",
        )
    assert password, "Le mot de passe de bootstrap du super admin est requis"
    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "serviceops-test",
        "username": "superadmin", "password": password,
    }).encode()
    with urllib.request.urlopen(ISSUER + "/protocol/openid-connect/token", body, timeout=10) as response:
        return {"Authorization": "Bearer " + json.load(response)["access_token"]}


def staff(role):
    username = f"{role.lower()}-{key()[:10]}"
    return {
        "role": role, "username": username, "email": f"{username}@serviceops.com",
        "firstName": "Compte", "lastName": "Atelier", "temporaryPassword": key(),
    }


def check_seed():
    expected = {
        "manager": (actor_id("manager"), "MANAGER"),
        "technician": (actor_id("technician"), "TECHNICIAN"),
        "requester": (actor_id("requester"), "REQUESTER"),
        "technician2": (actor_id("technician2"), "TECHNICIAN"),
        "superadmin": ("00000000-0000-4000-8000-000000000005", "SUPER_ADMIN"),
    }
    admin = admin_headers()
    page = send("/admin/accounts", admin, 200)
    assert not page["hasNext"]
    assert len(page["items"]) == len(expected)
    assert {user["username"]: (user["id"], user["role"]) for user in page["items"]} == expected
    assert len({user["email"].casefold() for user in page["items"]}) == len(expected)
    assert send("/requests", admin, 200)["total"] == 0
    print("Seed vérifié · cinq comptes stables et zéro intervention")


def main():
    path = "/admin/accounts"
    admin = admin_headers()
    manager, technician, requester = map(auth, ("manager", "technician", "requester"))
    send(path, {}, 401, "POST", staff("MANAGER"))
    send(path, {}, 401)
    for actor in (manager, technician, requester):
        assert send(path, actor, 403, "POST", staff("MANAGER"))["code"] == "REQUEST_FORBIDDEN"
        assert send(path, actor, 403)["code"] == "REQUEST_FORBIDDEN"
    send(path, manager | {"X-Demo-Actor": "00000000-0000-4000-8000-000000000005"}, 403, "POST", staff("MANAGER"))
    send("/requests", admin, 200)
    send("/requests", admin, 403, "POST", {"title": "Intervention interdite"}, key())
    send("/technicians", requester, 403)
    for query in ("page=-1", "size=0", "size=51"):
        assert send(path + "?" + query, admin, 400)["code"] == "INVALID_INPUT"
    first = send(path + "?size=2", admin, 200)
    second = send(path + "?size=2&page=1", admin, 200)
    assert (first["page"], first["size"], first["hasNext"], len(first["items"])) == (0, 2, True, 2)
    assert (second["page"], len(second["items"])) == (1, 2)
    assert {account["id"] for account in first["items"]}.isdisjoint(account["id"] for account in second["items"])
    roles = send(path + "?query=quester", admin, 200)["items"]
    assert any(account["username"] == "requester" and account["role"] == "REQUESTER" for account in roles)
    assert any(account["role"] == "SUPER_ADMIN" for account in send(path + "?query=superadmin", admin, 200)["items"])
    assert send(path + "?query=service-account-serviceops-accounts", admin, 200)["items"] == []
    for role in ("SUPER_ADMIN", "REQUESTER"):
        assert send(path, admin, 400, "POST", staff(role))["code"] == "INVALID_INPUT"
    assert send(path, admin, 400, "POST", staff("MANAGER") | {"temporaryPassword": "court"})["code"] == "INVALID_INPUT"

    manager_input = staff("MANAGER")
    new_manager = send(path, admin, 201, "POST", manager_input)
    assert set(new_manager) == {"id", "role", "username", "email"} and new_manager["role"] == "MANAGER", new_manager
    assert send(path, admin, 409, "POST", manager_input)["code"] == "ACCOUNT_CONFLICT"
    reused_email = staff("TECHNICIAN") | {"email": new_manager["email"]}
    assert send(path, admin, 409, "POST", reused_email)["code"] == "ACCOUNT_CONFLICT"
    assert send(path, admin, 409, "POST", staff("MANAGER") | {"username": new_manager["username"].upper()})["code"] == "ACCOUNT_CONFLICT"

    shared_email = staff("MANAGER")["email"]
    concurrent_inputs = [staff("MANAGER") | {"email": shared_email}, staff("TECHNICIAN") | {"email": shared_email.upper()}]

    def create_once(account):
        try:
            send(path, admin, 201, "POST", account)
            return 201
        except AssertionError as conflict:
            if not isinstance(conflict.args[0], tuple) or conflict.args[0][2] != 409:
                raise
            return 409

    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        assert sorted(pool.map(create_once, concurrent_inputs)) == [201, 409]
    assert len(send(path + "?" + urllib.parse.urlencode({"query": shared_email}), admin, 200)["items"]) == 1

    technician_input = staff("TECHNICIAN")
    new_tech = send(path, admin, 201, "POST", technician_input)
    assert set(new_tech) == {"id", "role", "username", "email"} and new_tech["role"] == "TECHNICIAN", new_tech
    directory = send("/technicians", manager, 200)
    assert {"id": new_tech["id"], "name": new_tech["username"]} in directory
    assert all(entry["id"] != new_manager["id"] for entry in directory)
    for created, account in ((new_manager, manager_input), (new_tech, technician_input)):
        query = urllib.parse.urlencode({"query": created["email"]})
        assert send(path + "?" + query, admin, 200)["items"] == [
            created | {"firstName": account["firstName"], "lastName": account["lastName"], "enabled": True}
        ]

    item = send("/requests", requester, 201, "POST", {"title": "Affectation nouveau technicien " + key()}, key())
    request = "/requests/" + item["id"]
    matching = send("/requests?" + urllib.parse.urlencode({"query": item["title"]}), admin, 200)
    assert matching["total"] == 1 and matching["items"][0]["id"] == item["id"]
    assert send(request, admin, 200)["id"] == item["id"]
    assert send(request + "/history", admin, 200)[0]["action"] == "CREATE"
    assert send(request + "/comments", admin, 200) == []
    send(request + "/comments", admin, 403, "POST", {"body": "Commentaire interdit", "expectedVersion": item["version"]})
    send(request + "/assign", admin, 403, "POST", {"technicianId": new_tech["id"], "expectedVersion": item["version"]})
    send(request + "/assign", manager, 400, "POST", {"technicianId": new_manager["id"], "expectedVersion": item["version"]})
    send(request + "/assign", manager, 204, "POST", {"technicianId": new_tech["id"], "expectedVersion": item["version"]})
    assert send(request, manager, 200)["assigneeId"] == new_tech["id"]
    print("Comptes vérifiés · droits, recherche, pagination, création, doublons et affectation")


if __name__ == "__main__":
    require_isolated()
    if sys.argv[1:] == ["--seed-only"]:
        check_seed()
    else:
        main()
