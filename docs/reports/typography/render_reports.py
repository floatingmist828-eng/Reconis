"""Render both Word-exported PDFs for typography and page layout review."""
from pathlib import Path
import json
import pypdfium2 as pdfium

REPORTS = Path(__file__).resolve().parents[1]
for label, name in [('design', '系统设计报告'), ('summary', '系统实现总结报告')]:
    source = REPORTS / 'final' / f'Reconis-{name}.pdf'
    out = REPORTS / 'evidence/20261106-typography' / f'render-{label}'
    out.mkdir(parents=True, exist_ok=True)
    with pdfium.PdfDocument(str(source)) as doc:
        for number in range(len(doc)):
            page = doc[number]
            try:
                bitmap = page.render(scale=140 / 72)
                bitmap.to_pil().save(out / f'page-{number + 1}.png')
                bitmap.close()
            finally:
                page.close()
        (out / 'manifest.json').write_text(json.dumps({
            'pdf': str(source), 'pages': len(doc), 'dpi': 140,
        }, ensure_ascii=False, indent=2), encoding='utf8')
        print(label, len(doc), 'pages rendered', flush=True)
