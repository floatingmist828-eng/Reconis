"""Check design-report source consistency and PDF structure after Word export.

Page PNG review is recorded separately; these checks do not assess visual quality.
"""
from collections import Counter
import argparse
from io import BytesIO
from pathlib import Path
import hashlib
import json
import re
import zipfile

from docx import Document
import pdfplumber
from pypdf import PdfReader

ASSETS = Path(__file__).resolve().parent
REPORTS = ASSETS.parent
PROJECT = next((p for p in REPORTS.parents if (p / "frontend/package.json").is_file()),
               REPORTS.parent / "Reconis")
ARCHIVE = PROJECT.parent / "Reconis-更新前备份-20261105-173f1dd.zip"
SOURCE = REPORTS / "system-design.md"
DOCX = REPORTS / "final/Reconis-系统设计报告.docx"
PDF = DOCX.with_suffix(".pdf")
EVIDENCE = REPORTS / "evidence/20261106-design-language-edit"
parser = argparse.ArgumentParser()
parser.add_argument('--evidence-dir', type=Path, default=EVIDENCE)
EVIDENCE = parser.parse_args().evidence_dir


def norm(text):
    return re.sub(r"\s+", "", text or "")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def plain(text):
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    return text.replace("**", "").replace("`", "")


issues = []
source = SOURCE.read_text(encoding="utf-8-sig")
doc = Document(DOCX)
reader = PdfReader(PDF)
headings = re.findall(r"^#{1,4} .+$", source, re.M)
word_paragraphs = [p.text for p in doc.paragraphs if p.text.strip()
                   and not p.style.name.lower().startswith("toc")]
word_tables = [[[c.text for c in row.cells] for row in t.rows] for t in doc.tables]
word_content = "\n".join(word_paragraphs + [c for t in word_tables for row in t for c in row])

# Every non-diagram source paragraph and table cell must reach the Word artifact.
source_items = []
inside_code = False
for line in source.splitlines():
    if line.startswith("```"):
        inside_code = not inside_code
        continue
    if inside_code or not line.strip():
        continue
    if line.startswith("|"):
        cells = [v.strip() for v in line.strip().strip("|").split("|")]
        if not all(re.fullmatch(r":?-+:?", v) for v in cells):
            source_items.extend(plain(v) for v in cells)
    else:
        source_items.append(plain(re.sub(r"^#{1,4}\s+|^-\s+", "", line)))
for item in source_items:
    if norm(item) not in norm(word_content):
        issues.append("Source content missing from Word: " + item[:100])

if len(doc.tables) != 22:
    issues.append(f"Expected 22 tables, found {len(doc.tables)}")
if len(doc.inline_shapes) != 4:
    issues.append(f"Expected 4 images, found {len(doc.inline_shapes)}")

dictionary = json.loads((ASSETS / "schema-fields.json").read_text(encoding="utf-8"))
dictionary_matches = len(dictionary) == 15 and len(word_tables[7:]) == 15
for expected, actual in zip(dictionary, word_tables[7:]):
    if actual[1:] != expected["columns"]:
        dictionary_matches = False
        issues.append("Frozen dictionary differs from Word: " + expected["name"])
if not dictionary_matches and not any("dictionary differs" in s for s in issues):
    issues.append("Frozen dictionary table count mismatch")

with pdfplumber.open(PDF) as pdf:
    pages = [p.extract_text() or "" for p in pdf.pages]
    body_texts = []
    pdf_rows = Counter()
    for number, (page, text) in enumerate(zip(pdf.pages, pages), 1):
        lines = [line for line in text.splitlines()
                 if norm(line) != norm("Reconis · 系统设计报告")
                 and not re.fullmatch(r"第\s*\d+\s*页", line.strip())]
        body_texts.append("\n".join(lines))
        if len(norm("".join(lines))) < 10:
            issues.append(f"Empty PDF page: {number}")
        if not text.splitlines() or norm(text.splitlines()[-1]) != f"第{number}页":
            issues.append(f"Footer mismatch on PDF page {number}")
        for table in page.extract_tables():
            for row in table:
                # Word's thin borders can produce extra empty grid cells.
                values = tuple(norm(cell) for cell in row if norm(cell))
                if values:
                    pdf_rows[values] += 1

pdf_body = norm("\n".join(body_texts))
for paragraph in word_paragraphs:
    if norm(paragraph) not in pdf_body:
        issues.append("Word paragraph missing from PDF text: " + paragraph[:100])
expected_rows = Counter(tuple(norm(cell) for cell in row if norm(cell))
                        for table in word_tables for row in table)
missing_rows = expected_rows - pdf_rows
for row, count in missing_rows.items():
    issues.append(f"Word table row missing from PDF ({count}): " + " | ".join(row)[:160])

toc_entries = []
for text in pages[:2]:
    for line in text.splitlines():
        match = re.match(r"(.+?)\s*\.{3,}\s*(\d+)\s*$", line)
        if not match:
            continue
        title, number = match[1].strip(), int(match[2])
        valid = (0 < number <= len(pages) and
                 any(norm(s) == norm(title) for s in pages[number - 1].splitlines()))
        toc_entries.append({"title": title, "page": number, "matches": valid})
        if not valid:
            issues.append("TOC target mismatch: " + title)
if len(toc_entries) != 10:
    issues.append(f"Expected 10 TOC entries, found {len(toc_entries)}")

fonts = {}
for page in reader.pages:
    for name, ref in page["/Resources"].get("/Font", {}).items():
        font = ref.get_object()
        base = str(font.get("/BaseFont", name))
        embedded = False
        for child in font.get("/DescendantFonts", [font]):
            descriptor = child.get_object().get("/FontDescriptor")
            if descriptor:
                descriptor = descriptor.get_object()
                embedded |= any(key in descriptor for key in ("/FontFile", "/FontFile2", "/FontFile3"))
        fonts[base] = embedded
if any(not embedded for embedded in fonts.values()):
    issues.append("Unembedded PDF font: " + ", ".join(n for n, ok in fonts.items() if not ok))

provenance = json.loads((ASSETS / "source-provenance.json").read_text(encoding="utf-8"))
asset_checks = []
for item in provenance["assets"]:
    valid = sha(ASSETS / item["path"]) == item["sha256"].upper()
    asset_checks.append({"path": item["path"], "matches": valid})
    if not valid:
        issues.append("Original diagram hash changed: " + item["path"])

archive_check = {"available": ARCHIVE.is_file()}
if ARCHIVE.is_file():
    with zipfile.ZipFile(ARCHIVE) as archive:
        old_source = archive.read("docs/reports/system-design.md").decode("utf-8-sig").replace("\r", "")
        old_doc = Document(BytesIO(archive.read("docs/reports/final/Reconis-系统设计报告.docx")))
        archive_check.update({
            "archiveSha256": sha(ARCHIVE),
            "archiveHashMatches": sha(ARCHIVE) == provenance["archiveSha256"].upper(),
            "headingsUnchanged": headings == re.findall(r"^#{1,4} .+$", old_source, re.M),
            "codeBlocksUnchanged": re.findall(r"```.*?```", source, re.S) == re.findall(r"```.*?```", old_source, re.S),
            "dictionaryUnchanged": word_tables[7:] == [[[c.text for c in r.cells] for r in t.rows] for t in old_doc.tables[7:]],
        })
    for key in ("archiveHashMatches", "headingsUnchanged", "codeBlocksUnchanged", "dictionaryUnchanged"):
        if not archive_check[key]:
            issues.append("Archived design consistency failed: " + key)

cross_reference = "流程实例与验证结果见《系统实现总结报告》第 6、9 章。"
if any(norm(cross_reference) not in norm(text) for text in (source, word_content, pdf_body)):
    issues.append("Updated implementation-report chapter 6/9 reference missing")

summary_hashes = {}
for extension, expected in {
    "docx": "3258EF06E575D1714422922C50F529EB41A920F43DDB4029FBE5B4DF1F4D802C",
    "pdf": "26D092CEE5D00A0FB980DDB38ADE4DAF1C114C87CD4C22F406392BD35C007081",
}.items():
    path = REPORTS / ("final/Reconis-系统实现总结报告." + extension)
    observed = sha(path)
    summary_hashes[extension] = {"sha256": observed, "matchesLanguageEditSnapshot": observed == expected}

result = {
    "source": {"path": str(SOURCE), "sha256": sha(SOURCE), "checkedTextItems": len(source_items)},
    "docx": {"path": str(DOCX), "sha256": sha(DOCX), "tables": len(doc.tables), "images": len(doc.inline_shapes)},
    "pdf": {"path": str(PDF), "sha256": sha(PDF), "pages": len(pages), "embeddedFonts": fonts},
    "contentChecks": {"wordParagraphsCheckedInPdf": len(word_paragraphs),
                      "wordTableRowsCheckedInPdf": sum(expected_rows.values()),
                      "missingTableRows": sum(missing_rows.values()), "frozenDictionaryMatches": dictionary_matches},
    "tocEntries": toc_entries,
    "diagrams": asset_checks,
    "archive": archive_check,
    "summaryArtifacts": summary_hashes,
    "issues": issues,
    "visualReview": "See the separate page PNG review record in this evidence folder.",
}
EVIDENCE.mkdir(parents=True, exist_ok=True)
(EVIDENCE / "final-artifact-check.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"pages": len(pages), "tocEntries": len(toc_entries), "tables": len(doc.tables),
                  "images": len(doc.inline_shapes), "issues": issues}, ensure_ascii=False))
if issues:
    raise SystemExit(1)
