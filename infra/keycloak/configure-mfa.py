"""Install the ServiceOps browser MFA flow on an existing Keycloak realm."""
import argparse
import getpass
import importlib.util
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlparse

spec = importlib.util.spec_from_file_location("accounts_setup", Path(__file__).with_name("configure-accounts.py"))
accounts_setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accounts_setup)
BROWSER = "serviceops-browser-mfa"
FORMS = "serviceops-mfa-forms"
LOGIN_THEME = "serviceops"


def executions(admin, flow):
    return admin.request("GET", f"/authentication/flows/{quote(flow)}/executions")


def ensure_execution(admin, flow, provider, requirement, *, subflow=False):
    route = f"/authentication/flows/{quote(flow)}/executions"
    identity = "displayName" if subflow else "providerId"
    found = next((step for step in executions(admin, flow)
                  if step.get(identity) == provider and step.get("level") == 0), None)
    if not found:
        if subflow:
            admin.request("POST", route + "/flow", {
                "alias": provider, "description": provider, "type": "basic-flow", "provider": "basic-flow",
            })
        else:
            admin.request("POST", route + "/execution", {"provider": provider})
        found = next(step for step in executions(admin, flow)
                     if step.get(identity) == provider and step.get("level") == 0)
    if found["requirement"] != requirement:
        admin.request("PUT", route, {"id": found["id"], "requirement": requirement})


def configure(admin):
    current = admin.request("GET")
    flows = admin.request("GET", "/authentication/flows")
    if not any(flow["alias"] == BROWSER for flow in flows):
        admin.request("POST", "/authentication/flows", {
            "alias": BROWSER, "providerId": "basic-flow", "topLevel": True, "builtIn": False,
        })
    ensure_execution(admin, BROWSER, "auth-cookie", "ALTERNATIVE")
    ensure_execution(admin, BROWSER, FORMS, "ALTERNATIVE", subflow=True)
    ensure_execution(admin, FORMS, "auth-username-password-form", "REQUIRED")
    ensure_execution(admin, FORMS, "auth-otp-form", "REQUIRED")
    otp = admin.request("GET", "/authentication/required-actions/CONFIGURE_TOTP")
    admin.request("PUT", "/authentication/required-actions/CONFIGURE_TOTP", otp | {
        "enabled": True, "defaultAction": True,
    })
    test_client = admin.client("serviceops-test")
    if test_client and test_client.get("enabled"):
        admin.request("PUT", "/clients/" + test_client["id"], {
            "enabled": False, "directAccessGrantsEnabled": False,
        })
    flow_changed = current.get("browserFlow") != BROWSER
    if flow_changed or current.get("loginTheme") != LOGIN_THEME:
        admin.request("PUT", body={"browserFlow": BROWSER, "loginTheme": LOGIN_THEME})
    if flow_changed:
        admin.request("POST", "/logout-all")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8180")
    parser.add_argument("--admin", required=True)
    args = parser.parse_args()
    password = os.environ.get("KEYCLOAK_ADMIN_PASSWORD") or getpass.getpass("Mot de passe administrateur Keycloak : ")
    configure(accounts_setup.RealmAdmin(args.url, args.admin, password))
    print("Flux TOTP obligatoire, thème d’enrôlement actif et client de test désactivé.")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit(f"Échec HTTP {error.code} sur {urlparse(error.url).path}.") from None
