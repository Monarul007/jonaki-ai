"""Runs eval cases on the device and prints answers, without PowerShell's encoding traps.

Windows PowerShell 5.1 reads .ps1 files as ANSI, so a Bengali question inside a script became
mojibake before it ever reached the phone. This driver keeps every string in UTF-8 and passes it
through a file, base64 where the adb shell might otherwise mangle it.
"""
import base64
import json
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")

ADB = r"E:\AI\litert-lm\Alchemize\.tooling\android-sdk\platform-tools\adb.exe"
PKG = "com.offlinegemma.app"


def adb(*args, check=False):
    r = subprocess.run([ADB, *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout


def read_app_file(rel):
    """Base64 round-trip: adb shell corrupts non-ASCII on stdout, base64 does not."""
    out = adb("shell", "run-as", PKG, "base64", rel)
    b64 = b"".join(out.split())
    if not b64:
        return None
    return json.loads(base64.b64decode(b64).decode("utf-8"))


def run_case(case, timeout=600):
    payload = json.dumps(case, ensure_ascii=False).encode("utf-8")
    local = os.path.join(os.environ["TEMP"], "case.json")
    with open(local, "wb") as f:
        f.write(payload)
    adb("push", local, "/data/local/tmp/case.json")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/case.json", "files/eval_case.json")
    adb("shell", "run-as", PKG, "rm", "-f", f"files/eval/{case['id']}.json")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/com.offlinegemma.app.MainActivity",
        "--es", "eval_case", "eval_case.json")
    start = time.time()
    while time.time() - start < timeout:
        time.sleep(6)
        got = read_app_file(f"files/eval/{case['id']}.json")
        if got:
            return got
    return None


def score(case, text):
    """Rough automatic check: does every point have one of its alternative words?

    Same rule as run_evals.py, kept here so a device run reports a pass/fail per case. The number is
    only a screen for a human read: an answer can contain the right token and still be wrong.
    """
    if not text:
        return 0, 0, []
    low = text.lower()
    hits, misses = 0, []
    points = case.get("points") or []
    for p in points:
        alts = (p.get("any") or []) + (p.get("all") or [])
        ok = bool(alts) and any(a.lower() in low for a in alts)
        if ok:
            hits += 1
        else:
            misses.append(p.get("point", "?"))
    return hits, len(points), misses


def main():
    path = sys.argv[1]
    only = sys.argv[2:] or None
    with open(path, encoding="utf-8") as f:
        cases = json.load(f)["cases"]
    total_pts = total_hits = 0
    for c in cases:
        if only and c["id"] not in only:
            continue
        res = run_case(c)
        print("=" * 70)
        print(f"{c['id']}  asked: {c['ask']}")
        if not res:
            print("  TIMEOUT")
            continue
        hits, n, misses = score(c, res.get("text") or "")
        total_pts += n
        total_hits += hits
        print(f"  score {hits}/{n}   total {res.get('total_secs')}s  answer {res.get('answer_secs')}s")
        print(f"  steps: " + ", ".join(
            f"{t['name']}({'ok' if t.get('ok') else 'FAIL'}{', ' + str(t['ms']) + 'ms' if t.get('ms') else ''})"
            for t in res.get("tools", [])))
        print("  answer:")
        for line in (res.get("text") or "").splitlines():
            print("    " + line)
        if misses:
            print("  MISSED points: " + " | ".join(misses))
        if res.get("error"):
            print("  ERROR: " + str(res["error"]))
        print()
    if total_pts:
        print(f"TOTAL {total_hits}/{total_pts} points ({100.0 * total_hits / total_pts:.0f}%)")


if __name__ == "__main__":
    main()