#!/usr/bin/env python3
"""Audit generated reports/deck against source headings, figures, and chart data."""
from datetime import datetime
import json
from pathlib import Path
import re
from zipfile import ZipFile

from docx import Document
import fitz
from lxml import etree
from pptx import Presentation

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "artifacts/delivery"
VALIDATION = ROOT / "artifacts/validation"


def main():
    issues, details = [], {}
    for stem, name, minimum_tables in [("system-design", "系统设计报告", 22), ("implementation-report", "系统实现总结报告", 2)]:
        source = (ROOT / "docs/reports" / f"{stem}.md").read_text()
        docx = OUTPUT / f"Reconis-{name}.docx"
        pdf = OUTPUT / f"Reconis-{name}.pdf"
        if not docx.is_file() or not pdf.is_file():
            issues.append(f"{name} 缺少 Word 或 PDF"); continue
        doc = Document(docx)
        paragraphs = [p.text for p in doc.paragraphs]
        headings = re.findall(r"^#{2,4}\s+(.*)$", source, re.M)
        missing = [heading for heading in headings if heading not in paragraphs]
        if missing: issues.append(f"{name} 缺少源稿章节：{missing}")
        full_text = "\n".join(paragraphs + [cell.text for table in doc.tables for row in table.rows for cell in row.cells])
        normalized = re.sub(r"\s+", "", full_text)
        in_code = False
        for line in source.splitlines()[1:]:
            if line.startswith("```"):
                in_code = not in_code
                continue
            if in_code or not line.strip() or line.startswith(("#", "|", "![")): continue
            expected = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line.strip())
            expected = expected.removeprefix("- ").replace("**", "").replace("`", "")
            if len(expected) >= 20 and re.sub(r"\s+", "", expected) not in normalized:
                issues.append(f"{name} 缺少源稿正文：{expected[:45]}")
        figures = len(doc.element.xpath("//w:drawing"))
        if figures != 4: issues.append(f"{name} 配图数不符：{figures}，应为 4")
        if len(doc.tables) < minimum_tables: issues.append(f"{name} 数据表缺失：{len(doc.tables)}")
        if any("erDiagram" in p or "flowchart " in p for p in paragraphs): issues.append(f"{name} 残留未渲染图代码")
        document = fitz.open(pdf)
        text = "\n".join(page.get_text() for page in document)
        if any(marker in text for marker in ("Error!", "错误!", "错误！", "No text of specified style")):
            issues.append(f"{name} 出现字段错误")
        for index, page in enumerate(document):
            for block in page.get_text("blocks"):
                if block[0] < -2 or block[1] < -2 or block[2] > page.rect.width + 2 or block[3] > page.rect.height + 2:
                    issues.append(f"{name} 第 {index + 1} 页文字超出纸张")
        details[name] = {"pages": len(document), "headingsChecked": len(headings), "tables": len(doc.tables), "figures": figures,
                         "coverPendingFields": sum(p.count("待填写") for p in paragraphs)}
    deck = OUTPUT / "Reconis-课程答辩.pptx"
    deck_pdf = OUTPUT / "Reconis-课程答辩.pdf"
    presentation = Presentation(deck)
    outline = json.loads((ROOT / "docs/presentation/outline.json").read_text())
    for index, (slide, spec) in enumerate(zip(presentation.slides, outline["slides"])):
        slide_text = "\n".join(shape.text for shape in slide.shapes if shape.has_text_frame)
        if spec["title"] not in slide_text: issues.append(f"PPT 第 {index + 1} 页标题与源稿不一致")
        if spec.get("variant") == "table":
            native = [shape.table for shape in slide.shapes if shape.has_table]
            expected = [spec["headers"], *spec["rows"]]
            actual = [[cell.text for cell in row.cells] for row in native[0].rows] if native else []
            if actual != expected: issues.append(f"PPT 第 {index + 1} 页表格内容与源稿不一致")
    tables = sum(shape.has_table for slide in presentation.slides for shape in slide.shapes)
    charts = sum(shape.has_chart for slide in presentation.slides for shape in slide.shapes)
    if len(presentation.slides) != 14 or tables != 6 or charts != 1:
        issues.append("PPT 页数或可编辑表格/图表数量不符")
    with ZipFile(deck) as archive:
        chart = etree.fromstring(archive.read("ppt/charts/chart1.xml"))
        values = chart.xpath("//c:val/c:numRef/c:numCache/c:pt/c:v/text()", namespaces={"c": "http://schemas.openxmlformats.org/drawingml/2006/chart"})
        if list(map(float, values)) != [100, -30]: issues.append("跨日图表不等于 100 / -30 元")
    if len(fitz.open(deck_pdf)) != len(presentation.slides): issues.append("PPT 与 PDF 页数不一致")
    details["PPT"] = {"slides": len(presentation.slides), "nativeTables": tables, "nativeCharts": charts, "crossDayYuan": [100, -30]}
    manual = VALIDATION / "pptx-qa/manual_review_passed.flag"
    if not manual.exists(): issues.append("缺少最终原生 PDF 的人工视觉检查记录")
    result = {"checkedAt": datetime.now().astimezone().isoformat(), "status": "PASSED" if not issues else "FAILED",
              "summary": "两份报告章节、配图、表格及 PDF 边界检查通过；14 页 PPT 保留 6 个原生表格和 1 个图表，100/-30 元与源数据一致；封面待填写项单列" if not issues else "；".join(issues),
              "details": details, "issues": issues,
              "reviewScope": "原生 Word/PowerPoint PDF 接触表及关键页已逐页检查；封面真实身份信息待用户补充"}
    (VALIDATION / "material-checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if issues: raise SystemExit(1)


if __name__ == "__main__": main()
