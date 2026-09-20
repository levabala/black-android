#!/usr/bin/env python3
"""Repeatable visual smoke test for the Android emulator (standard library only)."""

import argparse
import html
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.levabala.blackandroid"
ACTIVITY = f"{PACKAGE}/.MainActivity"
STEPS = []


def command(*args, text=True, check=True):
    result = subprocess.run(
        [str(ADB), "-s", SERIAL, *args], capture_output=True, text=text, check=False
    )
    if check and result.returncode:
        detail = result.stderr if text else result.stderr.decode(errors="replace")
        raise RuntimeError(f"adb {' '.join(args)} failed: {detail.strip()}")
    return result.stdout


def ui():
    command("shell", "uiautomator", "dump", "/sdcard/black-eye-window.xml")
    return ET.fromstring(command("shell", "cat", "/sdcard/black-eye-window.xml"))


def center(node):
    bounds = [int(value) for value in re.findall(r"\d+", node.attrib["bounds"])]
    return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2


def tap_node(node):
    x, y = center(node)
    command("shell", "input", "tap", str(x), str(y))


def tap_text(label):
    tap_node(wait_node(lambda node: node.attrib.get("text", "").casefold() == label.casefold(), label))


def wait_node(predicate, label, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        match = next((node for node in ui().iter() if predicate(node)), None)
        if match is not None:
            return match
        time.sleep(0.3)
    raise RuntimeError(f"Could not find visible control {label!r}")


def set_number(index, value):
    fields = [node for node in ui().iter() if node.attrib.get("class") == "android.widget.EditText"]
    if len(fields) < 3:
        raise RuntimeError(f"Expected three numeric fields, found {len(fields)}")
    tap_node(fields[index])
    # A fixed ten deletes clears every allowed value, including the 86400-second maximum.
    command("shell", "input keyevent 123; i=0; while [ $i -lt 10 ]; do input keyevent 67; i=$((i+1)); done")
    command("shell", "input", "text", str(value))
    hide_keyboard()


def hide_keyboard():
    state = command("shell", "dumpsys", "input_method")
    if "mInputShown=true" in state:
        command("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.4)


def current_status():
    raw = command("shell", "run-as", PACKAGE, "cat", "shared_prefs/black.xml", check=False)
    try:
        root = ET.fromstring(raw)
        return next((node.text or "") for node in root if node.attrib.get("name") == "status")
    except (ET.ParseError, StopIteration):
        return ""


def wait_status(prefix, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        status = current_status()
        if status.startswith(prefix):
            return status
        time.sleep(0.4)
    raise RuntimeError(f"Expected status starting with {prefix!r}; last status: {current_status()!r}")


def status_seconds(status):
    match = re.search(r"(\d+):(\d{2})", status)
    if not match:
        raise RuntimeError(f"Status has no timer: {status!r}")
    return int(match.group(1)) * 60 + int(match.group(2))


def capture(filename, label, status):
    path = OUTPUT / filename
    path.write_bytes(command("exec-out", "screencap", "-p", text=False))
    STEPS.append((filename, label, status))
    print(f"PASS {label}: {status}", flush=True)


def warning_notification_active():
    dump = command("shell", "dumpsys", "notification", "--noredact")
    return bool(re.search(r"NotificationRecord[^\n]*\b(?:pkg=)?com\.levabala\.blackandroid[^\n]*\bid=2\b", dump))


def wait_warning_notification(timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if warning_notification_active():
            return
        time.sleep(0.4)
    raise RuntimeError("The 10-second warning notification was not posted")


def wait_warning_notification_gone(timeout=5):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if not warning_notification_active():
            return
        time.sleep(0.3)
    raise RuntimeError("The 10-second warning notification remained after cancellation")


def write_gallery():
    cards = "\n".join(
        f'<figure><img src="{html.escape(filename)}" alt="{html.escape(label)}">'
        f'<figcaption>{html.escape(label)}<small>{html.escape(status)}</small></figcaption></figure>'
        for filename, label, status in STEPS
    )
    (OUTPUT / "index.html").write_text(
        "<!doctype html><html><meta charset='utf-8'><title>Black emulator eye test</title>"
        "<style>body{font:16px system-ui;background:#171717;color:white;margin:24px}"
        "main{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:20px}"
        "figure{margin:0}img{width:100%;border:1px solid #555}figcaption{padding:8px 0}"
        "small{display:block;color:#aaa;margin-top:4px}</style><h1>Black emulator eye test</h1>"
        f"<p>{html.escape(SERIAL)} · {time.strftime('%Y-%m-%d %H:%M:%S')}</p><main>{cards}</main></html>",
        encoding="utf-8",
    )


def main():
    command("install", "-r", str(APK))
    # This script only runs on an emulator. Reset app data for a deterministic, cheap rerun.
    command("shell", "pm", "clear", PACKAGE)
    command("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    command("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    command("shell", "cmd", "statusbar", "collapse")
    command("shell", "am", "start", "-n", ACTIVITY)

    spinner = wait_node(lambda node: node.attrib.get("class") == "android.widget.Spinner", "Interval unit selector")
    tap_node(spinner)
    tap_text("Seconds")
    set_number(0, 8)
    set_number(1, 18)
    set_number(2, 20)
    hide_keyboard()
    values = [node.attrib.get("text") for node in ui().iter() if node.attrib.get("class") == "android.widget.EditText"]
    if values[:3] != ["8", "18", "20"]:
        raise RuntimeError(f"Timer fields were not set correctly: {values[:3]}")
    tap_text("START")
    capture("01-countdown.png", "Countdown with 8-second interval", wait_status("Next warning"))
    if warning_notification_active():
        raise RuntimeError("The 10-second warning notification appeared during countdown")
    command("shell", "input", "keyevent", "KEYCODE_HOME")

    warning_status = wait_status("Blackout in", 20)
    if status_seconds(warning_status) > 10 and warning_notification_active():
        raise RuntimeError(f"The warning notification appeared too early: {warning_status!r}")
    capture("02-warning.png", "Warning overlay over home screen", warning_status)
    wait_warning_notification(18)
    command("shell", "cmd", "statusbar", "expand-notifications")
    time.sleep(0.8)
    capture("03-notification.png", "10-second notification with Cancel action", current_status())
    tap_text("Cancel")
    command("shell", "cmd", "statusbar", "collapse")
    time.sleep(0.5)
    capture("04-notification-cancel.png", "Notification action restarted countdown", wait_status("Next warning", 5))
    wait_warning_notification_gone()

    capture("05-blackout.png", "Blackout overlay", wait_status("Blackout:", 45))
    wait_warning_notification_gone()
    command("shell", "input tap 540 1100; sleep 0.2; input tap 540 1100; sleep 0.2; input tap 540 1100")
    capture("06-three-taps.png", "Three taps restarted countdown", wait_status("Next warning", 5))

    command("shell", "input", "keyevent", "26")
    locked_status = wait_status("Paused while locked", 5)
    locked_remaining = status_seconds(locked_status)
    if not 0 < locked_remaining < 8:
        raise RuntimeError(f"Expected an in-progress countdown when locked: {locked_status!r}")
    command("shell", "input", "keyevent", "26")
    wake_status = wait_status("Next warning", 5)
    if status_seconds(wake_status) > locked_remaining:
        raise RuntimeError(f"Short lock reset the countdown: {locked_status!r} -> {wake_status!r}")
    capture("07-after-wake.png", "Screen wake resumed countdown", wake_status)

    if INCLUDE_REBOOT:
        command("reboot")
        command("wait-for-device")
        boot_deadline = time.monotonic() + 90
        while command("shell", "getprop", "sys.boot_completed").strip() != "1":
            if time.monotonic() >= boot_deadline:
                raise RuntimeError("Emulator did not finish rebooting within 90 seconds")
            time.sleep(1)
        capture("08-after-reboot.png", "Enabled service resumed after reboot", wait_status("Next warning", 30))

    command("shell", "am", "start", "-n", ACTIVITY)
    tap_text("STOP")
    wait_status("Stopped", 5)
    write_gallery()
    print(f"Eye test passed. Open {OUTPUT / 'index.html'} to review screenshots.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB emulator serial; defaults to the only connected emulator")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/eye-tests")
    parser.add_argument("--include-reboot", action="store_true", help="also verify boot recovery (slower)")
    args = parser.parse_args()

    sdk_candidates = [Path(path) for path in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")) if path]
    sdk_candidates.append(ROOT / ".tools/sdk")
    ADB = next((sdk / "platform-tools/adb" for sdk in sdk_candidates if (sdk / "platform-tools/adb").exists()), None)
    if ADB is None:
        executable = shutil.which("adb")
        if not executable:
            parser.error("adb is missing; set ANDROID_HOME or install Android platform-tools")
        ADB = Path(executable)
    device_lines = subprocess.run([str(ADB), "devices"], capture_output=True, text=True, check=True).stdout.splitlines()
    emulators = [line.split()[0] for line in device_lines if re.match(r"^emulator-\d+\s+device$", line)]
    SERIAL = args.serial or (emulators[0] if len(emulators) == 1 else "")
    if SERIAL not in emulators:
        parser.error("start one Android emulator first; this script refuses to clear data on a phone")
    APK = args.apk.resolve()
    if not APK.is_file():
        parser.error(f"APK is missing: {APK}; run ./gradlew assembleDebug")
    OUTPUT = args.output.resolve()
    INCLUDE_REBOOT = args.include_reboot
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "failure.png").unlink(missing_ok=True)
    try:
        main()
    except Exception as error:
        try:
            (OUTPUT / "failure.png").write_bytes(command("exec-out", "screencap", "-p", text=False))
        except Exception:
            pass
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
