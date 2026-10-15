#!/usr/bin/env python3
"""Repeatable emulator-only recovery verification. Forces jobs instead of waiting 15 minutes."""
import importlib.util
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("eye", ROOT / "scripts/emulator_eye_test.py")
eye = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eye)
eye.ADB = ROOT / ".tools/sdk/platform-tools/adb"
devices = subprocess.check_output([str(eye.ADB), "devices"], text=True)
serials = re.findall(r"^(emulator-\d+)\s+device$", devices, re.M)
if len(serials) != 1:
    raise SystemExit("Start exactly one emulator; this test never resets a physical phone.")
eye.SERIAL = serials[0]
eye.OUTPUT = ROOT / "artifacts/recovery-tests"
eye.OUTPUT.mkdir(parents=True, exist_ok=True)
PACKAGE = eye.PACKAGE
cmd = eye.command


def prefs():
    raw = cmd("shell", "run-as", PACKAGE, "cat", "shared_prefs/black.xml", check=False)
    try:
        return {node.attrib["name"]: node.attrib.get("value", node.text) for node in ET.fromstring(raw)}
    except ET.ParseError:
        return {}


def wait(predicate, label, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            print(f"PASS {label}", flush=True)
            return
        time.sleep(0.35)
    raise RuntimeError(f"Timed out: {label}; state={prefs()}")


def running():
    return "isForeground=true" in cmd("shell", "dumpsys", "activity", "services", PACKAGE)


def notification():
    return bool(re.search(r"NotificationRecord[^\n]*com\.levabala\.blackandroid[^\n]*\bid=4\b",
                          cmd("shell", "dumpsys", "notification", "--noredact")))


def lose_service():
    # Explicitly stop just the service while preserving the user's enabled preference,
    # then kill the process. This prevents START_STICKY from hiding the watchdog test.
    cmd("shell", "run-as", PACKAGE, "am", "stopservice", "--user", "0", "-n", f"{PACKAGE}/.BlackService", check=False)
    wait(lambda: not running(), "service loss while schedule intent remains enabled")
    assert prefs().get("enabled") == "true"
    pid = cmd("shell", "pidof", PACKAGE, check=False).strip()
    if pid:
        cmd("shell", "run-as", PACKAGE, "kill", "-9", pid)
        wait(lambda: cmd("shell", "pidof", PACKAGE, check=False).strip() != pid, "app process terminated")
    assert not running()


def force_check():
    cmd("shell", "cmd", "jobscheduler", "run", "-f", PACKAGE, "1402")


original_whitelist = cmd("shell", "dumpsys", "deviceidle", "whitelist")
original_exempt = any(PACKAGE in line for line in original_whitelist.splitlines())
try:
    cmd("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    cmd("shell", "pm", "clear", PACKAGE)
    cmd("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", "en-US")
    cmd("logcat", "-c")
    cmd("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    cmd("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    cmd("shell", "dumpsys", "deviceidle", "whitelist", "-" + PACKAGE, check=False)
    cmd("shell", "am", "start", "-n", eye.ACTIVITY)
    eye.tap_text("START")
    wait(running, "foreground service started")
    wait(lambda: int(prefs().get("lastServiceHeartbeat", 0)) > 0, "service heartbeat recorded")
    assert "unknown" not in cmd("shell", "cmd", "jobscheduler", "get-job-state", PACKAGE, "1402").lower()
    eye.tap_text("BACKGROUND RELIABILITY")
    eye.wait_node(lambda n: "Android battery exemption: Not exempt" in n.attrib.get("text", ""), "exemption status")
    eye.capture("01-setup.png", "Reliability screen reports actual service and battery state", eye.current_status())
    eye.tap_text("ALLOW BACKGROUND RECOVERY")
    eye.wait_node(lambda n: n.attrib.get("package") == "com.android.settings", "Android exemption prompt")
    eye.capture("02-system-exemption.png", "Battery exemption opens Android confirmation", eye.current_status())
    cmd("shell", "input", "keyevent", "KEYCODE_BACK")
    cmd("shell", "input", "keyevent", "KEYCODE_HOME")

    lose_service()
    force_check()
    wait(notification, "missing service posts Resume notification without battery exemption")
    assert not running(), "A background restart should wait for permission or a user action"
    cmd("shell", "cmd", "statusbar", "expand-notifications")
    eye.wait_node(lambda n: n.attrib.get("text", "").casefold() == "resume black", "notification Resume action")
    eye.capture("03-resume-notification.png", "One recovery notification offers Resume", eye.current_status())
    # Check deduplication before exercising the user action.
    cmd("shell", "cmd", "statusbar", "collapse")
    force_check()
    time.sleep(1)
    logs = cmd("logcat", "-d")
    assert sum("recovery.resume_notification_posted" in line for line in logs.splitlines()) == 1
    cmd("shell", "cmd", "statusbar", "expand-notifications")
    eye.tap_text("Resume Black")
    cmd("shell", "cmd", "statusbar", "collapse")
    wait(running, "notification action resumes service without opening Black")
    wait(lambda: not notification(), "successful resume removes notification")

    lose_service()
    cmd("shell", "dumpsys", "deviceidle", "whitelist", "+" + PACKAGE)
    force_check()
    wait(running, "battery-exempt job recovers a dead process without opening Black")
    wait(lambda: not notification(), "automatic recovery clears reminder")
    cmd("shell", "am", "start", "-n", eye.ACTIVITY)
    eye.tap_text("BACKGROUND RELIABILITY")
    eye.wait_node(lambda n: "Android battery exemption: Allowed" in n.attrib.get("text", ""), "granted exemption status")
    eye.capture("03-allowed.png", "Granted exemption and running service verified", eye.current_status())
    cmd("shell", "input", "keyevent", "KEYCODE_HOME")

    cmd("shell", "dumpsys", "battery", "unplug")
    cmd("shell", "cmd", "power", "set-mode", "1")
    assert cmd("shell", "settings", "get", "global", "low_power").strip() == "1"
    lose_service()
    cmd("shell", "cmd", "power", "set-mode", "0")
    # The receiver cannot wake a dead process. Trigger the persisted job to verify
    # its eventual delivery path without pretending that recovery is instantaneous.
    force_check()
    wait(running, "recovery after power-saving exit and process death")
    logs = cmd("logcat", "-d")
    assert "process.previous_exit" in logs, "Previous process exit diagnostics were not logged"

    cmd("shell", "dumpsys", "battery", "reset")
    cmd("reboot")
    cmd("wait-for-device")
    wait(lambda: cmd("shell", "getprop", "sys.boot_completed").strip() == "1", "emulator reboot", 90)
    wait(running, "boot receiver restores enabled service without opening Black", 30)
    wait(lambda: int(prefs().get("lastServiceHeartbeat", 0)) > 0, "heartbeat after reboot")
    assert "unknown" not in cmd("shell", "cmd", "jobscheduler", "get-job-state", PACKAGE, "1402").lower()

    cmd("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    cmd("shell", "wm", "dismiss-keyguard")
    cmd("shell", "am", "start", "-n", eye.ACTIVITY)
    eye.tap_text("STOP")
    wait(lambda: not running() and prefs().get("enabled") == "false", "Stop disables service and recovery")
    job = cmd("shell", "cmd", "jobscheduler", "get-job-state", PACKAGE, "1402", check=False)
    assert "unknown" in job.lower() or "no such" in job.lower(), job
    assert not notification()
    # A stale Resume PendingIntent must not override Stop.
    cmd("shell", "run-as", PACKAGE, "am", "startservice", "--user", "0", "-n",
        f"{PACKAGE}/.BlackService", "-a", f"{PACKAGE}.START")
    time.sleep(1)
    assert not running() and prefs().get("enabled") == "false"
    eye.capture("04-stopped.png", "Stop remains stopped; stale resume cannot resurrect it", eye.current_status())
    eye.write_gallery()
    print(f"Recovery verification passed: {eye.OUTPUT / 'index.html'}", flush=True)
finally:
    cmd("shell", "cmd", "power", "set-mode", "0", check=False)
    cmd("shell", "dumpsys", "battery", "reset", check=False)
    cmd("shell", "dumpsys", "deviceidle", "whitelist",
        ("+" if original_exempt else "-") + PACKAGE, check=False)
