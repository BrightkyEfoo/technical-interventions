"""Run the integration suites against a disposable Compose project."""
import os
import secrets
import socket
import subprocess
import sys
import time
import urllib.request
from contextlib import ExitStack
from pathlib import Path
from tempfile import TemporaryDirectory

from local import ROOT, prepare_databases, validate_account_seed

PORT_VARIABLES = (
    "POSTGRES_PORT", "SERVICEOPS_KEYCLOAK_PORT", "SERVICEOPS_RABBITMQ_PORT",
    "SERVICEOPS_RABBITMQ_MANAGEMENT_PORT", "SERVICEOPS_NOTIFICATIONS_DB_PORT",
    "SERVICEOPS_JAVA_PORT", "SERVICEOPS_KOTLIN_PORT", "SERVICEOPS_WEB_PORT", "SERVICEOPS_MOBILE_WEB_PORT",
)


def allocate_ports():
    with ExitStack() as stack:
        ports = {}
        for variable in PORT_VARIABLES:
            listener = stack.enter_context(socket.socket())
            listener.bind(("127.0.0.1", 0))
            ports[variable] = str(listener.getsockname()[1])
        return ports


def wait_for_endpoint(endpoint):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(endpoint, timeout=5):
                return
        except OSError:
            time.sleep(1)
    raise RuntimeError(f"Le service de test ne répond pas sur {endpoint}.")


def main():
    validate_account_seed()
    project = f"serviceops-tests-{secrets.token_hex(5)}"
    settings = {
        **allocate_ports(),
        "COMPOSE_PROJECT_NAME": project,
        "POSTGRES_DB": "serviceops",
        "POSTGRES_USER": "serviceops",
        "POSTGRES_PASSWORD": secrets.token_urlsafe(24),
        "NOTIFICATIONS_DB_PASSWORD": secrets.token_urlsafe(24),
        "KEYCLOAK_SUPER_ADMIN_PASSWORD": secrets.token_urlsafe(24),
        "SERVICEOPS_ACCOUNTS_CLIENT_SECRET": secrets.token_urlsafe(24),
        "SERVICEOPS_INTERNAL_KEY": secrets.token_urlsafe(24),
        "SERVICEOPS_DEMO_PASSWORD": "serviceops-demo",
        "SERVICEOPS_TEST_GRANTS": "true",
        "SERVICEOPS_NOTIFICATIONS": "1",
    }
    settings["SERVICEOPS_ISSUER"] = f"http://localhost:{settings['SERVICEOPS_KEYCLOAK_PORT']}/realms/serviceops"
    with TemporaryDirectory(prefix=project + "-") as temp:
        env_file = Path(temp) / ".env"
        env_file.touch(mode=0o600)
        settings["COMPOSE_ENV_FILES"] = str(env_file)
        settings["SERVICEOPS_TEST_MFA_STATE"] = str(Path(temp) / "mfa.json")
        environment = dict(os.environ, **settings)
        command = ["docker", "compose", "-p", project, "--env-file", str(env_file),
                   "-f", "compose.yaml", "-f", "compose.delivery.yaml",
                   "--profile", "auth", "--profile", "events", "--profile", "delivery-java",
                   "--profile", "delivery-kotlin", "--profile", "clients"]

        def compose(*args, **kwargs):
            return subprocess.run([*command, *args], cwd=ROOT, env=environment, check=True, **kwargs)

        def acceptance(script, variant, *args):
            port = settings[f"SERVICEOPS_{variant.upper()}_PORT"]
            test_env = dict(environment, SERVICEOPS_API=f"http://127.0.0.1:{port}/api/v1", SERVICEOPS_VARIANT=variant)
            subprocess.run([sys.executable, "-B", f"tests/acceptance/{script}.py", *args], cwd=ROOT, env=test_env, check=True)

        subprocess.run(["infra/images/build.sh"], cwd=ROOT, env=environment, check=True)
        try:
            compose("up", "-d", "--wait", "--wait-timeout", "120", "postgres", "notifications-db", "rabbitmq", "keycloak")
            prepare_databases(compose)
            compose("up", "-d", "--wait", "--wait-timeout", "180")
            issuer_endpoint = settings["SERVICEOPS_ISSUER"] + "/.well-known/openid-configuration"
            wait_for_endpoint(issuer_endpoint)
            for variant in ("JAVA", "KOTLIN"):
                port = settings[f"SERVICEOPS_{variant}_PORT"]
                wait_for_endpoint(f"http://127.0.0.1:{port}/actuator/health")
            for variant in ("java", "kotlin"):
                acceptance("accounts", variant, "--seed-only")
            compose("restart", "keycloak")
            wait_for_endpoint(issuer_endpoint)
            for variant in ("java", "kotlin"):
                acceptance("accounts", variant, "--seed-only")
            for variant in ("java", "kotlin"):
                for script in ("accounts", "workflow", "events"):
                    acceptance(script, variant)
            subprocess.run(["npm", "run", "test:e2e"], cwd=ROOT, env=environment, check=True)
        except (RuntimeError, subprocess.CalledProcessError):
            compose("logs", "--tail", "35", "keycloak", "rabbitmq", "operations-java", "operations-kotlin")
            raise
        finally:
            compose("down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    main()
