"""
Jonaki AI test bench: runs evals/cases.json on a connected device/emulator and scores every answer.

    python evals/run_evals.py                 # all cases
    python evals/run_evals.py clean_ga scan_ga   # some cases
    python evals/run_evals.py --score-only evals/results/<run>   # re-score saved answers

Needs a DEBUG build installed (package com.offlinegemma.app.dev) with the model downloaded.
Each case runs in a fresh chat through the app's `eval_case` test command; the app writes
files/eval/<id>.json when the answer and its figures are finished.

The automatic scores are rough (word lists in cases.json). Read the answers too: report.md
shows each one in full next to its scores.
"""
import json
import os
import subprocess
import sys
import time
import unicodedata
from datetime import datetime

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
# JONAKI_PKG=com.offlinegemma.app for a test build installed over the real app (-PoverRelease).
PKG = os.environ.get("JONAKI_PKG", "com.offlinegemma.app.dev")
ACTIVITY = PKG + "/com.offlinegemma.app.MainActivity"
CASE_TMP = "/data/local/tmp/eval_case.json"
CASE_IN_APP = "eval_case.json"  # relative: inside the app's files dir
CASE_TIMEOUT_S = 20 * 60
BENGALI = range(0x0980, 0x0A00)
OTHER_SCRIPTS = [(0x0900, 0x0963), (0x0966, 0x097F), (0x0A00, 0x0DFF), (0x0600, 0x06FF), (0x4E00, 0x9FFF)]


def adb(*args, check=True, text=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=text, encoding="utf-8" if text else None)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr or r.stdout}")
    return r.stdout if text else r.stdout


def push_page(name, rel):
    """Copies a page to the device's Pictures folder and returns its MediaStore content URI."""
    local = os.path.join(HERE, rel)
    remote = f"/sdcard/Pictures/eval_{name}.png"
    adb("push", local, remote)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + remote)
    for _ in range(20):
        out = adb("shell", "content", "query", "--uri", "content://media/external/images/media",
                  "--projection", "_id", "--where", f"\"_display_name='eval_{name}.png'\"", check=False)
        ids = [line.split("_id=")[1].strip() for line in out.splitlines() if "_id=" in line]
        if ids:
            return f"content://media/external/images/media/{ids[-1]}"
        time.sleep(1)
    raise RuntimeError(f"page {name} not found in MediaStore")


def run_case(case, page_uris, outdir):
    cid = case["id"]
    payload = {"id": cid, "ask": case["ask"], "web": case.get("web", True)}
    if case.get("before"):
        payload["before"] = case["before"]
    if case.get("page"):
        payload["image"] = page_uris[case["page"]]
    tmp = os.path.join(outdir, f"{cid}.case.json")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)
    adb("push", tmp, CASE_TMP)
    adb("shell", "run-as", PKG, "cp", CASE_TMP, "files/" + CASE_IN_APP)
    adb("shell", "run-as", PKG, "rm", "-f", f"files/eval/{cid}.json", check=False)
    adb("logcat", "-c", check=False)
    adb("shell", "am", "start", "-n", ACTIVITY, "--es", "eval_case", CASE_IN_APP)
    started = time.time()
    while time.time() - started < CASE_TIMEOUT_S:
        time.sleep(10)
        raw = adb("shell", "run-as", PKG, "cat", f"files/eval/{cid}.json", check=False)
        if raw.strip().startswith("{"):
            result = json.loads(raw)
            result["log"] = adb("logcat", "-d", "-s", "ChatController:I", "Eval:I", "Ocr:I", check=False)[-4000:]
            return result
    return {"id": cid, "text": "", "error": f"no result after {CASE_TIMEOUT_S}s", "tools": [], "sources": []}


def nfc(s):
    # Bengali letters such as য় can be one code point or two; compare them the same way.
    return unicodedata.normalize("NFC", s)


def has_any(text, words):
    low = nfc(text).lower()
    return any(nfc(w).lower() in low for w in words)


def score(case, result, cases_by_id):
    text = result.get("text") or ""
    src = cases_by_id.get(case.get("same_points_as"), case)
    checks = {}
    points = src.get("points", [])
    hit = []
    for p in points:
        ok = all(w in text for w in p["all"]) if "all" in p else has_any(text, p["any"])
        hit.append({"point": p["point"], "ok": ok})
    checks["key_points"] = f"{sum(h['ok'] for h in hit)}/{len(hit)}" if hit else "n/a"
    wrong = [w for w in src.get("wrong", []) if w.lower() in text.lower()]
    junk = [w for w in src.get("junk", []) if w in text]
    others = [p for p in "কখগঘ" if case.get("only_part") and p != case["only_part"] and f"({p})" in text]
    bengali = sum(1 for c in text if ord(c) in BENGALI)
    foreign = [c for c in text if any(a <= ord(c) <= b for a, b in OTHER_SCRIPTS)]
    stripped = text.rstrip()
    ended = stripped[-1:] in list("।?!.)」`*") or stripped.endswith("]") if stripped else False
    searches = [t["detail"] for t in result.get("tools", []) if t["name"] == "web_search"]
    terms = src.get("search_terms", [])
    checks.update({
        "error": result.get("error"),
        "answered": bool(text.strip()),
        "only_asked_part": not others if case.get("only_part") else None,
        "other_parts_seen": others,
        "wrong_signals": wrong,
        "ocr_junk": junk,
        "ends_properly": ended,
        "bengali_full_stops": (stripped.count("।") > 0) if bengali > 80 else None,
        "foreign_script_chars": len(foreign),
        "searched": bool(searches),
        "search_queries": searches,
        "search_on_topic": (any(has_any(q, terms) for q in searches) if searches and terms else None),
        "must_search_ok": (bool(searches) if case.get("must_search") else None),
        "no_search_ok": (not searches if case.get("must_not_search") else None),
        "answer_secs": result.get("answer_secs"),
        "total_secs": result.get("total_secs"),
        "words": len(text.split()),
    })
    problems = []
    if not checks["answered"] or checks["error"]:
        problems.append("no answer / error")
    if checks["only_asked_part"] is False:
        problems.append("answered other parts")
    if wrong:
        problems.append("wrong-answer signals")
    if junk:
        problems.append("OCR junk in answer")
    if not ended and text:
        problems.append("cut off")
    if checks["bengali_full_stops"] is False:
        problems.append("no Bengali full stops")
    if foreign:
        problems.append("other alphabets")
    if checks["search_on_topic"] is False:
        problems.append("off-topic search")
    if checks["must_search_ok"] is False:
        problems.append("did not search")
    if checks["no_search_ok"] is False:
        problems.append("searched when it should not")
    if hit and sum(h["ok"] for h in hit) < max(1, round(0.75 * len(hit))):
        problems.append("missing key points")
    checks["points_detail"] = hit
    checks["problems"] = problems
    checks["pass"] = not problems
    return checks


def report(cases, results, outdir):
    by_id = {c["id"]: c for c in cases}
    lines = ["# Test bench run " + os.path.basename(outdir.rstrip("/\\")), ""]
    passed = sum(1 for r in results if r["score"]["pass"])
    times = sorted(r["score"]["total_secs"] or 0 for r in results)
    median = times[len(times) // 2] if times else 0
    lines += [f"**Passed {passed}/{len(results)}** · median time {median:.0f} s · "
              f"max {max(times or [0]):.0f} s", "",
              "| Case | Pass | Key points | Problems | Time (s) | Search |", "|---|---|---|---|---|---|"]
    for r in results:
        s = r["score"]
        q = "; ".join(s["search_queries"])[:60]
        lines.append(f"| {r['id']} | {'✅' if s['pass'] else '❌'} | {s['key_points']} | "
                     f"{', '.join(s['problems']) or '—'} | {(s['total_secs'] or 0):.0f} | {q} |")
    lines.append("")
    for r in results:
        c = by_id[r["id"]]
        s = r["score"]
        lines += [f"## {r['id']}", f"Ask: `{c['ask']}`" + (f" · page `{c['page']}`" if c.get("page") else "")
                  + (" · web off" if c.get("web") is False else ""), ""]
        for p in s["points_detail"]:
            lines.append(f"- {'✅' if p['ok'] else '❌'} {p['point']}")
        if c.get("manual_fact_check"):
            lines.append("- ⚠️ facts need a manual check")
        lines += ["", "Answer:", "", "```", (r.get("text") or "").strip(), "```", ""]
        if r.get("sources"):
            lines.append("Sources: " + "; ".join(x["url"] for x in r["sources"]))
        if r.get("figures"):
            lines.append("Figures: " + "; ".join(r["figures"]))
        lines.append("")
    with open(os.path.join(outdir, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n".join(lines[:len(results) + 6]))


def main():
    args = sys.argv[1:]
    with open(os.path.join(HERE, "cases.json"), encoding="utf-8") as f:
        spec = json.load(f)
    cases = spec["cases"]
    # Recent NTRCA / BCS exam questions live in their own file.
    exams = os.path.join(HERE, "exam_cases.json")
    if os.path.exists(exams):
        with open(exams, encoding="utf-8") as f:
            cases = cases + json.load(f)["cases"]
    by_id = {c["id"]: c for c in cases}
    if args[:1] == ["--score-only"]:
        outdir = args[1]
        with open(os.path.join(outdir, "results.json"), encoding="utf-8") as f:
            results = json.load(f)
        for r in results:
            r["score"] = score(by_id[r["id"]], r, by_id)
    else:
        chosen = [by_id[a] for a in args] if args else cases
        outdir = os.path.join(HERE, "results", datetime.now().strftime("%Y%m%d-%H%M"))
        os.makedirs(outdir, exist_ok=True)
        uris = {name: push_page(name, rel) for name, rel in spec["pages"].items()
                if any(c.get("page") == name for c in chosen)}
        results = []
        for c in chosen:
            print(f"… {c['id']}", flush=True)
            r = run_case(c, uris, outdir)
            r["score"] = score(c, r, by_id)
            results.append(r)
            print(f"   {'PASS' if r['score']['pass'] else 'FAIL'} {r['score']['problems']} "
                  f"{(r['score']['total_secs'] or 0):.0f}s", flush=True)
            with open(os.path.join(outdir, "results.json"), "w", encoding="utf-8") as f:
                json.dump(results, f, ensure_ascii=False, indent=1)
    with open(os.path.join(outdir, "results.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    report(cases, results, outdir)


if __name__ == "__main__":
    main()
