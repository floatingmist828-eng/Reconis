"""Audit both submitted reports against the shared typography rules.

Reads Word styles and rendered PDF text independently. Raster image lettering is
outside this check; figures still require visual review at their printed size.
"""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile

from docx import Document
from lxml import etree
import pdfplumber


REPORTS = Path(__file__).resolve().parents[1]
EVIDENCE = REPORTS / 'evidence/20261106-typography'
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
W = '{' + NS['w'] + '}'
HEADINGS = {'Heading 1': 16, 'Heading 2': 13, 'Heading 3': 11.5, 'Heading 4': 11}
THEMES = {'asciiTheme', 'hAnsiTheme', 'eastAsiaTheme', 'cstheme', 'csTheme'}


def norm(text):
    return re.sub(r'\s+', '', text or '')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def values(rpr):
    result = {}
    if rpr is None:
        return result
    for child in rpr:
        name = etree.QName(child).localname
        if name in ('rFonts', 'lang'):
            result.update({name + '.' + etree.QName(k).localname: v for k, v in child.attrib.items()})
        elif name in ('sz', 'szCs', 'b', 'bCs', 'i', 'iCs'):
            result[name] = child.get(W + 'val', '1')
    return result


def styled(name):
    m = re.fullmatch(r'heading\s*(\d+)', name, re.I)
    return 'Heading ' + m[1] if m else name


def locate(text, pages, expected):
    """Locate complete text including titles wrapping over multiple PDF lines."""
    needle = norm(text)
    candidates = []
    for number, (flat, chars) in enumerate(pages, 1):
        at = flat.find(needle)
        while at >= 0:
            selected = chars[at:at+len(needle)]
            error = sum(abs(float(c['size']) - expected) for c in selected) / max(1, len(selected))
            candidates.append((error, number, selected))
            at = flat.find(needle, at + 1)
    if not candidates:
        return None
    return min(candidates, key=lambda x: x[0])


def font_base(name):
    return name.split('+', 1)[-1]


def audit(label):
    docx = REPORTS / ('final/Reconis-' + label + '.docx')
    pdf = docx.with_suffix('.pdf')
    doc = Document(docx)
    problems = []
    with zipfile.ZipFile(docx) as package:
        styles_xml = etree.fromstring(package.read('word/styles.xml'))
        settings_xml = etree.fromstring(package.read('word/settings.xml'))
        defaults = values(styles_xml.find('w:docDefaults/w:rPrDefault/w:rPr', NS))
        styles = {s.get(W + 'styleId'): s for s in styles_xml.findall('w:style', NS)}
        used = set()
        parts = []
        for name in package.namelist():
            if re.fullmatch(r'word/(document|header\d+|footer\d+)\.xml', name):
                root = etree.fromstring(package.read(name))
                parts.append(root)
                used.update(e.get(W + 'val') for e in root.xpath('//w:pStyle|//w:rStyle|//w:tblStyle', namespaces=NS))
        used.update(s.get(W + 'styleId') for s in styles_xml.findall('w:style', NS) if s.get(W + 'default') == '1')

        def effective(sid, seen=None):
            seen = set() if seen is None else seen
            if sid in seen or sid not in styles:
                return dict(defaults)
            seen.add(sid)
            element = styles[sid]
            parent = element.find('w:basedOn', NS)
            found = effective(parent.get(W + 'val'), seen) if parent is not None else dict(defaults)
            found.update(values(element.find('w:rPr', NS)))
            return found

        style_checks = []
        for sid in sorted(used):
            if sid not in styles:
                problems.append('Referenced style missing: ' + sid)
                continue
            element = styles[sid]
            sn = element.find('w:name', NS).get(W + 'val')
            props = effective(sid)
            themes = [k for k in props if k.startswith('rFonts.') and k.split('.', 1)[1] in THEMES]
            matches = props.get('sz') == props.get('szCs') and props.get('sz') is not None
            style_checks.append({'id': sid, 'name': sn, 'effectiveProperties': props,
                                 'themeFontReferences': themes, 'sizeAndComplexSizeMatch': matches})
            if themes:
                problems.append('Used style has effective theme font references: ' + sn)
            if not matches:
                problems.append('Used style sz/szCs mismatch: ' + sn)
            if styled(sn) in HEADINGS and float(props.get('sz', 0)) / 2 != HEADINGS[styled(sn)]:
                problems.append('Heading style size differs from shared scale: ' + sn)

        lang = settings_xml.find('w:themeFontLang', NS)
        theme_east = lang.get(W + 'eastAsia') if lang is not None else None
        if defaults.get('lang.eastAsia') != 'zh-CN':
            problems.append('docDefaults East Asian language is not zh-CN')
        if theme_east != 'zh-CN':
            problems.append('themeFontLang East Asian language is not zh-CN')
        run_overrides = []
        for root in parts:
            for run in root.xpath('//w:r[w:t]', namespaces=NS):
                props = values(run.find('w:rPr', NS))
                themes = [k for k in props if k.startswith('rFonts.') and k.split('.', 1)[1] in THEMES]
                mismatch = 'sz' in props and 'szCs' in props and props['sz'] != props['szCs']
                if themes or mismatch:
                    run_overrides.append({'text': ''.join(run.xpath('./w:t/text()', namespaces=NS)),
                                          'properties': props})
        if run_overrides:
            problems.append('Text runs retain theme fonts or conflicting sz/szCs')

    with pdfplumber.open(pdf) as rendered:
        pages = []
        distribution = Counter()
        for page in rendered.pages:
            # PDF text only. No OCR is performed on embedded figure images.
            chars = [c for c in page.chars if norm(c.get('text'))]
            expanded = []
            for char in chars:
                expanded.extend(dict(char, text=letter) for letter in norm(char['text']))
            pages.append((''.join(c['text'] for c in expanded), expanded))
            distribution.update((font_base(c['fontname']), round(float(c['size']), 2)) for c in expanded)
        page_count = len(pages)

    heading_checks, caption_checks, special_checks = [], [], []
    for paragraph in doc.paragraphs:
        text = paragraph.text.strip()
        name = styled(paragraph.style.name)
        if not text:
            continue
        if name in HEADINGS:
            expected = HEADINGS[name]
            collection = heading_checks
        elif 'caption' in name.lower():
            props = effective(paragraph.style.style_id)
            expected = float(props['sz']) / 2
            collection = caption_checks
        elif name in ('Title', 'Subtitle', 'Report Front Heading'):
            props = effective(paragraph.style.style_id)
            expected = float(props['sz']) / 2
            collection = special_checks
        else:
            continue
        found = locate(text, pages, expected)
        item = {'text': text, 'style': name, 'expectedPoints': expected, 'located': found is not None}
        if not found:
            problems.append('PDF text not located: ' + text)
        else:
            _, page, chars = found
            item.update({'page': page, 'fontSizes': sorted({round(float(c['size']), 2) for c in chars}),
                         'fonts': sorted({font_base(c['fontname']) for c in chars})})
            if any(abs(float(c['size']) - expected) > .15 for c in chars):
                problems.append('PDF paragraph size differs: ' + text)
            if name in HEADINGS:
                wrong = []
                for c in chars:
                    name_actual = font_base(c['fontname'])
                    if ('\u3400' <= c['text'] <= '\u9fff' and 'SimHei' not in name_actual
                            or c['text'].isascii() and c['text'].isalnum() and not name_actual.startswith('Arial')):
                        wrong.append({'char': c['text'], 'font': name_actual, 'points': c['size']})
                item['fontMismatches'] = wrong
                if wrong:
                    problems.append('PDF heading font differs from Chinese SimHei/Latin Arial: ' + text)
            if '规范化分析示例' in text:
                item['characters'] = [{'char': c['text'], 'unicode': 'U+%04X' % ord(c['text']),
                                       'font': font_base(c['fontname']), 'points': round(float(c['size']), 2)} for c in chars]
        collection.append(item)
    prohibited = [font for font, _ in distribution if re.search(r'MS[- ]?(Gothic|Mincho)', font, re.I)]
    if prohibited:
        problems.append('Unexpected Japanese fallback fonts in PDF: ' + ', '.join(sorted(set(prohibited))))

    return {'report': label, 'docxSha256': sha(docx), 'pdfSha256': sha(pdf), 'pdfPages': page_count,
            'docDefaults': defaults, 'themeEastAsianLanguage': theme_east,
            'usedStyleChecks': style_checks, 'runOverrideIssues': run_overrides,
            'headings': heading_checks, 'captions': caption_checks, 'frontMatter': special_checks,
            'pdfTextFontDistribution': [{'font': f, 'points': s, 'characters': n}
                                        for (f, s), n in sorted(distribution.items())],
            'issues': problems}


def main():
    result = {'scope': 'Native Word text and rendered PDF text; figures and screenshots are inspected visually.',
              'headingScalePoints': HEADINGS,
              'reports': [audit(name) for name in ('系统设计报告', '系统实现总结报告')]}
    result['passed'] = all(not report['issues'] for report in result['reports'])
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'typography-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': result['passed'], 'reports': [
        {'report': item['report'], 'pages': item['pdfPages'], 'headings': len(item['headings']),
         'captions': len(item['captions']), 'issues': item['issues']} for item in result['reports']]}, ensure_ascii=False))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
