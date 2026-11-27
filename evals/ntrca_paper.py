"""Runs the 17th NTRCA (school-2, 2022) paper on the phone, questions 1..N, in ONE app start.

Two things this fixes, both reported from watching the first attempt:
  - the app was restarted for every question, which woke the model up again each time (~30 s
    wasted per case) and made the run unlike real use;
  - the questions were 16 paraphrases from the repo rather than the actual paper.

The paper lives in the local study corpus (personal use only, so it is read from disk and never
copied into the repo). Each question is sent as the exam presents it, options included, and scored
against the paper's own recorded answer.
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
PAPER = r"D:\ShikkhaSathi\data\personal-study\biddabari\ntrca-17th-ntrca-question-solution-2022-school-2.json"
LABELS = ["ক", "খ", "গ", "ঘ"]


def adb(*args, check=False):
    r = subprocess.run([ADB, *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout


def read_json(rel):
    out = adb("shell", "run-as", PKG, "base64", rel)
    b64 = b"".join(out.split())
    if not b64:
        return None
    return json.loads(base64.b64decode(b64).decode("utf-8"))


def build(first, last):
    paper = json.load(open(PAPER, encoding="utf-8"))
    by_num = {q["number"]: q for q in paper["questions"]}
    cases = []
    for n in range(first, last + 1):
        q = by_num.get(n)
        if not q:
            continue
        opts = q.get("options") or []
        if not opts:
            continue
        ask = q["question"].strip()
        letters = [{"index": i, "label": LABELS[i] if i < len(LABELS) else str(i + 1), "text": t}
                   for i, t in enumerate(opts)]
        ask += "\n" + " ".join(f"({o['label']}) {o['text']}" for o in letters)
        cases.append({
            "id": f"q{n:02d}",
            "ask": ask,
            "web": False,
            "expect_label": LABELS[q["correctIndex"]] if q["correctIndex"] is not None else None,
            "expect_text": q.get("answerText"),
            "number": n,
        })
    return cases


def main():
    first = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    last = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    cases = build(first, last)
    print(f"built {len(cases)} questions ({first}..{last})")

    batch = {"cases": [{"id": c["id"], "ask": c["ask"], "web": c["web"]} for c in cases]}
    local = os.path.join(os.environ["TEMP"], "ntrca_batch.json")
    with open(local, "w", encoding="utf-8") as f:
        json.dump(batch, f, ensure_ascii=False)
    adb("push", local, "/data/local/tmp/batch.json")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/batch.json", "files/eval_batch.json")
    adb("shell", "run-as", PKG, "rm", "-f", "files/eval/_batch_done.json")

    adb("shell", "am", "force-stop", PKG)
    t0 = time.time()
    adb("shell", "am", "start", "-n", f"{PKG}/com.offlinegemma.app.MainActivity",
        "--es", "eval_case", "eval_batch.json")
    print("started one batch run (single app start)")

    results = {}
    deadline = t0 + 15 * 60 * 6
    while time.time() < deadline:
        time.sleep(15)
        done = read_json("files/eval/_batch_done.json")
        if done:
            break
        got = 0
        for c in cases:
            if c["id"] not in results:
                r = read_json(f"files/eval/{c['id']}.json")
                if r:
                    results[c["id"]] = r
                    got += 1
        print(f"  {len(results)}/{len(cases)} answered  ({int(time.time()-t0)}s)")

    right = wrong = 0
    for c in cases:
        r = results.get(c["id"])
        print("=" * 70)
        print(f"{c['id']}  {c['ask'].splitlines()[0]}")
        print(f"  paper answer: ({c['expect_label']}) {c['expect_text']}")
        if not r:
            print("  NO RESULT")
            continue
        text = (r.get("text") or "").strip()
        first_line = text.splitlines()[0] if text else "(empty)"
        ok = bool(c["expect_text"]) and c["expect_text"].strip() in text
        # Also accept the right option letter leading the answer.
        if not ok and c["expect_label"]:
            ok = first_line.lstrip("* (")[:1] == c["expect_label"]
        right += 1 if ok else 0
        wrong += 0 if ok else 1
        print(f"  {'CORRECT' if ok else 'WRONG'}   {r.get('total_secs')}s")
        print(f"  answered: {first_line[:120]}")
        if not ok:
            print(f"  full: {text[:400]}")
    print()
    print(f"SCORE {right}/{right + wrong} correct")
    with open(os.path.join(os.environ["TEMP"], "ntrca_report.json"), "w", encoding="utf-8") as f:
        json.dump({"cases": cases, "results": results}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()