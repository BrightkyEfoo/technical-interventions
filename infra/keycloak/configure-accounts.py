"""Apply the account features to an existing ServiceOps realm without reimporting it."""
import argparse
import getpass
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROLE_URL = "/roles"
DEFAULT_ROLE = "default-roles-serviceops"


def required(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"La variable {name} est requise.")
    return value


class RealmAdmin:
    def __init__(self, base, admin, password):
        self.base = base.rstrip("/")
        self.realm = "/admin/realms/serviceops"
        form = urllib.parse.urlencode({
            "grant_type": "password", "client_id": "admin-cli",
            "username": admin, "password": password,
        }).encode()
        req = urllib.request.Request(self.base + "/realms/master/protocol/openid-connect/token", form)
        with urllib.request.urlopen(req, timeout=20) as response:
            self.token = json.load(response)["access_token"]

    def request(self, method, path="", body=None):
        headers = {"Authorization": "Bearer " + self.token}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + self.realm + path, data, headers, method=method)
        with urllib.request.urlopen(req, timeout=20) as response:
            result = response.read()
            return json.loads(result) if result else None

    def role(self, name):
        return self.request("GET", ROLE_URL + "/" + name)

    def client(self, name):
        found = self.request("GET", "/clients?" + urllib.parse.urlencode({"clientId": name}))
        return next((client for client in found if client["clientId"] == name), None)

    def user(self, name):
        found = self.request("GET", "/users?" + urllib.parse.urlencode({"exact": "true", "username": name}))
        return next((user for user in found if user["username"] == name), None)


def configure(admin, service_secret, super_password):
    model = json.loads(Path(__file__).with_name("serviceops-realm.json").read_text())
    realm = admin.request("GET")
    if realm.get("defaultRole", {}).get("name") != DEFAULT_ROLE:
        raise SystemExit("Le rôle par défaut de ce realm diffère de celui de ServiceOps.")
    root_user = next(user for user in model["users"] if user["username"] == "superadmin")
    existing = admin.user(root_user["username"])
    if existing:
        mapped = admin.request("GET", f"/users/{existing['id']}/role-mappings/realm")
        if "SUPER_ADMIN" not in {role["name"] for role in mapped}:
            raise SystemExit("L’identifiant superadmin est déjà utilisé par un autre compte.")

    try:
        admin.role("SUPER_ADMIN")
    except urllib.error.HTTPError as failure:
        if failure.code != 404:
            raise
        admin.request("POST", ROLE_URL, {"name": "SUPER_ADMIN"})

    definition = next(client for client in model["clients"] if client["clientId"] == "serviceops-accounts")
    definition["secret"] = service_secret
    current = admin.client(definition["clientId"])
    if current is None:
        admin.request("POST", "/clients", definition)
        current = admin.client(definition["clientId"])
    else:
        admin.request("PUT", "/clients/" + current["id"], definition)
    account = admin.request("GET", "/clients/" + current["id"] + "/service-account-user")
    management = admin.client("realm-management")["id"]
    roles = [admin.request("GET", f"/clients/{management}/roles/{name}") for name in ("manage-users", "view-realm")]
    admin.request("POST", f"/users/{account['id']}/role-mappings/clients/{management}", roles)

    if not existing:
        admin.request("POST", "/users", {
            "username": root_user["username"], "enabled": False,
            "firstName": root_user["firstName"], "lastName": root_user["lastName"], "email": root_user["email"],
            "emailVerified": True, "credentials": [{"type": "password", "value": super_password, "temporary": False}],
        })
    super_id = admin.user(root_user["username"])["id"]
    mapping = f"/users/{super_id}/role-mappings/realm"
    admin.request("POST", mapping, [admin.role("SUPER_ADMIN")])
    admin.request("DELETE", mapping, [admin.role(DEFAULT_ROLE)])
    if not existing:
        admin.request("PUT", f"/users/{super_id}", {"enabled": True})

    configured = admin.request("GET", f"/roles/{DEFAULT_ROLE}/composites")
    if "REQUESTER" not in {role["name"] for role in configured}:
        admin.request("POST", f"/roles/{DEFAULT_ROLE}/composites", [admin.role("REQUESTER")])
    admin.request("PUT", body={key: model[key] for key in
        ("registrationAllowed", "duplicateEmailsAllowed", "bruteForceProtected", "passwordPolicy")})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8180")
    parser.add_argument("--admin", required=True)
    args = parser.parse_args()
    password = os.environ.get("KEYCLOAK_ADMIN_PASSWORD") or getpass.getpass("Mot de passe Keycloak : ")
    admin = RealmAdmin(args.url, args.admin, password)
    configure(admin, required("SERVICEOPS_ACCOUNTS_CLIENT_SECRET"), required("KEYCLOAK_SUPER_ADMIN_PASSWORD"))
    print("Realm ServiceOps mis à jour")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Keycloak a refusé l’opération HTTP {error.code} sur {urllib.parse.urlparse(error.url).path}.") from None
