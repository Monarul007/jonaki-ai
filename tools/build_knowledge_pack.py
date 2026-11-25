#!/usr/bin/env python3
"""
Builds the Jonaki AI knowledge pack from ShikkhaSathi study data.

The pack is side-loaded onto the phone (adb), never bundled in the APK and never
committed: every source folder is marked "personal study only, do not redistribute".

Usage:
  python tools/build_knowledge_pack.py --out knowledge.pack
  python tools/build_knowledge_pack.py --out knowledge.pack --stats
"""

import argparse
import gzip
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict

DATA = r"D:\ShikkhaSathi\data"

CHUNK_MAX = 1100          # characters per passage
CHUNK_MIN = 40
MAX_POSTINGS = 64         # per term (highest tf only)
MAX_DF_RATIO = 0.22       # drop terms in more than this share of chunks

# Lines repeated across pages (site headers in the OCR exports, page rules).
NOISE_RE = re.compile(
    r"^(?:-{2,}|={2,}|\s*)$"
    r"|হাজারো\s*প্রতিযোগী"
    r"|নিয়মিত\s*আপডেট\s*পেতে"
    r"|Andriod\s*App|iOS\s*App|Live\s*Written"
    r"|ফেসবুক\s*পেজ|গ্রুপে\s*জয়েন"
    r"|^Subject\s*Code|^বিষয়\s*কোড|^সময়\s*[:：]|^বিষয়\s*[:：]",
    re.IGNORECASE,
)

BENGALI = re.compile(r"[\u0980-\u09FF]")
TOKEN = re.compile(r"[0-9A-Za-z\u0980-\u09FF]+")
# Bengali suffixes glued to a word: strip a few to widen matching.
SUFFIXES = ("গুলো", "গুলি", "দের", "কে", "ের", "ে", "টি", "রা", "তে", "ের", "য়")

STOP = set("""
a an the is are was were be been being of in on at to for from by with without and or not no
what which who whom whose when where why how does do did done can could should would will
the this that these those it its as if then than so such into over under about after before
""".split())

# Bengali function words: they appear in nearly every question and paper, so they match
# everything and match nothing. Dropping them is what makes the search precise.
STOP |= set("""
না কি কী কে কেন কত কোথায় কখন কোন কোনটি এবং বা ও এর এই সেই একটি একটা এক একই হয় হবে ছিল ছিলো
থেকে জন্য সাথে সাথে করে করা করেন করবেন বলে বলা যায় যাবে পাওয়া পাওয়া যায় থাকে থাকবে তবে কিন্তু
আর তার তারপর পরে আগে উপর নিচে মধ্যে সব সকল অনেক কিছু এটি তা এটা ওটা যার যাকে যেমন তেমন হলে যদি
""".split())


def norm(text):
    return re.sub(r"\s+", " ", text or "").strip()


# The packs mix Bengali and ASCII digits ("৩৫তম" vs "35th"). Indexing both forms as ASCII
# is what lets a Bengali numeral query find an English-numbered paper.
DIGITS = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")


def ascii_digits(text):
    return text.translate(DIGITS)


def tokens(text):
    out = []
    for raw in TOKEN.findall(ascii_digits(text.lower())):
        if raw in STOP or len(raw) < 2:
            continue
        out.append(raw)
        # "35তম" -> also "35", so a question with only the number still matches the paper.
        m = re.match(r"^(\d+)\D+$", raw)
        if m:
            out.append(m.group(1))
        # Bengali: also index the stem so "পর্যায়ের" matches "পর্যায়"
        if BENGALI.match(raw):
            for suf in SUFFIXES:
                if len(raw) > len(suf) + 3 and raw.endswith(suf):
                    out.append(raw[: -len(suf)])
                    break
    return out


class Pack:
    def __init__(self):
        self.chunks = []
        self.sources = Counter()

    def add(self, source, title, text):
        text = norm(text)
        if len(text) < CHUNK_MIN:
            return
        self.chunks.append({"s": source, "t": norm(title)[:160], "x": text[:CHUNK_MAX]})
        self.sources[source] += 1


# ---------------------------------------------------------------- sources

def load_biddabari(pack):
    root = os.path.join(DATA, "personal-study", "biddabari")
    for name in sorted(os.listdir(root)):
        if not name.endswith(".json") or name == "index.json":
            continue
        path = os.path.join(root, name)
        try:
            doc = json.load(open(path, encoding="utf-8"))
        except Exception as e:
            print(f"  skip {name}: {e}")
            continue
        exam = doc.get("exam") or {}
        paper = exam.get("title") or name
        for q in doc.get("questions", []):
            opts = q.get("options") or []
            lines = [f"প্রশ্ন: {norm(q.get('question'))}"]
            if len(opts) == 4:
                lines.append("  ".join(f"{k}) {norm(v)}" for k, v in zip("কখগঘ", opts)))
            ans = q.get("answerText")
            if ans and q.get("correctIndex") is not None:
                lines.append(f"উত্তর: {norm(ans)}")
            pack.add("biddabari", paper, "\n".join(lines))


def load_uttoron(pack):
    root = os.path.join(DATA, "personal-study", "uttoron")
    for name in sorted(os.listdir(root)):
        if not name.endswith(".json") or name == "index.json":
            continue
        try:
            doc = json.load(open(os.path.join(root, name), encoding="utf-8"))
        except Exception as e:
            print(f"  skip {name}: {e}")
            continue
        exam = doc.get("exam") or {}
        paper = exam.get("title") or name
        for q in doc.get("questions", []):
            opts = q.get("options") or []
            lines = [f"প্রশ্ন: {norm(q.get('question'))}"]
            if len(opts) == 4:
                lines.append("  ".join(f"{k}) {norm(v)}" for k, v in zip("কখগঘ", opts)))
            idx = q.get("correctIndex")
            if isinstance(idx, int) and 0 <= idx < len(opts):
                lines.append(f"উত্তর: {norm(opts[idx])}")
            exp = norm(q.get("explanation"))
            if exp:
                lines.append(f"ব্যাখ্যা: {exp[:420]}")
            pack.add("uttoron", paper, "\n".join(lines))


SUBJECT_BN = {
    "bangla": "বাংলা",
    "bangladesh_affairs": "বাংলাদেশ বিষয়াবলি",
    "english": "ইংরেজি",
    "general_science": "সাধারণ বিজ্ঞান",
    "international_affairs": "আন্তর্জাতিক বিষয়াবলি",
    "math": "গণিত",
}


def paper_title_bn(name):
    """`35th-bangla-ocr` -> `৩৫তম বিসিএস · বাংলা` (the file names are all Latin)."""
    m = re.match(r"(\d+)(?:st|nd|rd|th)-(.+?)(?:-ocr)?$", os.path.splitext(name)[0])
    if not m:
        return name
    num, subj = m.group(1), m.group(2)
    subject = SUBJECT_BN.get(subj, subj.replace("_", " "))
    return f"{ascii_digits(num)}তম বিসিএস · {subject}"


def load_bpsc(pack):
    root = os.path.join(DATA, "personal-study", "bpsc-written")
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".txt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
            except Exception as e:
                print(f"  skip {name}: {e}")
                continue
            paper = paper_title_bn(name)
            for page in re.split(r"=====\s*PAGE\s+\d+\s*=====", text):
                keep = [ln for ln in page.splitlines() if not NOISE_RE.search(ln.strip())]
                body = norm("\n".join(keep))
                if len(body) < 120:
                    continue
                for i in range(0, len(body), CHUNK_MAX):
                    pack.add("bpsc-written", paper, body[i : i + CHUNK_MAX])


def load_chemistry(pack):
    path = os.path.join(
        DATA, "personal-study", "nctb-chemistry-9-10", "ocr", "book.normalized.ascii.txt"
    )
    text = open(path, encoding="utf-8", errors="replace").read()
    for n, page in enumerate(re.split(r"=====\s*PAGE\s+\d+\s*=====", text), 1):
        keep = [ln for ln in page.splitlines() if not NOISE_RE.search(ln.strip())]
        body = norm("\n".join(keep))
        if len(body) < 120:
            continue
        for i in range(0, len(body), CHUNK_MAX):
            pack.add("nctb-chemistry", f"রসায়ন ৯-১০ · পৃষ্ঠা {n}", body[i : i + CHUNK_MAX])


# ---------------------------------------------------------------- index

def build_index(chunks):
    postings = defaultdict(list)
    # A passage's own subject words (title) count extra, so "35th BCS Bangla" prefers the
    # paper it names over an unrelated MCQ that happens to contain the word "বাংলা".
    for i, c in enumerate(chunks):
        body = Counter(tokens(c["x"]))
        head = Counter(tokens(c["t"]))
        for term, tf in body.items():
            postings[term].append((i, tf + head.get(term, 0) * 3))

    n = len(chunks)
    max_df = int(n * MAX_DF_RATIO)
    kept = {}
    for term, plist in postings.items():
        if len(plist) > max_df:
            continue
        plist.sort(key=lambda p: -p[1])
        kept[term] = plist[:MAX_POSTINGS]

    avgdl = sum(len(tokens(c["x"])) for c in chunks) / max(n, 1)
    return kept, avgdl


def encode(kept):
    """term -> "doc:tf,doc:tf" in base36, the most compact readable form."""
    out = {}
    for term, plist in kept.items():
        out[term] = ",".join(f"{d:x}:{tf:x}" for d, tf in plist)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="knowledge.pack")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()

    pack = Pack()
    for fn, label in (
        (load_biddabari, "biddabari"),
        (load_uttoron, "uttoron"),
        (load_bpsc, "bpsc-written"),
        (load_chemistry, "nctb-chemistry"),
    ):
        before = len(pack.chunks)
        fn(pack)
        print(f"{label:16} +{len(pack.chunks) - before:5} chunks")

    # Identical passages (papers re-scraped, repeated headers) carry no extra value.
    seen, unique = set(), []
    for c in pack.chunks:
        key = (c["s"], c["x"][:200])
        if key in seen:
            continue
        seen.add(key)
        unique.append(c)
    print(f"deduped {len(pack.chunks) - len(unique)} duplicate chunks")
    pack.chunks = unique

    kept, avgdl = build_index(pack.chunks)
    index = encode(kept)
    doc = {
        "version": 1,
        "avgdl": round(avgdl, 2),
        "n": len(pack.chunks),
        "sources": sorted({c["s"] for c in pack.chunks}),
        "chunks": pack.chunks,
        "index": index,
    }
    raw = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with gzip.open(args.out, "wb", compresslevel=9) as f:
        f.write(raw)

    text_bytes = sum(len(c["x"]) for c in pack.chunks)
    print()
    print(f"chunks   {len(pack.chunks)}")
    print(f"terms    {len(index)}")
    print(f"postings {sum(len(v) for v in index.values())}")
    print(f"text     {text_bytes / 1e6:.2f} M chars")
    print(f"json     {len(raw) / 1e6:.2f} MB")
    print(f"pack     {os.path.getsize(args.out) / 1e6:.2f} MB  -> {args.out}")
    if args.stats:
        for s in doc["sources"]:
            print(f"  {s:16} {pack.sources[s]:5} chunks")


if __name__ == "__main__":
    sys.exit(main())
