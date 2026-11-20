"""Shared HTTP acceptance suite for either ServiceOps backend."""
import base64
import concurrent.futures
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get("SERVICEOPS_API", "http://localhost:8080/api/v1")
ISSUER = os.environ.get("SERVICEOPS_ISSUER", "http://localhost:8180/realms/serviceops")
DEMO = os.environ.get("SERVICEOPS_DEMO") == "1"
ACCOUNTS = {"manager": 1, "technician": 2, "requester": 3, "technician2": 4}


def require_isolated():
    if (not os.environ.get("COMPOSE_PROJECT_NAME", "").startswith("serviceops-tests-")
            or urllib.parse.urlparse(BASE).port in (None, 8080, 8081)
            or urllib.parse.urlparse(ISSUER).port in (None, 8180)):
        raise SystemExit("Lance python3 infra/test-local.py pour tester sur des bases isolées.")


def actor_id(name):
    return f"00000000-0000-4000-8000-{ACCOUNTS[name]:012d}"


def auth(name):
    if DEMO:
        return {"X-Demo-Actor": actor_id(name)}
    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "serviceops-test", "username": name,
        "password": os.environ.get("SERVICEOPS_DEMO_PASSWORD", "serviceops-demo"),
    }).encode()
    with urllib.request.urlopen(ISSUER + "/protocol/openid-connect/token", body, timeout=10) as response:
        return {"Authorization": "Bearer " + json.load(response)["access_token"]}


def send(path, actor, status, method="GET", body=None, key=None, content_type="application/json"):
    data = None if body is None else body if isinstance(body, bytes) else json.dumps(body).encode()
    headers = dict(actor)
    if data is not None:
        headers["Content-Type"] = content_type
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            code, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        code, raw = error.code, error.read()
    assert code == status, (method, path, code, status, raw[:400])
    return json.loads(raw) if raw else None


def key():
    return str(uuid.uuid4())


def multipart(expected_version, content):
    boundary = key()
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"expectedVersion\"\r\n\r\n{expected_version}\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"probe.png\"\r\n"
        "Content-Type: image/png\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def main():
    if not DEMO:
        send("/requests", {}, 401)
        send("/requests", {"Authorization": "Bearer invalid"}, 401)
    requester, manager, tech, other = (auth(name) for name in ("requester", "manager", "technician", "technician2"))
    title = "Porte de test " + key()
    creation_key = key()
    assert send("/requests", requester, 400, "POST", {"title": "abcd"}, key())["code"] == "INVALID_TITLE"
    data = send("/requests", requester, 201, "POST", {"title": f"  {title}  ", "priority": "HIGH"}, creation_key)
    assert data["title"] == title and data["status"] == "OPEN" and data["assigneeId"] is None, data
    assert data == send("/requests", requester, 201, "POST", {"title": title, "priority": "HIGH"}, creation_key)
    assert send("/requests", requester, 409, "POST", {"title": "Autre porte"}, creation_key)["code"] == "IDEMPOTENCY_CONFLICT"
    request = "/requests/" + data["id"]
    send(request, tech, 404)
    send(request, other, 404)
    send(request + "/assign", requester, 403, "POST", {"technicianId": actor_id("technician"), "expectedVersion": data["version"]})
    before = send(request, manager, 200)
    assert before["version"] == data["version"]
    barrier = threading.Barrier(2)
    assignment = {"technicianId": actor_id("technician"), "expectedVersion": data["version"]}

    def compete():
        barrier.wait(timeout=3)
        try:
            send(request + "/assign", manager, 204, "POST", assignment)
            return True
        except AssertionError as failure:
            assert failure.args[0][2] == 409, failure
            return False

    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        winners = list(pool.map(lambda _: compete(), range(2)))
    assert sorted(winners) == [False, True], winners
    assigned = send(request, tech, 200)
    assert assigned["status"] == "ASSIGNED" and assigned["assigneeId"] == actor_id("technician")
    send(request, other, 404)
    filtered = send("/requests?" + urllib.parse.urlencode({"query": title, "status": "ASSIGNED", "size": 1}), tech, 200)
    assert filtered["total"] == 1 and filtered["items"][0] == assigned, filtered
    send(request + "/start", tech, 409, "POST", {"expectedVersion": data["version"]})
    started = send(request + "/start", tech, 200, "POST", {"expectedVersion": assigned["version"]})
    assert started["status"] == "IN_PROGRESS"
    invalid, invalid_type = multipart(started["version"], b"not-an-image")
    send(request + "/attachments", tech, 400, "POST", invalid, content_type=invalid_type)
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    photo, photo_type = multipart(started["version"], png)
    send(request + "/attachments", tech, 201, "POST", photo, content_type=photo_type)
    with_photo = send(request, tech, 200)
    send(request + "/resolve", tech, 409, "POST", {"expectedVersion": started["version"], "report": "Intervention terminée."}, key())
    send(request + "/resolve", tech, 400, "POST", {"expectedVersion": with_photo["version"], "report": "court"}, key())
    report = {"expectedVersion": with_photo["version"], "report": "Intervention terminée avec succès."}
    resolution_key = key()
    resolved = send(request + "/resolve", tech, 200, "POST", report, resolution_key)
    assert resolved["status"] == "RESOLVED" and resolved["report"] == report["report"]
    assert resolved == send(request + "/resolve", tech, 200, "POST", report, resolution_key)
    send(request + "/resolve", tech, 409, "POST", {**report, "report": "Un autre compte rendu."}, resolution_key)
    comment = send(request + "/comments", requester, 201, "POST", {"expectedVersion": resolved["version"], "body": "Merci."})
    assert send(request + "/comments", requester, 200)[-1]["id"] == comment["id"]
    latest = send(request, requester, 200)
    closed = send(request + "/close", requester, 200, "POST", {"expectedVersion": latest["version"]})
    assert closed["status"] == "CLOSED"
    send(request + "/comments", requester, 409, "POST", {"expectedVersion": closed["version"], "body": "Trop tard."})
    history = send(request + "/history", requester, 200)
    assert [entry["action"] for entry in history] == ["CREATE", "ASSIGN", "START", "ATTACH", "RESOLVE", "COMMENT", "CLOSE"], history
    if os.environ.get("SERVICEOPS_NOTIFICATIONS") == "1":
        for _ in range(24):
            notifications = send("/notifications", tech, 200)
            found = [item for item in notifications if item["requestId"] == data["id"]]
            if found:
                assert len(found) == 1, found
                break
            time.sleep(0.5)
        else:
            raise AssertionError("No notification received")
    print(f"Parcours accepté sur {BASE}. UUID persistant {data['id']}", flush=True)


if __name__ == "__main__":
    require_isolated()
    main()
