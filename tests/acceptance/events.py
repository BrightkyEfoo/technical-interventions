"""Exercise broker outage, recovery and redelivery on the local Compose demo."""
import base64
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request

from workflow import actor_id, auth, key, require_isolated, send

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["docker", "compose", "--profile", "events"]
VARIANT = os.environ.get("SERVICEOPS_VARIANT", "java")
MANAGEMENT = f"http://127.0.0.1:{os.environ.get('SERVICEOPS_RABBITMQ_MANAGEMENT_PORT', '15672')}/api/"


def compose(*args, **kwargs):
    return subprocess.run([*COMPOSE, *args], cwd=ROOT, check=True, **kwargs)


def eventually(check, predicate, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if predicate(value):
            return value
        time.sleep(0.5)
    raise AssertionError("Condition did not converge")


def rabbit_headers():
    model = json.loads(compose("config", "--format", "json", capture_output=True, text=True).stdout)
    rabbit = model["services"]["rabbitmq"]["environment"]
    token = base64.b64encode((rabbit["RABBITMQ_DEFAULT_USER"] + ":" + rabbit["RABBITMQ_DEFAULT_PASS"]).encode()).decode()
    return {"Authorization": "Basic " + token, "Content-Type": "application/json"}


def rabbit_api(route, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(MANAGEMENT + route, headers=rabbit_headers(), data=data)
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.load(response)


def main():
    creator, manager, tech = (auth(name) for name in ("requester", "manager", "technician"))
    resource = send("/requests", creator, 201, "POST", {"title": "Panne broker " + key()}, key())
    resource_id = resource["id"]
    route = "/requests/" + resource_id
    recipient = actor_id("technician")

    def linked():
        return [item for item in send("/notifications", tech, 200) if item["requestId"] == resource_id]

    compose("stop", "rabbitmq")
    try:
        send(route + "/assign", manager, 204, "POST", {"technicianId": recipient, "expectedVersion": resource["version"]})
        current = send(route, tech, 200)
        assert current["status"] == "ASSIGNED"
        assert linked() == []
    finally:
        compose("up", "-d", "--wait", "--wait-timeout", "60", "rabbitmq")
    notifications = eventually(linked, lambda rows: len(rows) == 1)
    first = notifications[0]
    event = {
        "eventId": first["id"], "type": "request.assigned", "schemaVersion": 1, "requestId": resource_id,
        "requestVersion": current["version"], "recipientId": recipient, "correlationId": resource_id,
        "occurredAt": first["createdAt"],
    }
    exchange = f"serviceops.{VARIANT}.events"
    queue = f"serviceops.{VARIANT}.assignments"
    dead = f"serviceops.{VARIANT}.diagnostics"
    publish = f"exchanges/%2F/{urllib.parse.quote(exchange)}/publish"

    def publish_message(payload):
        result = rabbit_api(publish, {"properties": {"content_type": "text/plain"}, "routing_key": "request.assigned",
                                      "payload_encoding": "string", "payload": json.dumps(payload)})
        assert result["routed"], result

    publish_message(event)
    before_dead = rabbit_api(f"queues/%2F/{dead}").get("messages", 0)
    publish_message({"type": "unsupported"})
    eventually(lambda: rabbit_api(f"queues/%2F/{dead}").get("messages", 0), lambda count: count > before_dead)
    eventually(lambda: rabbit_api(f"queues/%2F/{queue}").get("messages", 0), lambda count: count == 0)
    assert linked() == [first]
    print(f"{VARIANT}: affectation persistante pendant la panne, reprise, redélivrance idempotente et file de diagnostic", flush=True)


if __name__ == "__main__":
    require_isolated()
    main()
