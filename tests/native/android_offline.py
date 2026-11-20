"""Android emulator proof: open, save offline, cold launch, confirm and check server.

Use a dedicated emulator with the debug app installed and the demo servers
mapped via ADB reverse. The script restores network access in finally.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "acceptance"))
import workflow

DEVICE = os.environ.get("ANDROID_SERIAL", "emulator-5554")
ADB = os.environ.get("ADB", "adb")
DUMP = "/sdcard/serviceops-native-test.xml"
APP = "cm.brightky.serviceops"
ROOT = f"{APP}/.MainActivity"


def adb(*args, check=True):
    return subprocess.run([ADB, "-s", DEVICE, *args], check=check, capture_output=True, text=True, timeout=25)


def ui():
    result = adb("shell", "uiautomator", "dump", DUMP, check=False)
    if "dumped" not in result.stdout:
        return []
    return list(ET.fromstring(adb("shell", "cat", DUMP).stdout).iter("node"))


def find(label, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        for node in ui():
            if label == node.get("text") or label == node.get("content-desc"):
                return node
    raise AssertionError(f"Élément natif introuvable : {label}")


def tap(node):
    x, y, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x + x2) // 2), str((y + y2) // 2))


def click(label):
    tap(find(label))


def launch():
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", ROOT)


def airplane(enabled):
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if enabled else "disable")


def native_test(variant, port):
    workflow.BASE = f"http://127.0.0.1:{port}/api/v1"
    requester, manager, technician = (workflow.auth(n) for n in ("requester", "manager", "technician"))
    title = "Intervention native " + variant + " " + workflow.key()[:8]
    created = workflow.send("/requests", requester, 201, "POST", {"title": title}, workflow.key())
    resource = "/requests/" + created["id"]
    workflow.send(resource + "/assign", manager, 204, "POST", {
        "technicianId": workflow.actor_id("technician"), "expectedVersion": created["version"],
    })
    assigned = workflow.send(resource, technician, 200)
    workflow.send(resource + "/start", technician, 200, "POST", {"expectedVersion": assigned["version"]})

    launch()
    click("TECHNICIAN")
    if variant == "kotlin":
        if any(node.get("text") == "Java" for node in ui()):
            click("Java")
            click("Kotlin")
    else:
        if any(node.get("text") == "Kotlin" for node in ui()):
            click("Kotlin")
            click("Java")
    click(title + " En cours")
    click("RÉDIGER UN COMPTE RENDU")
    report = "Porte verifiee."
    airplane(True)
    try:
        find("Hors ligne")
        field = next(n for n in ui() if n.get("class") == "android.widget.EditText")
        tap(field)
        adb("shell", "input", "text", report.replace(" ", "%s"))
        find(report)
        launch()
        find("Hors ligne")
        click(title + " En cours")
        find(report)
    finally:
        airplane(False)
    find("En ligne")
    click("TECHNICIAN")
    click("CONFIRMER L’ENVOI")
    find("Compte rendu enregistré.")
    resolved = workflow.send(resource, technician, 200)
    assert resolved["status"] == "RESOLVED" and resolved["report"] == report, resolved
    print(f"{variant}: rapport réouvert à froid et confirmé depuis Android", flush=True)


if __name__ == "__main__":
    variant = sys.argv[1]
    if variant not in ("java", "kotlin"):
        raise SystemExit("java ou kotlin attendu")
    native_test(variant, 8080 if variant == "java" else 8081)
