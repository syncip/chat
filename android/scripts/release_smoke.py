#!/usr/bin/env python3
"""
Emulator-Test der **Release-APK** (R8, signiert) – genau das, was Nutzer installieren.

Bedient die App per adb/UI Automator (Compose-Test-Tags sind als resource-id sichtbar) gegen einen echten Server:
Registrierung → Backup → App-PIN einrichten → Gerätesperre (Geräte-PIN, derselbe Keystore-Weg wie der Fingerabdruck)
einrichten → Sperren-Knopf → Entsperren per App-PIN → Sperren → Entsperren per Gerätesperre → App neu starten → Entsperren.

Aufruf: release_smoke.py <apk> [server=10.0.2.2:18200] [admin-key=emulator-admin-key]
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

APK = sys.argv[1]
SERVER = sys.argv[2] if len(sys.argv) > 2 else "10.0.2.2:18200"
ADMIN_KEY = sys.argv[3] if len(sys.argv) > 3 else "emulator-admin-key"
HOST_URL = "http://127.0.0.1:" + SERVER.split(":")[1]
PKG = "org.syncip.chat"
PASS = "releasepass1"
APP_PIN = "2468"
DEVICE_PIN = "1111"


def adb(*a, check=True):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check).stdout


def dump():
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        x = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if x.startswith("<?xml"):
            return ET.fromstring(x)
        time.sleep(0.5)
    raise RuntimeError("UI-Dump fehlgeschlagen")


def matches(n, rid=None, text=None, cls=None):
    if rid is not None:
        r = n.get("resource-id", "")
        if r != rid and not r.endswith(":id/" + rid):
            return False
    if text is not None:
        t = (n.get("text") or "") + "\n" + (n.get("content-desc") or "")
        if not re.search(text, t):
            return False
    if cls is not None and n.get("class") != cls:
        return False
    return True


def find_all(root, **kw):
    return [n for n in root.iter("node") if matches(n, **kw)]


def screen(root=None):
    root = root if root is not None else dump()
    out = []
    for n in root.iter("node"):
        bits = [n.get("resource-id", ""), n.get("text", ""), n.get("content-desc", "")]
        bits = [b for b in bits if b]
        if bits:
            out.append(" | ".join(bits))
    return "\n".join(out)


def fail(msg):
    print("✗ " + msg)
    try:
        print("--- Bildschirm ---\n" + screen())
    except Exception as e:  # noqa: BLE001
        print("(kein Bildschirm: %s)" % e)
    print("--- logcat ---")
    print("\n".join(adb("logcat", "-d", check=False).splitlines()[-250:]))
    sys.exit(1)


def wait(desc, timeout=60, index=0, **kw):
    end = time.time() + timeout
    swiped = 0
    while time.time() < end:
        root = dump()
        found = find_all(root, **kw)
        if len(found) > index:
            return found[index]
        # Elemente unterhalb des sichtbaren Bereichs: einmal pro Durchgang nach oben wischen (max. 3x)
        if kw.get("rid") and swiped < 3 and time.time() > end - timeout + 3:
            adb("shell", "input", "swipe", "540", "1500", "540", "700", "300")
            swiped += 1
        time.sleep(0.7)
    fail("Zeitüberschreitung: " + desc)


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(desc, **kw):
    x, y = center(wait(desc, **kw))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.4)


def type_into(desc, value, **kw):
    tap(desc, **kw)
    adb("shell", "input", "text", value)
    adb("shell", "input", "keyevent", "111")  # Tastatur schließen (ESC)
    time.sleep(0.3)


def device_credential():
    """System-Dialog der Gerätesperre: Geräte-PIN eingeben."""
    wait("Gerätesperre-Abfrage", timeout=30, cls="android.widget.EditText")
    time.sleep(0.5)
    adb("shell", "input", "text", DEVICE_PIN)
    adb("shell", "input", "keyevent", "66")


def ok(msg):
    print("✔ " + msg, flush=True)


def invite():
    req = urllib.request.Request(HOST_URL + "/v1/admin/invites", method="POST", headers={"X-Admin-Key": ADMIN_KEY}, data=b"")
    return json.loads(urllib.request.urlopen(req).read())["invite"]


# --- Vorbereitung ---
adb("shell", "locksettings", "set-pin", DEVICE_PIN, check=False)  # Gerätesperre wie auf einem echten Handy
adb("uninstall", PKG, check=False)
adb("install", "-r", APK)
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
adb("logcat", "-c", check=False)
adb("shell", "am", "start", "-n", PKG + "/chat.android.MainActivity")
ok("Release-APK installiert und gestartet")

# --- Registrierung ---
tap("Registrieren", rid="onb_register")
type_into("Server", SERVER, rid="reg_server")
type_into("Name", "rel", rid="reg_name")
type_into("Einladung", invite(), rid="reg_invite")
type_into("Passphrase", PASS, rid="reg_pass")
type_into("Passphrase 2", PASS, rid="reg_pass2")
tap("Konto erstellen", rid="reg_submit")
wait("Backup-Dialog", timeout=90, text="Backup speichern")
ok("Registrierung")

# --- Backup (Pflicht) ---
for i in range(2):
    x, y = center(wait("Backup-Feld %d" % i, cls="android.widget.EditText", index=i))
    adb("shell", "input", "tap", str(x), str(y))
    adb("shell", "input", "text", "backuppass1")
    adb("shell", "input", "keyevent", "111")
tap("Backup-Datei speichern", text="^Backup-Datei speichern$")
tap("Speichern im Dateidialog", timeout=30, text="^(SAVE|Save|Speichern|SPEICHERN)$")
tap("Backup bestätigt", timeout=30, text="Ich habe das Backup sicher abgelegt")
ok("Backup gespeichert")

# --- App-PIN einrichten ---
tap("Angebot Schnell-Entsperren", timeout=30, text="^Jetzt einrichten$")
type_into("Passphrase", PASS, rid="qu_pass")
type_into("PIN", APP_PIN, rid="qu_pin")
type_into("PIN 2", APP_PIN, rid="qu_pin2")
tap("PIN speichern", rid="qu_pin_save")
wait("PIN gespeichert", timeout=30, text="App-PIN gespeichert")
ok("App-PIN gespeichert")

# --- Gerätesperre / Fingerabdruck einrichten ---
type_into("Passphrase", PASS, rid="qu_pass")
tap("Gerätesperre aktivieren", text="aktivieren und speichern")
device_credential()
wait("Gerätesperre gespeichert", timeout=30, text="Fingerabdruck-Entsperren gespeichert")
ok("Fingerabdruck/Gerätesperre eingerichtet (App blieb dabei entsperrt)")

# --- Sperren-Knopf + Entsperren per App-PIN ---
for _ in range(3):
    if find_all(dump(), rid="btn_lock"):
        break
    adb("shell", "input", "keyevent", "4")
    time.sleep(0.8)
tap("Sperren-Knopf", rid="btn_lock")
wait("Sperrbildschirm mit PIN-Feld", timeout=20, rid="pin_key_2")
ok("Sperren-Knopf sperrt die App")
for k in APP_PIN:
    tap("PIN-Taste " + k, rid="pin_key_" + k)
t = time.time()
tap("OK", rid="pin_key_k")
wait("Startseite nach PIN", timeout=60, rid="fab_add")
ok("Entsperren per App-PIN (%.1f s)" % (time.time() - t))

# --- Sperren + Entsperren per Gerätesperre ---
tap("Sperren-Knopf", rid="btn_lock")
wait("Sperrbildschirm", timeout=20, rid="pin_key_2")
tap("Fingerabdruck-Knopf", text="Fingerabdruck / Gerätesperre")
device_credential()
wait("Startseite nach Gerätesperre", timeout=60, rid="fab_add")
ok("Entsperren per Fingerabdruck/Gerätesperre")

# --- Neustart: App ist gesperrt, Abfrage kommt automatisch ---
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", PKG + "/chat.android.MainActivity")
device_credential()
wait("Startseite nach Neustart", timeout=60, rid="fab_add")
ok("Nach Neustart gesperrt und per Gerätesperre entsperrt")

print("\n".join(l for l in adb("logcat", "-d", check=False).splitlines() if "Entsperren:" in l))
print("RELEASE-APK-TEST BESTANDEN")
