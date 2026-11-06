#!/usr/bin/env python3
"""Build the final Reconis report from Markdown with editable Word content.

Use the bundled Python runtime. The default input is ../implementation-report.md
and the default output is ../final/Reconis-系统实现总结报告.docx. Explicit
--input/--output arguments allow a smoke document without touching the report.
Word must update the TOC and PAGE fields before the final PDF export.
"""
from __future__ import annotations

import argparse
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote

from docx import Document
from docx.enum.section import WD_SECTION_START
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.opc.constants import RELATIONSHIP_TYPE as RT
from docx.shared import Cm, Pt, RGBColor
from PIL import Image


BASE = Path(__file__).resolve().parent
DEFAULT_INPUT = BASE.parent / "implementation-report.md"
DEFAULT_OUTPUT = BASE.parent / "final" / "Reconis-系统实现总结报告.docx"
AUTHORS = "王梓豪、吕世英、朱晓天"
MEMBERS = (("王梓豪", "24373342"), ("吕世英", "24373352"), ("朱晓天", "24373340"))
TABLE_WIDTH_CM = 16.4
MAX_IMAGE_HEIGHT_CM = 21.8
BLACK = RGBColor(0, 0, 0)
IMAGE_RE = re.compile(r"^!\[([^\]]*)\]\((.+)\)\s*$")
HEADING_RE = re.compile(r"^(#{1,6})\s*(\S.*)$")
LIST_RE = re.compile(r"^(\s*)(?:([-+*])\s+|(\d+)[.)]\s+)(.*)$")
CAPTION_RE = re.compile(r"^[表图]\s*[\dA-Z一二三四五六七八九十]+[-－.]\d+(?:\s|[：:])")


@dataclass
class Block:
    kind: str
    content: object
    line: int
    level: int = 0


def xml(parent, tag, **attributes):
    element = OxmlElement(tag)
    for key, value in attributes.items():
        element.set(qn(key.replace("__", ":")), str(value))
    parent.append(element)
    return element


def set_fonts(font_owner, latin="Times New Roman", east_asia="宋体", size=11, bold=None):
    font_owner.font.name = latin
    font_owner.font.size = Pt(size)
    font_owner.font.color.rgb = BLACK
    if bold is not None:
        font_owner.font.bold = bold
    rpr = font_owner._element.get_or_add_rPr()
    fonts = rpr.find(qn("w:rFonts"))
    if fonts is None:
        fonts = xml(rpr, "w:rFonts")
    for theme in ("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme"):
        fonts.attrib.pop(qn("w:" + theme), None)
    for key, value in (("ascii", latin), ("hAnsi", latin), ("eastAsia", east_asia), ("cs", latin)):
        fonts.set(qn("w:" + key), value)
    language = rpr.find(qn("w:lang"))
    if language is None:
        language = xml(rpr, "w:lang")
    language.set(qn("w:val"), "en-US")
    language.set(qn("w:eastAsia"), "zh-CN")


def plain(text):
    return re.sub(r"[`*_]", "", text).strip()


def display_units(text):
    """Approximate mixed Chinese/Latin width, used only for layout allocation."""
    return sum(2 if ord(char) > 0x2FFF else 1 for char in plain(text))


def table_cells(line):
    text = line.strip()
    if text.startswith("|"):
        text = text[1:]
    if text.endswith("|") and not text.endswith("\\|"):
        text = text[:-1]
    cells, current, ticks, escaped = [], [], False, False
    for char in text:
        if escaped:
            current.extend(("\\", char))
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "`":
            ticks = not ticks
            current.append(char)
        elif char == "|" and not ticks:
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    if escaped:
        current.append("\\")
    cells.append("".join(current).strip())
    return cells


def separator(line):
    cells = table_cells(line)
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", cell.strip()) for cell in cells)


def parse_markdown(source):
    """Deliberately small, dependency-free parser for the report's Markdown."""
    lines = source.replace("\r\n", "\n").splitlines()
    blocks, i = [], 0

    def is_start(index):
        s = lines[index].strip()
        return (HEADING_RE.match(s) or IMAGE_RE.match(s) or s.startswith(("```", "~~~"))
                or LIST_RE.match(lines[index]) or s.startswith("> ")
                or (index + 1 < len(lines) and "|" in s and separator(lines[index + 1])))

    while i < len(lines):
        raw, text, line = lines[i], lines[i].strip(), i + 1
        if not text:
            i += 1
            continue
        heading = HEADING_RE.match(text)
        if heading:
            blocks.append(Block("heading", heading[2], line, len(heading[1])))
            i += 1
        elif text.startswith(("```", "~~~")):
            marker, language = text[:3], text[3:].strip()
            if language.lower() in ("mermaid", "plantuml"):
                raise ValueError(f"Line {line}: render {language} to a PNG before building the report")
            code = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith(marker):
                code.append(lines[i])
                i += 1
            if i == len(lines):
                raise ValueError(f"Line {line}: unterminated fenced code block")
            blocks.append(Block("code", "\n".join(code), line))
            i += 1
        elif IMAGE_RE.match(text):
            image = IMAGE_RE.match(text)
            blocks.append(Block("image", (image[1], image[2]), line))
            i += 1
        elif i + 1 < len(lines) and "|" in text and separator(lines[i + 1]):
            rows = [table_cells(text)]
            alignment = table_cells(lines[i + 1])
            i += 2
            while i < len(lines) and lines[i].strip() and "|" in lines[i]:
                row = table_cells(lines[i])
                if len(row) != len(rows[0]):
                    raise ValueError(f"Line {i + 1}: table has {len(row)} cells; expected {len(rows[0])}")
                rows.append(row)
                i += 1
            blocks.append(Block("table", (rows, alignment), line))
        elif LIST_RE.match(raw):
            item = LIST_RE.match(raw)
            depth = min(len(item[1].replace("\t", "    ")) // 2, 3)
            blocks.append(Block("list", (item[3], item[4]), line, depth))
            i += 1
        elif text.startswith("> "):
            blocks.append(Block("quote", text[2:], line))
            i += 1
        elif re.fullmatch(r"(?:---+|\*\*\*+|___+)", text):
            # A report uses paragraph spacing rather than decorative rules.
            i += 1
        elif text.startswith("<!--"):
            while i < len(lines) and "-->" not in lines[i]:
                i += 1
            i += 1
        elif text.startswith("<") and not text.startswith(("<br", "<http")):
            raise ValueError(f"Line {line}: HTML blocks are unsupported; use native Markdown content")
        else:
            paragraph = [text]
            i += 1
            while i < len(lines) and lines[i].strip() and not is_start(i):
                paragraph.append(lines[i].strip())
                i += 1
            blocks.append(Block("paragraph", " ".join(paragraph), line))
    return blocks


def add_inline(paragraph, text, *, size=None, bold=False, italic=False):
    """Write inline code/emphasis/links as native runs, never flattened pictures."""
    i, buffer = 0, []

    def write(value, code=False):
        if not value:
            return
        run = paragraph.add_run(value)
        if code:
            set_fonts(run, "Consolas", "宋体", size or 9.5, bold=bold)
        else:
            if size is not None:
                run.font.size = Pt(size)
            if bold:
                run.bold = True
            if italic:
                run.italic = True
        return run

    def flush():
        write("".join(buffer))
        buffer.clear()

    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text) and text[i + 1] in r"\`*[]_!|":
            buffer.append(text[i + 1]); i += 2
            continue
        br = re.match(r"<br\s*/?>", text[i:], re.I)
        if br:
            flush(); paragraph.add_run().add_break(); i += len(br[0])
            continue
        if text[i] == "`":
            marker = re.match(r"`+", text[i:])[0]
            end = text.find(marker, i + len(marker))
            if end != -1:
                flush(); write(text[i + len(marker):end], code=True)
                i = end + len(marker)
                continue
        if text.startswith("**", i):
            end = text.find("**", i + 2)
            if end != -1:
                flush(); add_inline(paragraph, text[i + 2:end], size=size, bold=True, italic=italic)
                i = end + 2
                continue
        if text[i] == "*":
            end = text.find("*", i + 1)
            if end != -1:
                flush(); add_inline(paragraph, text[i + 1:end], size=size, bold=bold, italic=True)
                i = end + 1
                continue
        if text[i] == "[":
            link = re.match(r"\[([^\]]+)\]\(([^)]+)\)", text[i:])
            if link:
                flush()
                relation = paragraph.part.relate_to(link[2], RT.HYPERLINK, is_external=True)
                element = xml(paragraph._p, "w:hyperlink", r__id=relation)
                run = write(link[1])
                run.font.underline = True
                element.append(run._r)
                i += len(link[0])
                continue
        buffer.append(text[i]); i += 1
    flush()


def field(paragraph, instruction, cached=""):
    begin = paragraph.add_run()
    xml(begin._r, "w:fldChar", w__fldCharType="begin", w__dirty="true")
    code = paragraph.add_run()
    element = xml(code._r, "w:instrText")
    element.set(qn("xml:space"), "preserve")
    element.text = " " + instruction + " "
    xml(paragraph.add_run()._r, "w:fldChar", w__fldCharType="separate")
    if cached:
        paragraph.add_run(cached)
    xml(paragraph.add_run()._r, "w:fldChar", w__fldCharType="end")


def setup_styles(document):
    styles = document.styles
    normal = styles["Normal"]
    set_fonts(normal)
    pf = normal.paragraph_format
    pf.line_spacing = 1.25
    pf.space_before = Pt(0)
    pf.space_after = Pt(4)
    pf.first_line_indent = Pt(22)
    pf.widow_control = True
    pf.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    for name, points in (("Title", 25), ("Subtitle", 17), ("Heading 1", 16), ("Heading 2", 13), ("Heading 3", 11.5), ("Heading 4", 11)):
        style = styles[name]
        set_fonts(style, "Arial", "黑体", points, bold=name != "Subtitle")
        style.font.italic = False
        style.paragraph_format.first_line_indent = Pt(0)
        style.paragraph_format.keep_with_next = True
        style.paragraph_format.keep_together = True
        style.paragraph_format.space_before = Pt(14 if name.startswith("Heading") else 0)
        style.paragraph_format.space_after = Pt(8)
        style.paragraph_format.line_spacing = 1.2
        style.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.LEFT
        # Chapters flow naturally. Only the front matter/body section boundaries
        # create explicit page breaks; keep_with_next protects every heading.
        style.paragraph_format.page_break_before = False
        for parent in (style.element.find(qn("w:pPr")), style.element.find(qn("w:rPr"))):
            if parent is not None:
                for tag in ("w:pBdr", "w:bdr"):
                    for item in list(parent.findall(qn(tag))):
                        parent.remove(item)
    definitions = {
        "Report Front Heading": ("黑体", "Arial", 16),
        "Report Caption": ("宋体", "Times New Roman", 9.5),
        "Report Table": ("宋体", "Times New Roman", 9.5),
        "Report Code": ("宋体", "Consolas", 8.5),
        "Report Figure": ("宋体", "Times New Roman", 9.5),
        "Report List": ("宋体", "Times New Roman", 11),
        "Report Cover Detail": ("宋体", "Times New Roman", 12),
    }
    for name, (east, latin, points) in definitions.items():
        style = styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        style.base_style = normal
        set_fonts(style, latin, east, points)
        style.paragraph_format.first_line_indent = Pt(0)
    for name in ("Report Front Heading", "Report Caption", "Report Figure", "Report Cover Detail"):
        styles[name].paragraph_format.alignment = WD_ALIGN_PARAGRAPH.CENTER
    styles["Report Front Heading"].paragraph_format.keep_with_next = True
    styles["Report Front Heading"].paragraph_format.space_after = Pt(15)
    styles["Report Caption"].paragraph_format.keep_together = True
    styles["Report Caption"].paragraph_format.space_after = Pt(6)
    styles["Report Caption"].paragraph_format.line_spacing = 1.15
    styles["Report Table"].paragraph_format.line_spacing = 1.08
    styles["Report Table"].paragraph_format.space_after = Pt(2)
    styles["Report Table"].paragraph_format.space_before = Pt(2)
    styles["Report Code"].paragraph_format.line_spacing = 1.1
    styles["Report Code"].paragraph_format.space_before = Pt(4)
    styles["Report Code"].paragraph_format.space_after = Pt(8)
    styles["Report Code"].paragraph_format.left_indent = Cm(.15)
    styles["Report Code"].paragraph_format.right_indent = Cm(.15)
    for name in ("TOC 1", "TOC 2"):
        style = styles[name] if name in styles else styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        set_fonts(style, size=11)
        style.paragraph_format.first_line_indent = Pt(0)
        style.paragraph_format.left_indent = Cm(0 if name == "TOC 1" else .65)
        style.paragraph_format.space_after = Pt(5)
        style.paragraph_format.line_spacing = 1.25
    for name in ("Header", "Footer"):
        set_fonts(styles[name], size=9)
        styles[name].paragraph_format.first_line_indent = Pt(0)
        styles[name].paragraph_format.space_after = Pt(0)
    update = document.settings.element.find(qn("w:updateFields"))
    if update is None:
        update = xml(document.settings.element, "w:updateFields")
    update.set(qn("w:val"), "true")
    # Explicit Chinese line-break and script defaults avoid locale/theme drift.
    defaults = document.styles.element.find(qn("w:docDefaults"))
    if defaults is not None:
        rdefaults = defaults.find(qn("w:rPrDefault"))
        if rdefaults is not None:
            rpr = rdefaults.find(qn("w:rPr"))
            if rpr is not None:
                lang = rpr.find(qn("w:lang"))
                if lang is None:
                    lang = xml(rpr, "w:lang")
                lang.set(qn("w:eastAsia"), "zh-CN")


def configure_section(section, number_format=None):
    section.page_width, section.page_height = Cm(21), Cm(29.7)
    section.left_margin = section.right_margin = Cm(2.3)
    section.top_margin = section.bottom_margin = Cm(2.2)
    section.header_distance, section.footer_distance = Cm(1.15), Cm(1.1)
    section.header.is_linked_to_previous = False
    section.footer.is_linked_to_previous = False
    section.different_first_page_header_footer = False
    if number_format:
        header = section.header.paragraphs[0]
        header.text = "Reconis·系统实践总结"
        header.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        footer = section.footer.paragraphs[0]
        footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
        field(footer, "PAGE", "I" if number_format == "upperRoman" else "1")
        page_number = section._sectPr.find(qn("w:pgNumType"))
        if page_number is None:
            page_number = xml(section._sectPr, "w:pgNumType")
        page_number.set(qn("w:fmt"), number_format)
        page_number.set(qn("w:start"), "1")


def add_cover(document):
    configure_section(document.sections[0])
    title = document.add_paragraph("Reconis 校园支付对账系统", "Title")
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_before = Cm(4.2)
    title.paragraph_format.space_after = Pt(20)
    subtitle = document.add_paragraph("系统实践总结报告", "Subtitle")
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    subtitle.paragraph_format.space_after = Cm(3.2)
    for name, student_id in MEMBERS:
        paragraph = document.add_paragraph(f"{name}    {student_id}", "Report Cover Detail")
        paragraph.paragraph_format.space_after = Pt(12)
    date = document.add_paragraph("2026年11月6日", "Report Cover Detail")
    date.paragraph_format.space_before = Cm(2.2)
    configure_section(document.add_section(WD_SECTION_START.NEW_PAGE), "upperRoman")


def column_widths(rows):
    count = len(rows[0])
    if count > 10:
        raise ValueError("Tables wider than 10 columns need editorial restructuring")
    weights, minimum = [], []
    for index in range(count):
        lengths = sorted(display_units(row[index]) for row in rows[1:] or rows)
        typical = lengths[min(len(lengths) - 1, math.floor(len(lengths) * .8))]
        typical = max(display_units(rows[0][index]), min(typical, 75), 2)
        compact = all(display_units(row[index]) <= 12 for row in rows)
        weights.append(typical ** .68)
        minimum.append(.8 if compact else 1.35)
    remainder = TABLE_WIDTH_CM - sum(minimum)
    result = [minimum[i] + remainder * weights[i] / sum(weights) for i in range(count)]
    return result


def add_table(document, rows, alignments, *, widths=None, keep_table=False, compact=False):
    widths = widths or column_widths(rows)
    table = document.add_table(rows=1, cols=len(rows[0]))
    table.autofit = False
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    properties = table._tbl.tblPr
    width = properties.find(qn("w:tblW"))
    width.set(qn("w:w"), str(round(TABLE_WIDTH_CM / 2.54 * 1440)))
    width.set(qn("w:type"), "dxa")
    borders = xml(properties, "w:tblBorders")
    for side in ("top", "left", "bottom", "right", "insideH", "insideV"):
        xml(borders, "w:" + side, w__val="single", w__sz="4", w__color="D9D9D9")
    margins = xml(properties, "w:tblCellMar")
    for side, amount in (("top", 65), ("bottom", 65), ("left", 85), ("right", 85)):
        xml(margins, "w:" + side, w__w=str(amount), w__type="dxa")
    for column, cm in zip(table.columns, widths):
        column.width = Cm(cm)
    for row_index, values in enumerate(rows):
        row = table.rows[0] if row_index == 0 else table.add_row()
        xml(row._tr.get_or_add_trPr(), "w:cantSplit")
        if row_index == 0:
            xml(row._tr.get_or_add_trPr(), "w:tblHeader")
        for index, value in enumerate(values):
            cell = row.cells[index]
            cell.width = Cm(widths[index])
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            paragraph = cell.paragraphs[0]
            paragraph.style = "Report Table"
            paragraph.paragraph_format.keep_with_next = row_index == 0 or (keep_table and row_index < len(rows) - 1)
            paragraph.paragraph_format.keep_together = True
            if compact:
                paragraph.paragraph_format.space_before = Pt(1)
                paragraph.paragraph_format.space_after = Pt(1)
                paragraph.paragraph_format.line_spacing = 1.05
            short_numeric = display_units(value) <= 14 and (len(value) < 10 or re.fullmatch(r"[\d.,% /+\-]+", value))
            center_requested = alignments[index].startswith(":") and alignments[index].endswith(":")
            paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER if row_index == 0 or short_numeric or center_requested else WD_ALIGN_PARAGRAPH.LEFT
            add_inline(paragraph, value, size=9 if len(values) >= 6 else 9.5, bold=row_index == 0)
            if row_index == 0:
                xml(cell._tc.get_or_add_tcPr(), "w:shd", w__fill="F1F1F1", w__val="clear")
    return table


def wrapped_code_lines(code, limit=94):
    for original in code.expandtabs(4).split("\n"):
        remaining = original
        while sum(2 if ord(char) > 0x2FFF else 1 for char in remaining) > limit:
            width, position, last_break = 0, 0, 0
            for position, char in enumerate(remaining):
                width += 2 if ord(char) > 0x2FFF else 1
                if width > limit:
                    break
                if char in " ,;()":
                    last_break = position + 1
            split = last_break if last_break >= position // 2 else position
            yield remaining[:split]
            remaining = remaining[split:]
        yield remaining


def add_code(document, code, *, keep_block=False, compact=False):
    paragraph = document.add_paragraph(style="Report Code")
    lines = list(wrapped_code_lines(code))
    paragraph.paragraph_format.keep_together = keep_block or len(lines) <= 14
    if compact:
        paragraph.paragraph_format.space_before = Pt(2)
        paragraph.paragraph_format.space_after = Pt(4)
    paragraph.alignment = WD_ALIGN_PARAGRAPH.LEFT
    xml(paragraph._p.get_or_add_pPr(), "w:shd", w__fill="F6F6F6", w__val="clear")
    for index, line in enumerate(lines):
        if index:
            paragraph.add_run().add_break()
        run = paragraph.add_run(line)
        set_fonts(run, "Consolas", "宋体", 8.5)


def add_image(document, alt, source, input_path):
    source = unquote(source.strip().strip("<>"))
    if re.match(r"https?://", source, re.I):
        raise ValueError("Images must be verified local PNGs, not remote URLs")
    path = Path(source)
    if not path.is_absolute():
        path = input_path.parent / path
    path = path.resolve(strict=True)
    with Image.open(path) as image:
        width, height = image.size
        if image.format not in ("PNG", "JPEG"):
            raise ValueError(f"Use a PNG or JPEG instead of {image.format}: {path.name}")
    width_cm = min(16.2, width / 100 * 2.54)
    height_cm = width_cm * height / width
    if height_cm > MAX_IMAGE_HEIGHT_CM:
        width_cm *= MAX_IMAGE_HEIGHT_CM / height_cm
    paragraph = document.add_paragraph(style="Report Figure")
    paragraph.paragraph_format.keep_with_next = bool(alt)
    paragraph.paragraph_format.keep_together = True
    paragraph.paragraph_format.space_before = Pt(6)
    paragraph.paragraph_format.space_after = Pt(3)
    shape = paragraph.add_run().add_picture(str(path), width=Cm(width_cm))
    shape._inline.docPr.set("descr", alt)
    shape._inline.docPr.set("title", alt)
    if alt:
        caption = document.add_paragraph(style="Report Caption")
        add_inline(caption, alt)
        caption.paragraph_format.keep_with_next = False


def build(input_path, output_path):
    source = input_path.read_text(encoding="utf-8-sig")
    blocks = parse_markdown(source)
    document = Document()
    setup_styles(document)
    properties = document.core_properties
    properties.title = "Reconis 校园支付对账系统实践总结报告"
    properties.subject = "数据库课程设计最终实践总结"
    properties.author = properties.last_modified_by = AUTHORS
    properties.keywords = "校园支付 对账 数据库 实践总结"
    properties.comments = ""
    properties.created = datetime(2026, 11, 4, tzinfo=timezone.utc)
    properties.modified = datetime(2026, 11, 6, tzinfo=timezone.utc)
    properties.revision = 1
    add_cover(document)
    main_started, toc_added, last_image_caption = False, False, None
    appendix = None
    paragraphs = tables = images = 0
    for block_index, block in enumerate(blocks):
        previous_block = blocks[block_index - 1] if block_index else None
        next_block = blocks[block_index + 1] if block_index + 1 < len(blocks) else None
        if block.kind == "heading":
            text = plain(str(block.content))
            if block.level == 1:
                appendix_match = re.match(r"附录\s*([A-Z])", text)
                appendix = appendix_match[1] if appendix_match else None
            if not main_started and "Reconis" in text and "报告" in text:
                continue  # Cover is defined once, not repeated from the source title.
            if block.level == 1 and text in ("摘要", "目录", "目 录", "摘 要"):
                paragraph = document.add_paragraph(text.replace(" ", ""), "Report Front Heading")
                paragraph.paragraph_format.page_break_before = text.replace(" ", "") == "目录"
                if text.replace(" ", "") == "目录":
                    toc = document.add_paragraph()
                    toc.paragraph_format.first_line_indent = Pt(0)
                    field(toc, 'TOC \\o "1-2" \\h \\z \\u')
                    toc_added = True
                continue
            first_main = not main_started
            if first_main:
                configure_section(document.add_section(WD_SECTION_START.NEW_PAGE), "decimal")
                main_started = True
            paragraph = document.add_paragraph(style=f"Heading {min(block.level, 4)}")
            add_inline(paragraph, str(block.content))
            if appendix == "C":
                paragraph.paragraph_format.space_before = Pt(10)
                paragraph.paragraph_format.space_after = Pt(5)
            if first_main:
                paragraph.paragraph_format.page_break_before = False
        elif block.kind in ("paragraph", "quote"):
            text = str(block.content)
            # Images own their alt-text captions; avoid duplicating an identical line.
            if last_image_caption and plain(text) == plain(last_image_caption):
                last_image_caption = None
                continue
            if text in ("[[TOC]]", "[TOC]", "[toc]"):
                if not toc_added:
                    paragraph = document.add_paragraph()
                    paragraph.paragraph_format.first_line_indent = Pt(0)
                    field(paragraph, 'TOC \\o "1-2" \\h \\z \\u')
                    toc_added = True
                continue
            # Figure captions are emitted by add_image. A paragraph beginning
            # with a figure reference is explanatory prose, not a caption.
            caption = plain(text).startswith("表") and len(plain(text)) < 65 and bool(CAPTION_RE.match(plain(text)))
            paragraph = document.add_paragraph(style="Report Caption" if caption else "Normal")
            if caption:
                paragraph.paragraph_format.keep_with_next = plain(text).startswith("表")
            if block.kind == "quote":
                paragraph.paragraph_format.left_indent = Cm(.6)
                paragraph.paragraph_format.first_line_indent = Pt(0)
            if appendix == "B" and re.match(r"^B-C\d+", text) and next_block and next_block.kind == "code":
                paragraph.paragraph_format.keep_with_next = True
                paragraph.paragraph_format.keep_together = True
                paragraph.paragraph_format.space_after = Pt(3)
            if appendix == "C":
                # Keep the 11pt body type; recover space through paragraph gaps.
                paragraph.paragraph_format.space_before = Pt(0)
                paragraph.paragraph_format.space_after = Pt(2 if not caption else 4)
                if not caption:
                    paragraph.paragraph_format.line_spacing = 1.2
                if next_block and next_block.kind == "code" and len(text) < 80:
                    paragraph.paragraph_format.keep_with_next = True
            add_inline(paragraph, text)
            paragraphs += 1
        elif block.kind == "table":
            rows, alignments = block.content
            evidence_index = appendix == "C" and len(rows[0]) == 2 and rows[0][0] == "文件或目录"
            add_table(document, rows, alignments,
                      widths=[10.0, 6.4] if evidence_index else None,
                      keep_table=evidence_index, compact=appendix == "C")
            tables += 1
        elif block.kind == "image":
            alt, source_path = block.content
            add_image(document, alt, source_path, input_path)
            last_image_caption = alt
            images += 1
        elif block.kind == "code":
            check_explanation = (appendix == "B" and previous_block and
                                 previous_block.kind == "paragraph" and
                                 re.match(r"^B-C\d+", str(previous_block.content)))
            add_code(document, str(block.content), keep_block=bool(check_explanation),
                     compact=appendix == "C")
        elif block.kind == "list":
            number, text = block.content
            paragraph = document.add_paragraph(style="Report List")
            paragraph.paragraph_format.left_indent = Cm(.65 + .5 * block.level)
            paragraph.paragraph_format.first_line_indent = Cm(-.5)
            add_inline(paragraph, (f"{number}. " if number else "• ") + text)
        if block.kind != "image":
            last_image_caption = None
    if not main_started:
        raise ValueError("The source needs a chapter heading after the abstract/TOC")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    document.save(output_path)
    print(f"Created: {output_path}")
    print(f"Paragraphs={paragraphs}; tables={tables}; images={images}; sections={len(document.sections)}; TOC={toc_added}")
    print("Update Word fields and visually inspect every rendered page before final delivery.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    build(args.input.resolve(strict=True), args.output.resolve())


if __name__ == "__main__":
    main()
