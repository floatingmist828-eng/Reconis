"""Check the submitted artifacts; visual page review remains a separate step."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import zipfile
from collections import Counter

from docx import Document
import pdfplumber
from pypdf import PdfReader

B = Path(__file__).resolve().parent
REPORT_ROOT = B.parent
PROJECT_ROOT = next((parent for parent in REPORT_ROOT.parents
                     if (parent / 'frontend/package.json').is_file()),
                    REPORT_ROOT.parent / 'Reconis')
BACKUP = PROJECT_ROOT.parent / 'Reconis-更新前备份-20261105-173f1dd.zip'
SOURCE = B.parent / 'implementation-report.md'
DOCX = B.parent / 'final/Reconis-系统实现总结报告.docx'
PDF = DOCX.with_suffix('.pdf')
EVIDENCE = REPORT_ROOT / 'evidence/20261106-language-edit'
parser = argparse.ArgumentParser()
parser.add_argument('--evidence-dir', type=Path, default=EVIDENCE)
EVIDENCE = parser.parse_args().evidence_dir
EVIDENCE.mkdir(parents=True, exist_ok=True)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def norm(text):
    return re.sub(r'\s+', '', text)

issues = []
source = SOURCE.read_text(encoding='utf8')
figures = re.findall(r'^!\[(图\d+-\d+)[^\]]*\]\(([^)]+)\)', source, re.M)
for label, rel in figures:
    if not (B.parent / rel).is_file():
        issues.append('Missing figure: ' + label)
for label, count in Counter(x[0] for x in figures).items():
    if count != 1:
        issues.append('Duplicate figure: ' + label)
doc = Document(DOCX)
reader = PdfReader(PDF)
with pdfplumber.open(PDF) as pdf:
    pages = [(page.extract_text() or '') for page in pdf.pages]
    body_start = next(i for i, text in enumerate(pages)
                      if '1 项目背景与最终范围' in text and not re.search(r'项目背景与最终范围\s*\.{3}', text))
    for i, text in enumerate(pages[body_start:], 1):
        if text.splitlines()[-1].strip() != str(i):
            issues.append(f'Body footer mismatch: PDF {body_start + i}')
    empty = [i+1 for i, text in enumerate(pages) if len(norm(text)) < 10]
    if empty:
        issues.append('Empty PDF pages: ' + str(empty))
    toc_entries = []
    for text in pages[2:body_start]:
        for line in text.splitlines():
            match = re.match(r'(.+?)\s*\.{3,}\s*(\d+)\s*$', line)
            if match:
                title, page = match[1].strip(), int(match[2])
                target = pages[body_start+page-1] if body_start+page-1 < len(pages) else ''
                ok = any(norm(line) == norm(title) for line in target.splitlines())
                toc_entries.append({'title': title, 'page': page, 'matches': ok})
                if not ok:
                    issues.append('TOC target mismatch: ' + title)
    if len(toc_entries) < 60:
        issues.append('Unexpectedly few TOC entries: ' + str(len(toc_entries)))
    visible_captions = []
    for i, text in enumerate(pages[body_start:], body_start+1):
        for line in text.splitlines():
            if re.match(r'^图\s*\d+-\d+\s+', line):
                visible_captions.append({'page': i, 'text': line})

font_info = {}
for page in reader.pages:
    for name, ref in page['/Resources'].get('/Font', {}).items():
        font = ref.get_object()
        base = str(font.get('/BaseFont', name))
        fonts = font.get('/DescendantFonts', [font])
        embedded = False
        for child in fonts:
            desc = child.get_object().get('/FontDescriptor')
            if desc:
                desc = desc.get_object()
                embedded |= any(key in desc for key in ['/FontFile', '/FontFile2', '/FontFile3'])
        font_info[base] = embedded
unembedded = [name for name, embedded in font_info.items() if not embedded]
if unembedded:
    issues.append('Unembedded PDF fonts: ' + str(unembedded))

historical_hashes = json.loads((REPORT_ROOT/'evidence/20261104/protected-design-hashes.json').read_text(encoding='utf-8-sig'))
protected = {
    'verificationScope': 'Read archived initial-design entries directly from the existing ZIP and compare with the recorded November 4 hashes. No current external initial-design file is assumed unchanged.',
    'archive': str(BACKUP),
    'entries': [],
}
if not BACKUP.is_file():
    protected['status'] = 'ARCHIVE_UNAVAILABLE'
    protected['note'] = ('Local backup is not distributed with the repository. '
                         'This checkout cannot repeat the archived initial-design check; '
                         'the dated result remains in evidence/20261106/initial-design-preservation.json.')
else:
    protected['archiveSha256'] = sha(BACKUP)
    with zipfile.ZipFile(BACKUP) as archive:
        for entry in historical_hashes:
            name = entry['Path']
            observed = hashlib.sha256(archive.read(name)).hexdigest().upper()
            restored_path = REPORT_ROOT / Path(name).relative_to('docs/reports')
            item = {
                'archiveEntry': name,
                'expectedSha256': entry['Hash'],
                'archivedSha256': observed,
                'archiveMatchesHistoricalHash': observed == entry['Hash'],
                'standaloneRestoredCopyPresent': restored_path.exists(),
                'originalRepoPathPresent': (PROJECT_ROOT / name).exists(),
            }
            protected['entries'].append(item)
            if not item['archiveMatchesHistoricalHash']:
                issues.append('Archived initial-design hash mismatch: ' + name)
    protected['status'] = 'ARCHIVED_BASELINE_VERIFIED'
    protected['currentDesignScope'] = ('The initial design report was restored for language editing '
                                        'after the user expanded the scope to both reports. '
                                        'Current design artifacts are checked separately in '
                                        'evidence/20261106-design-language-edit/.')

result = {
    'source': {'path': str(SOURCE), 'sha256': sha(SOURCE)},
    'docx': {'path': str(DOCX), 'sha256': sha(DOCX), 'tables': len(doc.tables),
             'images': len(doc.inline_shapes), 'sections': len(doc.sections)},
    'pdf': {'path': str(PDF), 'sha256': sha(PDF), 'pages': len(pages),
            'bodyPages': len(pages)-body_start, 'embeddedFonts': font_info},
    'figureCount': len(figures), 'tocEntries': toc_entries,
    'initialDesign': protected, 'issues': issues,
    'visualReview': 'See evidence/20261106-language-edit/visual-qa-*.md and render-final/page-*.png; automated checks do not assess visual quality.'
}
(EVIDENCE/'final-artifact-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
print(json.dumps({'pages': len(pages), 'bodyPages': len(pages)-body_start,
                  'tocEntries': len(toc_entries), 'figures': len(figures),
                  'tables': len(doc.tables), 'issues': issues}, ensure_ascii=False))
if issues:
    raise SystemExit(1)
