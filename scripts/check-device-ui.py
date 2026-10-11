"""Read only Auralis UI. Fail rather than reusing a stale device hierarchy."""
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
ADB = str(ROOT / "tools/android-sdk/platform-tools/adb.exe")
remote = f"/sdcard/auralis-check-{time.time_ns()}.xml"
result = subprocess.run([ADB, "shell", "uiautomator", "dump", remote], capture_output=True, text=True)
if result.returncode or "UI hierchary dumped" not in result.stdout:
    raise SystemExit("Fresh app hierarchy unavailable; no stale data used.")
xml = subprocess.check_output([ADB, "exec-out", "cat", remote])
subprocess.run([ADB, "shell", "rm", remote], check=True)
nodes = [n for n in ET.fromstring(xml).iter("node") if n.get("package") == "com.auralis.music"]
if not nodes:
    raise SystemExit("Auralis is not foreground; no other app content shown.")
for node in nodes:
    text = node.get("text") or node.get("content-desc")
    if text:
        print(f"{text} | {node.get('bounds')}")
if len(sys.argv) > 1:
    target = ROOT / "tmp" / (Path(sys.argv[1]).name + ".png")
    target.write_bytes(subprocess.check_output([ADB, "exec-out", "screencap", "-p"]))
    print(f"Screenshot: {target}")
