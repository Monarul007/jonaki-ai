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
