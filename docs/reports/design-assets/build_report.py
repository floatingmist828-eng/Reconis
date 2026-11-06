#!/usr/bin/env python3
"""Build the initial design report from its frozen design-stage sources.

No application/database files are read; final implementation report is untouched.
Microsoft Word updates TOC fields and exports PDF in the separate finalize step.
"""
from __future__ import annotations
import json
from pathlib import Path
import re
import sys

ASSETS = Path(__file__).resolve().parent
REPORTS = ASSETS.parent
sys.path.insert(0, str(REPORTS / 'typography'))
from report_typography import set_fonts, normalize_template


def sql_columns() -> list[tuple[str, list[tuple[str, str, str]]]]:
    """Read the initial design dictionary, preserved from the archived report."""
    tables = json.loads((ASSETS / "schema-fields.json").read_text(encoding="utf-8"))
    return [(table["name"], [tuple(row) for row in table["columns"]]) for table in tables]


def build_report(source: Path, target: Path, figures: dict[str, Path], cover: dict) -> None:
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
    from docx.enum.style import WD_STYLE_TYPE
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Cm, Pt, RGBColor
    from PIL import Image

    doc = Document()
    for root in (doc.element, doc.styles.element):
        for border in root.iter(qn('w:pBdr')):
            border.getparent().remove(border)
    section = doc.sections[0]
    section.page_width, section.page_height = Cm(21), Cm(29.7)
    section.top_margin, section.bottom_margin = Cm(2.3), Cm(2.2)
    section.left_margin, section.right_margin = Cm(2.6), Cm(2.2)
    section.header_distance, section.footer_distance = Cm(1.1), Cm(1.1)
    for name, size in [("Normal", 11), ("Title", 25), ("Subtitle", 16), ("Heading 1", 16), ("Heading 2", 13),
                       ("Heading 3", 11.5), ("Heading 4", 11), ("Caption", 9.5), ("Table Grid", 9.5),
                       ("TOC 1", 11), ("TOC 2", 11), ("Header", 9), ("Footer", 9)]:
        if name not in doc.styles: doc.styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        style = doc.styles[name]
        is_heading = name.startswith('Heading') or name in ('Title', 'Subtitle')
        set_fonts(style, 'Arial' if is_heading else 'Times New Roman',
                  '黑体' if is_heading else '宋体', size, bold=is_heading)
        style.font.italic = False
        if name.startswith("Heading"):
            style.font.color.rgb = RGBColor(0, 0, 0)
            style.paragraph_format.keep_with_next = True
            style.paragraph_format.space_before, style.paragraph_format.space_after = Pt(15), Pt(7)
    normal = doc.styles["Normal"].paragraph_format
    normal.line_spacing, normal.space_after = 1.35, Pt(5)
    normalize_template(doc)
    doc.settings.element.append(OxmlElement("w:updateFields"))
    doc.settings.element[-1].set(qn("w:val"), "true")

    lines = source.read_text(encoding="utf-8").splitlines()
    report_title = lines[0].removeprefix("# ").replace("Reconis ", "")
    doc.core_properties.title = "Reconis " + report_title
    doc.core_properties.subject = "数据库课程设计"
    doc.core_properties.author = "、".join(m.get("name", "") for m in cover.get("members", []) if m.get("name"))
    p = doc.add_paragraph("Reconis " + report_title, "Title")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.runs[0].font.size = Pt(25)
    p.runs[0].bold = True
    p = doc.add_paragraph("校园多商户支付对账与差错协同处理系统")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.runs[0].font.size = Pt(12)
    doc.add_paragraph("目　录", style="Subtitle").runs[0].font.color.rgb = RGBColor(0, 0, 0)
    toc = doc.add_paragraph()
    field = OxmlElement("w:fldSimple")
    field.set(qn("w:instr"), 'TOC \\o "1-1" \\h \\z \\u')
    toc._p.append(field)
    doc.add_page_break()

    header = section.header.paragraphs[0]
    header.text = "Reconis · " + report_title
    header.style = doc.styles["Header"]
    footer = section.footer.paragraphs[0]
    footer.style = doc.styles['Footer']
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    footer.add_run("第 ")
    field = OxmlElement("w:fldSimple")
    field.set(qn("w:instr"), "PAGE")
    footer._p.append(field)
    footer.add_run(" 页")

    chapter, table_counter, current_heading = "0", 0, "数据定义"

    def paragraph(text, style=None):
        text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
        p = doc.add_paragraph(style=style)
        for index, part in enumerate(re.split(r"(\*\*.*?\*\*|`[^`]+`)", text)):
            r = p.add_run(part.strip("*`") if part.startswith(("**", "`")) else part)
            if part.startswith("**"): r.bold = True
            if part.startswith("`"):
                r.font.name = "DejaVu Sans Mono"
                r.font.size = Pt(9)
        return p

    def picture(path, caption):
        with Image.open(path) as im:
            width = 15.8
            if im.height / im.width > 1.5: width = min(8.8, 18 / (im.height / im.width))
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.keep_with_next = True
        p.add_run().add_picture(str(path), width=Cm(width))
        p = doc.add_paragraph(caption, style="Caption")
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER

    def table(rows, caption=None):
        nonlocal table_counter
        table_counter += 1
        if caption:
            p = doc.add_paragraph(caption, style="Caption")
        else:
            caption_title = re.sub(r'^[\d.]+\s*', '', current_heading)
            p = doc.add_paragraph(f"表 {chapter}.{table_counter}　{caption_title}", style="Caption")
        p.paragraph_format.keep_with_next = True
        grid = doc.add_table(rows=1, cols=len(rows[0]))
        grid.style = "Table Grid"
        grid.autofit = False
        usable = 16.2
        borders = OxmlElement('w:tblBorders')
        for edge in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'):
            item = OxmlElement('w:' + edge)
            for key, value in [('val', 'single'), ('sz', '4'), ('color', 'D9D9D9')]: item.set(qn('w:' + key), value)
            borders.append(item)
        grid._tbl.tblPr.append(borders)
        longest = [max(len(str(row[col])) for row in rows if col < len(row)) for col in range(len(rows[0]))]
        weights = [min(48, max(12, value)) for value in longest]
        if (caption or '').endswith('exception_task'):
            weights = [4.2, 3.8, 8.2]
        for col, weight in zip(grid.columns, weights): col.width = Cm(usable * weight / sum(weights))
        for i, values in enumerate(rows):
            cells = grid.rows[0].cells if i == 0 else grid.add_row().cells
            for j, value in enumerate(values[:len(cells)]):
                cell = cells[j]
                cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
                margins = OxmlElement('w:tcMar')
                for edge, margin in [('top', '70'), ('bottom', '70'), ('left', '90'), ('right', '90')]:
                    item = OxmlElement('w:' + edge)
                    item.set(qn('w:w'), margin)
                    item.set(qn('w:type'), 'dxa')
                    margins.append(item)
                cell._tc.get_or_add_tcPr().append(margins)
                cell.width = Cm(usable * weights[j] / sum(weights))
                cell.text = value.replace("`", "").replace("**", "")
                for p in cell.paragraphs:
                    # Keep the repeated header with at least two data rows.
                    p.paragraph_format.keep_with_next = (
                        i < min(2, len(rows) - 1)
                        or ((caption or '').endswith('reconciliation_result') and i == len(rows) - 2)
                    )
                    p.paragraph_format.space_after = Pt(3)
                    p.paragraph_format.space_before = Pt(3)
                    p.paragraph_format.line_spacing = 1.15
                    for r in p.runs: r.font.size = Pt(9.5)
                if i == 0:
                    shade = OxmlElement("w:shd")
                    shade.set(qn("w:fill"), "F2F2F2")
                    cell._tc.get_or_add_tcPr().append(shade)
                    for r in cell.paragraphs[0].runs: r.bold = True
            pr = grid.rows[i]._tr.get_or_add_trPr()
            pr.append(OxmlElement("w:cantSplit"))
            if i == 0: pr.append(OxmlElement("w:tblHeader"))
        doc.add_paragraph().paragraph_format.space_after = Pt(0)

    i = 1
    while i < len(lines):
        line = lines[i].strip()
        if not line: i += 1; continue
        if line.startswith("```"):
            language, content = line[3:].strip(), []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                content.append(lines[i]); i += 1
            if language == "mermaid":
                if any("erDiagram" in item for item in content):
                    for n, key in enumerate(("er-identity", "er-funds", "er-reconciliation"), 1):
                        picture(figures[key], f"图 {chapter}.{n}　关系图（分域展示；PK 主键，UK 唯一键，箭头指向子记录）")
                else:
                    key = "data-flow"
                    picture(figures[key], f"图 {chapter}.1　主要数据流")
            else:
                for item in content:
                    p = doc.add_paragraph(item)
                    p.paragraph_format.space_after = Pt(0)
                    p.paragraph_format.line_spacing = 1.05
                    for r in p.runs: r.font.name = "DejaVu Sans Mono"; r.font.size = Pt(8)
            i += 1; continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                values = [value.strip() for value in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", value.strip()) for value in values): rows.append(values)
                i += 1
            table(rows); continue
        heading = re.match(r"^(#{2,4})\s+(.*)", line)
        if heading:
            current_heading = heading[2]
            level = len(heading[1]) - 1
            if level == 1:
                chapter = heading[2].split()[0]
                table_counter = 0
            doc.add_heading(heading[2], level=level)
        elif line.startswith("!["):
            match = re.match(r"!\[([^\]]*)\]\(([^)]+)\)", line)
            if match:
                path = (source.parent / match[2]).resolve()
                caption = lines[i + 2].strip() if i + 2 < len(lines) and lines[i + 2].startswith("图 ") else match[1]
                picture(path, caption)
                if caption != match[1]: i += 2
        elif line.startswith("- "): paragraph(line[2:], "List Bullet")
        elif re.match(r"^\d+\.\s", line): paragraph(line)
        else: paragraph(line)
        i += 1

    doc.add_page_break()
    doc.add_heading("附录 A　基础表字段字典", level=1)
    table_counter = 0
    paragraph((ASSETS / "appendix-intro.md").read_text(encoding="utf-8").strip())
    for name, columns in sql_columns():
        doc.add_heading(name, level=2)
        table([["字段", "SQL 类型", "键与约束摘要"], *[list(row) for row in columns]], f"表 A.{table_counter + 1}　{name}")
    doc.add_heading("自动账单扩展（迁移 007）", level=2)
    paragraph((ASSETS / "acquisition-extension.md").read_text(encoding="utf-8").strip())
    target.parent.mkdir(parents=True, exist_ok=True)
    normalize_template(doc)
    doc.save(target)
    print("Report:", target, flush=True)


def main() -> None:
    figures = {name: ASSETS / "diagrams" / (name + ".png") for name in
               ("data-flow", "er-identity", "er-funds", "er-reconciliation")}
    cover = {"members": [{"name": name} for name in ("王梓豪", "吕世英", "朱晓天")]}
    build_report(REPORTS / "system-design.md",
                 REPORTS / "final" / "Reconis-系统设计报告.docx", figures, cover)


if __name__ == "__main__":
    main()
