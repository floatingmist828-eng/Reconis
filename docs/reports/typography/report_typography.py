"""Explicit Chinese/Latin type settings shared by the two course reports."""
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor


def child(parent, tag):
    node = parent.find(qn(tag))
    if node is None:
        node = OxmlElement(tag)
        parent.append(node)
    return node


def font_properties(rpr, latin, east_asia, size=None):
    fonts = child(rpr, 'w:rFonts')
    for key in list(fonts.attrib):
        if key.rsplit('}', 1)[-1].lower().endswith('theme'):
            del fonts.attrib[key]
    for key, value in [('ascii', latin), ('hAnsi', latin), ('eastAsia', east_asia), ('cs', latin)]:
        fonts.set(qn('w:' + key), value)
    language = child(rpr, 'w:lang')
    language.set(qn('w:val'), 'en-US')
    language.set(qn('w:eastAsia'), 'zh-CN')
    if size is not None:
        for tag in ['w:sz', 'w:szCs']:
            child(rpr, tag).set(qn('w:val'), str(round(size * 2)))


def set_fonts(font_owner, latin='Times New Roman', east_asia='宋体', size=11, bold=None):
    font_owner.font.name = latin
    font_owner.font.size = Pt(size)
    font_owner.font.color.rgb = RGBColor(0, 0, 0)
    if bold is not None:
        font_owner.font.bold = bold
        child(font_owner._element.get_or_add_rPr(), 'w:bCs').set(qn('w:val'), '1' if bold else '0')
    font_properties(font_owner._element.get_or_add_rPr(), latin, east_asia, size)


def normalize_template(document):
    """Remove inherited theme/locale drift while preserving intentional emphasis."""
    defaults = child(document.styles.element, 'w:docDefaults')
    rpr = child(child(defaults, 'w:rPrDefault'), 'w:rPr')
    font_properties(rpr, 'Times New Roman', '宋体', 11)
    language = child(document.settings.element, 'w:themeFontLang')
    language.set(qn('w:val'), 'en-US')
    language.set(qn('w:eastAsia'), 'zh-CN')
    for name in ('Caption', 'Table Grid'):
        if name in document.styles:
            set_fonts(document.styles[name], size=9.5, bold=False)
            document.styles[name].font.italic = False

    for style in document.styles:
        rpr = style.element.find(qn('w:rPr'))
        if rpr is None:
            continue
        fonts = rpr.find(qn('w:rFonts'))
        if fonts is not None:
            for key in list(fonts.attrib):
                if key.rsplit('}', 1)[-1].lower().endswith('theme'):
                    del fonts.attrib[key]
        size = rpr.find(qn('w:sz'))
        if size is not None:
            child(rpr, 'w:szCs').set(qn('w:val'), size.get(qn('w:val')))
        child(rpr, 'w:lang').set(qn('w:eastAsia'), 'zh-CN')
        if style.name.startswith('Heading') or style.name in ('Title', 'Subtitle'):
            for tag in ['w:spacing', 'w:kern', 'w:position', 'w:w']:
                for node in list(rpr.findall(qn(tag))):
                    rpr.remove(node)

    # Linked character styles are used when a heading is edited in Word.
    for name in ['Title', 'Subtitle', 'Heading 1', 'Heading 2', 'Heading 3', 'Heading 4']:
        if name not in document.styles:
            continue
        style = document.styles[name]
        link = style.element.find(qn('w:link'))
        if link is None:
            continue
        linked = next((s for s in document.styles if s.style_id == link.get(qn('w:val'))), None)
        if linked is not None:
            set_fonts(linked, 'Arial', '黑体', style.font.size.pt, bold=style.font.bold)
            linked.font.italic = False

    for rpr in document.element.iter(qn('w:rPr')):
        size = rpr.find(qn('w:sz'))
        if size is not None:
            child(rpr, 'w:szCs').set(qn('w:val'), size.get(qn('w:val')))
