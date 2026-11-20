"""Démarrage de la démonstration locale. Le secret reste dans .env."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "--profile", "auth", "--profile", "events"]
PORTS = {"java": (8080, 8091), "kotlin": (8081, 8092)}


def compose(*args, **kwargs):
    return subprocess.run([*COMPOSE, *args], cwd=ROOT, check=True, **kwargs)


def ensure_account_secrets():
    path = ROOT / ".env"
    contents = path.read_text() if path.exists() else ""
    updated = contents
    for name in ("SERVICEOPS_ACCOUNTS_CLIENT_SECRET", "KEYCLOAK_SUPER_ADMIN_PASSWORD"):
        if os.environ.get(name) or re.search(rf"^{name}=\S+", contents, re.MULTILINE):
            continue
        updated = re.sub(rf"^{name}=\s*?$", "", updated, flags=re.MULTILINE)
        updated = updated.rstrip() + f"\n{name}={secrets.token_urlsafe(32)}\n"
    if updated != contents:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as target:
            target.write(updated.lstrip("\n"))


def prepare():
    ensure_account_secrets()
    validate_account_seed()
    existing = compose("ps", "-a", "-q", "keycloak", capture_output=True, text=True).stdout.strip()
    if existing:
        mounts = json.loads(subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .Mounts}}", existing], text=True
        ))
        if not any(mount["Destination"] == "/opt/keycloak/data" for mount in mounts):
            raise SystemExit("Sauvegarde et migre les données Keycloak vers le volume keycloak_data avant de relancer Compose. Voir docs/EXPLOITATION.md.")
    compose("up", "-d", "--wait", "--wait-timeout", "120")
    prepare_databases(compose)


def validate_account_seed():
    users = json.loads((ROOT / "infra/keycloak/serviceops-realm.json").read_text())["users"]
    for field in ("username", "email", "id"):
        values = [user[field].casefold() for user in users if user.get(field)]
        if len(set(values)) != len(values):
            raise SystemExit(f"Le seed Keycloak contient un doublon dans le champ {field}.")


def prepare_databases(compose):
    for service in ("postgres", "notifications-db"):
        statement = "SELECT format('CREATE DATABASE %I', :'target') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'target')"
        compose("exec", "-T", service, "sh", "-ec",
                'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v target="${POSTGRES_DB}_kotlin" -v ON_ERROR_STOP=1',
                input=statement + "\n\\gexec\n", text=True)


def run(variant, service, demo, port_override):
    config = json.loads(compose("config", "--format", "json", capture_output=True, text=True).stdout)["services"]
    notifications = service == "notifications"
    pg = config["notifications-db" if notifications else "postgres"]
    env = pg["environment"]
    port = pg["ports"][0]["published"]
    rabbit = config["rabbitmq"]["environment"]
    db = env["POSTGRES_DB"] + ("_kotlin" if variant == "kotlin" else "")
    key = hashlib.sha256(("serviceops-local-internal:" + rabbit["RABBITMQ_DEFAULT_PASS"]).encode()).hexdigest()
    operation_port, notification_port = PORTS[variant]
    process_env = dict(
        os.environ, SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port_override or (notification_port if notifications else operation_port)),
        SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{port}/{db}",
        SPRING_DATASOURCE_USERNAME=env["POSTGRES_USER"], SPRING_DATASOURCE_PASSWORD=env["POSTGRES_PASSWORD"],
        RABBITMQ_USER=rabbit["RABBITMQ_DEFAULT_USER"], RABBITMQ_PASSWORD=rabbit["RABBITMQ_DEFAULT_PASS"],
        SERVICEOPS_INTERNAL_KEY=key, SERVICEOPS_NOTIFICATIONS_URL=f"http://127.0.0.1:{notification_port}",
    )
    if not notifications:
        process_env["SPRING_PROFILES_ACTIVE"] = "postgres,events" + (",demo" if demo else "")
        process_env["SERVICEOPS_ACCOUNTS_CLIENT_SECRET"] = config["keycloak"]["environment"]["SERVICEOPS_ACCOUNTS_CLIENT_SECRET"]
    name = f"serviceops-notifications-{variant}" if notifications else f"serviceops-{variant}"
    jar = ROOT / f"backends/{variant}/{name}/target/{name}-0.0.1-SNAPSHOT.jar"
    if not jar.is_file():
        raise SystemExit("Construis les modules avec ./mvnw package.")
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        java = str(Path(java_home) / "bin/java")
    else:
        java = shutil.which("java") or "java"
    with tempfile.TemporaryDirectory(prefix=f"serviceops-{variant}-{service}-") as workspace:
        snapshot = shutil.copy2(jar, Path(workspace) / jar.name)
        child = subprocess.Popen([java, "-jar", snapshot], env=process_env)
        previous = signal.signal(signal.SIGTERM, lambda signum, frame: child.send_signal(signum))
        try:
            raise SystemExit(child.wait())
        finally:
            signal.signal(signal.SIGTERM, previous)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("prepare")
    launch = sub.add_parser("run")
    launch.add_argument("variant", choices=PORTS)
    launch.add_argument("service", choices=["operations", "notifications"])
    launch.add_argument("--demo", action="store_true", help="Permet X-Demo-Actor pour les tests locaux")
    launch.add_argument("--port", type=int, help="Port hôte pour un second lancement local")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare()
    else:
        run(args.variant, args.service, args.demo, args.port)


if __name__ == "__main__":
    main()
