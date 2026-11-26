"""Acceptance gate for Jonaki's answers, modelled on the sample's integration tests.

The Flutter sample does not trust a retriever's score. `rag_chat_test.dart` asserts that an
on-topic reply CONTAINS the fact from the cited excerpt, and that an off-topic question lands
BELOW the gate with no citation at all. `model_smoke_test.dart` fails rather than let a silent
backend change pass.

This does the same over a completed eval run:

  python evals/check_golden.py [results_dir]

Exit code 0 means every rule held. Each failure names the case, the rule and the evidence.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def newest_results() -> Path:
    dirs = sorted((HERE / "results").glob("*"), key=lambda p: p.name)
    dirs = [d for d in dirs if (d / "results.json").is_file()]
    if not dirs:
        sys.exit("no results in evals/results/ — run run_evals.py first")
    return dirs[-1]


def load_golden() -> dict:
    return json.loads((HERE / "golden.json").read_text(encoding="utf-8"))


def digits_only(s: str) -> str:
    """Bengali and ASCII digits normalised away, so a rule can be written once."""
    table = {ord(c): ord("0") + (ord(c) - ord("০")) for c in "০১২৩৪৫৬৭৮৯"}
    return s.translate(table)


def knowledge_sources(result: dict) -> list[str]:
    out: list[str] = []
    for t in result.get("tools") or []:
        if t.get("name") == "knowledge":
            out += [s.strip() for s in (t.get("detail") or "").split(",") if s.strip()]
    return out


def check_case(case: dict, result: dict) -> list[str]:
    problems: list[str] = []
    text = result.get("text") or ""
    flat = digits_only(text)

    for want in case.get("must_contain", []):
        if digits_only(want) not in flat:
            problems.append(f"missing {want!r}")

    for bad in case.get("must_not_contain", []):
        if digits_only(bad) in flat:
            problems.append(f"contains forbidden {bad!r}")

    for bad in case.get("knowledge_must_not_include", []):
        if any(bad in s for s in knowledge_sources(result)):
            problems.append(f"irrelevant study source {bad!r} was injected")

    if case.get("no_web_sources") and result.get("sources"):
        problems.append("web sources were fetched for an arithmetic question")

    return problems


def main() -> int:
    golden = load_golden()
    results = json.loads((newest_results() / "results.json").read_text(encoding="utf-8"))
    by_id = {r.get("id"): r for r in results}

    failures = 0
    print(f"run: {newest_results().name}")

    global_bad = golden.get("global", {}).get("must_not_contain", [])
    global_digits = golden.get("global", {}).get("forbid_hallucinated_digits", [])

    for r in results:
        rid = r.get("id")
        text = digits_only(r.get("text") or "")
        for bad in global_bad:
            if bad in text:
                print(f"FAIL {rid}: reasoning marker {bad!r} leaked into the answer")
                failures += 1
        for bad in global_digits:
            if digits_only(bad) in text:
                print(f"FAIL {rid}: hallucinated digits {bad!r} in the answer")
                failures += 1

    for case in golden.get("cases", []):
        cid = case["id"]
        result = by_id.get(cid)
        if result is None:
            print(f"SKIP {cid}: not in this run")
            continue
        problems = check_case(case, result)
        if problems:
            failures += len(problems)
            print(f"FAIL {cid}: " + "; ".join(problems))
        else:
            print(f"PASS {cid}")

    # Reported, not asserted: the repair only runs when the answer actually made a bad claim.
    repaired = [r.get("id") for r in results if "math repair" in (r.get("log") or "")]
    print(f"math repair ran for: {repaired or 'no case (nothing was wrong to fix)'}")

    print(f"\n{failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
