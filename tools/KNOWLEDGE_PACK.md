# Study data pipeline (personal use)

Builds the on-device knowledge pack from the ShikkhaSathi study folders on this PC.

**The pack is never committed and never shipped in the APK.** Every source folder is
marked *personal study only, do not redistribute* (Biddabari and Uttoron are commercial
coaching platforms, BPSC/Question Archive own their question wording). The pack is built
here, copied to the phone, and imported there. `*.pack` is git-ignored.

## Build

```bash
python tools/build_knowledge_pack.py --out knowledge.pack --stats
```

Sources (only these, as chosen):

| Source | Included |
|---|---|
| `personal-study/biddabari` | all papers (JSON) |
| `personal-study/bpsc-written` | all OCR `.txt` (PDFs excluded) |
| `personal-study/nctb-chemistry-9-10/ocr/book.normalized.ascii.txt` | the whole textbook |
| `personal-study/uttoron` | all question JSON |

Current output: **9 731 passages · 3.31 M characters · 2.5 MB gzipped**.

Each passage is a question with its four options and answer, or a ~1 100-character
window of a book/paper. Text is tokenised with the same rules the phone uses (Latin
words, Bengali words, plus a Bengali stem so "পর্যায়ের" matches "পর্যায়").

## Put it on the phone

1. Copy `knowledge.pack` to the phone (e.g. into `Download/jonaki-study.pack`).
2. In Jonaki AI: **drawer → Settings → Storage → Add study files**.
3. Pick the file. The row changes to **Study files** with the passage count.

`adb push <pack> /sdcard/Download/jonaki-study.pack` works for step 1. (A release APK
is not debuggable, so `run-as` cannot be used to drop the file into app storage.)

## How it is used

On every typed question the app searches the pack (BM25 over pre-computed postings),
takes the best 2–3 passages and adds them to the prompt under a short instruction to
use them and to credit the study files, not a website. A `Checked your study files`
chip appears on the answer. Voice answers skip this, and so do picture answers.

Nothing is trained. The model is unchanged; the passages are simply put in front of it,
which is why this works inside the phone's 4k/8k token window.

## Removing it

**Settings → Storage → Study files → Remove.** Deleting the app also removes it unless
"Keep app data" is ticked on uninstall.

---

# Exam pack (exam mode)

The knowledge pack is prose; exam mode needs structure. A second pack carries the same questions
as exam papers — four options, the key, the subject, and the paper's own explanation where it
exists.

```bash
python tools/build_exam_pack.py            # -> app/src/main/assets/exam.pack
python tools/build_exam_pack.py --stats    # list every paper
```

Sources: the same `personal-study/biddabari` (NTRCA) and `personal-study/uttoron` (BCS) folders.

Current output: **81 papers · 9,030 questions · 5,027 with an explanation (56%) · 1.30 MB gzipped**.

Format (`version 1`):

```json
{"version":1,"personalUseOnly":true,
 "exams":[{"id","title","titleBn","category","group","year",
           "durationMinutes","negative","count","subjects":[{"name","count"}]}],
 "questions":{"<examId>":[{"n","subject","text","options":[4],"answer":idx,"explain":str|null}]}}
```

Field notes:

- Only items with exactly four non-blank options and a `correctIndex` in 0..3 are kept — the same
  rule the knowledge pack uses, so a section instruction never becomes a question.
- `durationMinutes` comes from the paper when it records one, else the conventional time for the
  examination (NTRCA 60, BCS 120). Those two are the real durations; the rest are a minute per
  question.
- `negative` is the paper's own `negativeMarking` when present (BCS 0.5, others 0).
- `explain` is the source's own explanation with scraped page furniture cut off. 46 of them
  carried the coaching site's promo block followed by its analytics and Cloudflare bootstrapping as
  literal script text (~6 KB each); shown under an answer in review that is a wall of JavaScript,
  so `clean_text()` cuts at the first marker and the result is verified to be clean.
- Titles are disambiguated: the 48th BCS is three files all titled "48th BCS", so they become
  "… - Special Health General Part", "… - Medical Part", "… - Dental Part".

If `exam.pack` is absent the app rebuilds papers from `knowledge.pack`'s answer-key passages
(`প্রশ্ন: … ক) … উত্তর: …`) instead, so exam mode still works on a phone that only has the older
pack. That fallback has no subjects or durations, so it is a second-best path.

**Never commit `exam.pack`** — `*.pack` is git-ignored, and every source is personal-use only.

