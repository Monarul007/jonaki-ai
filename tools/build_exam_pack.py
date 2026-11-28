#!/usr/bin/env python3
"""
Builds the Android app's exam pack from the ShikkhaSathi study folders.

The knowledge pack is prose: each MCQ becomes a paragraph ("প্রশ্ন: … ক) … উত্তর: …") so it can
be searched. Exam mode needs the same questions as *structure* - four options, which one is
right, and the subject - so this writes a second, separate pack.

Like knowledge.pack it is built locally and never shipped in the repository, because every
source folder is marked "personal study only, do not redistribute".

Usage:
  python tools/build_exam_pack.py
  python tools/build_exam_pack.py --out app/src/main/assets/exam.pack --stats
"""

import argparse
import gzip
import json
import os
import re
import sys

DATA = r"D:\ShikkhaSathi\data"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "exam.pack")

# The study files record whether a paper deducts marks (negativeMarking) but not how long it
# runs. These are the conventional times for each examination, not scraped values.
MINUTES = {"NTRCA": 60, "BCS": 120}
DEFAULT_MINUTES = 60

CATEGORY = {"NTRCA": "ntrca", "BCS": "bcs"}

BENGALI = re.compile(r"[\u0980-\u09FF]")

# Page furniture that the source site's scraper left inside a handful of explanations: the
# academy's promo block, then its analytics and Cloudflare bootstrapping as literal script text.
# Rendered in review mode it is a ~6 KB wall of JavaScript under the answer, so it is cut out.
JUNK = re.compile(
    r"(?:উত্তরণ\s*Book Package|Book Package\s*প্রিলি|Help Branch List|Explore BPSC"
    r"|window\.dataLayer|gtag\(|window\.fbq|cdn-cgi|MathJax\.Hub|\$\(document\)\.ready"
    r"|Copyright\s*©|Concord Tower|Helpline:)",
    re.I,
)


def clean_text(text):
    """Cut scraped page furniture off an explanation."""
    text = norm(text)
    if not text:
        return ""
    cut = JUNK.search(text)
    if cut:
        text = text[: cut.start()]
    return norm(text).rstrip(" ,;:`-*/\\")


def norm(text):
    return re.sub(r"\s+", " ", text or "").strip()


def minutes_for(group, count):
    """A paper's running time. NTRCA and BCS have fixed real durations; anything else gets
    a minute per question, which is close to how these tests are set."""
    fixed = MINUTES.get(group)
    if fixed:
        return fixed
    return max(30, min(180, count))


def negative_for(doc):
    """The paper's own rule when it states one, else 0.5 for BCS (the real deduction), else 0."""
    exam = doc.get("exam") or {}
    if exam.get("negativeMarking") is True:
        return 0.5 if (exam.get("group") or "").upper() == "BCS" else 0.25
    if exam.get("negativeMarking") is False:
        return 0.0
    return 0.5 if (exam.get("group") or "").upper() == "BCS" else 0.0


def clean(question):
    """One question in the app's shape, or None when the item cannot be answered."""
    options = question.get("options") or []
    answer = question.get("correctIndex")
    text = norm(question.get("question"))
    if len(options) != 4 or not isinstance(answer, int) or not 0 <= answer < 4:
        return None
    if not text or any(not norm(o) for o in options):
        return None
    return {
        "n": question.get("number"),
        "subject": norm(question.get("subject")) or ("সাধারণ" if BENGALI.search(text) else "General"),
        "text": text,
        "options": [norm(o) for o in options],
        "answer": answer,
        "explain": clean_text(question.get("explanation")) or None,
    }


def load_papers(root, group):
    """Every paper under one study folder, as app-ready structure."""
    out = []
    if not os.path.isdir(root):
        return out
    for name in sorted(os.listdir(root)):
        if not name.endswith(".json") or name == "index.json":
            continue
        try:
            with open(os.path.join(root, name), encoding="utf-8") as fh:
                doc = json.load(fh)
        except (OSError, ValueError) as e:
            print(f"  skip {name}: {e}")
            continue
        exam = doc.get("exam") or {}
        questions = [q for q in (clean(item) for item in doc.get("questions") or []) if q]
        if len(questions) < 5:
            continue
        for i, q in enumerate(questions, 1):
            q["n"] = i
        subjects = {}
        for q in questions:
            subjects[q["subject"]] = subjects.get(q["subject"], 0) + 1
        title = norm(exam.get("title")) or name
        out.append({
            "id": norm(exam.get("id")) or os.path.splitext(name)[0],
            "title": title,
            "titleBn": norm(exam.get("titleBn")) or title,
            "category": CATEGORY.get(group, "other"),
            "group": group,
            "year": exam.get("year"),
            "date": exam.get("date"),
            "source_file": os.path.splitext(name)[0],
            "durationMinutes": minutes_for(group, len(questions)),
            "negative": negative_for(doc),
            "subjects": [{"name": k, "count": v} for k, v in subjects.items()],
            "questions": questions,
        })
    out.sort(key=lambda p: (-(p.get("year") or 0), p["title"]))
    return out


def disambiguate(papers):
    """Two files can carry the same title - the 48th BCS is split into general, medical and
    dental parts, all titled "48th BCS". In a list that reads as the same exam three times,
    so the differing tail of the file name is appended to each repeated title."""
    counts = {}
    for p in papers:
        counts[p["title"]] = counts.get(p["title"], 0) + 1
    for p in papers:
        if counts[p["title"]] < 2:
            continue
        tail = re.sub(r"^.*?(?:bcs|ntrca)[-_]*", "", p["source_file"], flags=re.I)
        tail = re.sub(r"^(?:\d+(?:st|nd|rd|th)?[-_]?)?", "", tail, flags=re.I)
        tail = norm(tail.replace("-", " ").replace("_", " "))
        if not tail:
            continue
        label = tail.title() if tail.isascii() else tail
        p["title"] = f"{p['title']} - {label}"
        if p["titleBn"] == p["title"].split(" - ")[0]:
            p["titleBn"] = f"{p['titleBn']} - {label}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--data", default=DATA)
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()

    papers = []
    papers += load_papers(os.path.join(args.data, "personal-study", "biddabari"), "NTRCA")
    papers += load_papers(os.path.join(args.data, "personal-study", "uttoron"), "BCS")
    disambiguate(papers)

    # Exam ids must be unique: the two folders have separate naming, but a clash would make
    # one paper unopenable, so a duplicate is suffixed rather than silently dropped.
    seen = {}
    for p in papers:
        base = p["id"]
        if base in seen:
            seen[base] += 1
            p["id"] = f"{base}-{seen[base]}"
        else:
            seen[base] = 1

    index = [{
        "id": p["id"],
        "title": p["title"],
        "titleBn": p["titleBn"],
        "category": p["category"],
        "group": p["group"],
        "year": p["year"],
        "durationMinutes": p["durationMinutes"],
        "negative": p["negative"],
        "count": len(p["questions"]),
        "subjects": p["subjects"],
    } for p in papers]

    doc = {
        "version": 1,
        "personalUseOnly": True,
        "exams": index,
        "questions": {p["id"]: p["questions"] for p in papers},
    }
    raw = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with gzip.open(args.out, "wb", compresslevel=9) as fh:
        fh.write(raw)

    total = sum(len(p["questions"]) for p in papers)
    explained = sum(1 for p in papers for q in p["questions"] if q["explain"])
    print(f"papers   {len(papers)}")
    print(f"questions {total}")
    print(f"explained {explained} ({100.0 * explained / max(total, 1):.0f}%)")
    print(f"json     {len(raw) / 1e6:.2f} MB")
    print(f"pack     {os.path.getsize(args.out) / 1e6:.2f} MB  -> {args.out}")
    if args.stats:
        for p in papers:
            print(f"  {p['category']:6} {p['id'][:52]:52} {len(p['questions']):4} q  {p['durationMinutes']}m")
    return 0


if __name__ == "__main__":
    sys.exit(main())
