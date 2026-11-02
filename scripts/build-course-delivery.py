#!/usr/bin/env python3
"""Build course reports and the source-backed defense deck; never install tools."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / "artifacts" / "matplotlib"))


def run(command: list[str], **kwargs) -> None:
    print("Running:", " ".join(map(str, command)), flush=True)
    subprocess.run(command, check=True, **kwargs)


def diagrams() -> dict[str, Path]:
    existing = {key: ROOT / 'docs/assets/diagrams' / (key + '.png') for key in ('data-flow', 'modules', 'er-identity', 'er-funds', 'er-reconciliation')}
    if all(path.is_file() for path in existing.values()):
        return existing
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

    available = {font.name for font in font_manager.fontManager.ttflist}
    cjk_font = next((name for name in ("Noto Sans CJK SC", "Microsoft YaHei", "SimHei", "SimSun") if name in available), None)
    if not cjk_font: raise RuntimeError("关系图需要中文字体：Noto Sans CJK SC、微软雅黑、黑体或宋体")
    plt.rcParams.update({"font.family": cjk_font, "svg.fonttype": "none"})
    target = ROOT / "docs" / "assets" / "diagrams"
    target.mkdir(parents=True, exist_ok=True)

    def draw(name, nodes, edges, size=(12, 7)):
        fig, ax = plt.subplots(figsize=size)
        fig.patch.set_facecolor("white")
        ax.set_xlim(0, 12)
        ax.set_ylim(0, 7)
        ax.axis("off")
        width, height = 2.7, 1.05
        for start, end, label in edges:
            x1, y1, *_ = nodes[start]
            x2, y2, *_ = nodes[end]
            dx, dy = x2 - x1, y2 - y1
            t = min(width / 2 / abs(dx) if dx else 999, height / 2 / abs(dy) if dy else 999)
            a, b = (x1 + dx * t, y1 + dy * t), (x2 - dx * t, y2 - dy * t)
            arrow = FancyArrowPatch(a, b, arrowstyle="-|>", color="#71958a", mutation_scale=12, linewidth=1.3)
            ax.add_patch(arrow)
            if label:
                ax.text((a[0] + b[0]) / 2, (a[1] + b[1]) / 2 + .11, label,
                        ha="center", va="center", fontsize=9, color="#48665d",
                        bbox={"facecolor": "white", "edgecolor": "none", "pad": 1.5})
        for x, y, title, body in nodes.values():
            ax.add_patch(FancyBboxPatch((x-width/2, y-height/2), width, height,
                                      boxstyle="round,pad=.025,rounding_size=.07",
                                      facecolor="#f1f7f4", edgecolor="#7aa897", linewidth=1.1))
            ax.text(x, y+.16, title, ha="center", va="center", fontsize=12, color="#163d31", weight="bold")
            ax.text(x, y-.22, body, ha="center", va="center", fontsize=9, color="#48665d")
        fig.subplots_adjust(left=.015, right=.985, bottom=.02, top=.98)
        for extension in ("png", "svg"):
            rendered = target / f"{name}.{extension}"
            fig.savefig(rendered, dpi=200, facecolor="white")
            if extension == "svg":
                rendered.write_text("\n".join(line.rstrip() for line in rendered.read_text().splitlines()) + "\n")
        plt.close(fig)

    draw("data-flow", {
        "u": (1.5, 5.9, "消费者 / 收银员", "订单与退款申请"),
        "p": (5.7, 5.9, "交易处理", "订单 · 支付 · 退款"),
        "c": (10.0, 5.9, "支付渠道", "可信通知与主动查询"),
        "f": (1.5, 3.5, "财务 / 复核员", "账单 · 分派 · 复核"),
        "d": (5.7, 3.5, "交易数据存储", "订单 / 支付 / 退款 / 流水"),
        "r": (10.0, 3.5, "对账与协作", "账单 / 结果 / 工单"),
        "a": (1.5, 1.1, "管理与审计", "资料审核 · 访问操作日志"),
        "s": (5.7, 1.1, "统计与导出", "业务日 · 商户 · 门店 · 差错"),
        "o": (10.0, 1.1, "可追溯结果", "来源明细 / 图表 / 报告"),
    }, [("u", "p", "交易请求"), ("p", "c", "渠道调用"), ("p", "d", "确认事实"),
        ("f", "d", "授权操作"), ("d", "r", "本地事实"), ("c", "r", "渠道账单"),
        ("d", "s", "汇总"), ("r", "o", "核对证据"), ("s", "o", "统计输出"), ("a", "s", "只读查询")])
    draw("er-identity", {
        "u": (1.6, 5.4, "用户 app_user", "PK id · UK username"),
        "m": (10.0, 5.4, "商户 merchant", "PK id · UK merchant_code"),
        "j": (5.8, 5.4, "成员联系 merchant_member", "UK (merchant_id, user_id)"),
        "s": (10.0, 2.4, "门店 store", "UK (merchant_id, store_code)"),
        "o": (5.8, 2.4, "订单 business_order", "FK store_id · FK consumer_id"),
        "a": (1.6, 2.4, "审计日志 audit_log", "FK actor_user_id · scope_merchant_id"),
    }, [("u", "j", "1 : N"), ("m", "j", "1 : N"), ("m", "s", "1 : N"),
        ("s", "o", "1 : N"), ("u", "o", "消费者 0..1 : N"), ("u", "a", "0..1 : N")])
    draw("er-funds", {
        "o": (1.6, 5.4, "订单 business_order", "业务应收与状态"),
        "i": (1.6, 2.1, "明细 order_item", "UK (order_id, line_no)"),
        "p": (5.8, 5.4, "支付 payment_attempt", "UK out_trade_no / client_request_id"),
        "n": (10.0, 5.4, "通知 payment_notification", "允许多次接收同一通知"),
        "r": (5.8, 2.1, "退款 refund_request", "UK out_request_no / client_request_id"),
        "e": (10.0, 2.1, "流水 transaction_entry", "收款 / 退款源互斥 · UK event_key"),
    }, [("o", "i", "1 : N"), ("o", "p", "1 : N"), ("p", "n", "0..1 : N"),
        ("p", "r", "1 : N"), ("p", "e", "收款 1 : 0..1"), ("r", "e", "退款 1 : 0..1")])
    draw("er-reconciliation", {
        "b": (1.6, 5.4, "批次 reconciliation_batch", "输入来源 / 基线自关联"),
        "l": (5.8, 5.4, "账单 statement_line", "UK (import_batch_id, line_no)"),
        "e": (10.0, 5.4, "流水 transaction_entry", "只读资金事实"),
        "r": (5.8, 2.1, "结果 reconciliation_result", "UK (batch_id, result_key)"),
        "t": (10.0, 2.1, "工单 exception_task", "起因固定 · 最新结果可更新"),
    }, [("b", "l", "1 : N"), ("b", "r", "1 : N"), ("l", "r", "0..1 : N"),
        ("e", "r", "0..1 : N"), ("r", "t", "起因 1 : 0..1")])
    draw("modules", {
        "v": (1.6, 5.4, "Vue 3 页面", "列表 · 详情 · 图表 · 表单"),
        "a": (5.8, 5.4, "Spring Boot API", "权限 · 参数 · 会话 · CSRF"),
        "g": (10.0, 5.4, "渠道适配器", "DEMO / 支付宝官方 SDK"),
        "f": (1.6, 2.1, "受控文件目录", "账单原件 · 证据 · 导出"),
        "s": (5.8, 2.1, "业务模块", "交易 · 对账 · 协作 · 分析"),
        "d": (10.0, 2.1, "SQL Server", "15 表 · 5 过程 · 2 函数 · 全文"),
    }, [("v", "a", "同源 API"), ("a", "g", "调用"), ("a", "s", "业务处理"),
        ("s", "f", "归档"), ("s", "d", "MyBatis")])
    return {name: target / f"{name}.png" for name in
            ("data-flow", "er-identity", "er-funds", "er-reconciliation", "modules")}


def sql_columns() -> list[tuple[str, list[tuple[str, str, str]]]]:
    text = (ROOT / "database/001_schema.sql").read_text(encoding="utf-8-sig")
    result = []
    pattern = r"\b([a-z][a-z_0-9]*)\s+((?:BIGINT|INT|BIT|ROWVERSION|NVARCHAR|VARCHAR|DATETIME2|CHAR)(?:\((?:MAX|\d+)\))?)\b?"
    # A lookahead avoids splitting NVARCHAR(MAX), CHECK, and composite-key commas.
    pattern = pattern.removesuffix(r"\b?") + r"(?=\s|,|$)"
    for match in re.finditer(r"CREATE TABLE dbo\.(\w+)\s*\((.*?)\n\);", text, re.S):
        columns = []
        body = match.group(2)
        found = list(re.finditer(pattern, body, re.I))
        for i, column in enumerate(found):
            tail = body[column.end():found[i + 1].start() if i + 1 < len(found) else len(body)]
            notes = []
            if "IDENTITY" in tail: notes.append("IDENTITY")
            if "PRIMARY KEY" in tail: notes.append("PK")
            if "NOT NULL" in tail: notes.append("非空")
            if re.search(r"\bUNIQUE\b(?!\s*\()", tail): notes.append("唯一")
            if column[2].upper() == "ROWVERSION": notes.append("自动版本")
            reference = re.search(r"REFERENCES dbo\.(\w+)\((\w+)\)", tail)
            if reference: notes.append("FK → " + reference[1] + "." + reference[2])
            columns.append((column[1], column[2], "；".join(notes) or "可空或由表级约束定义"))
        result.append((match[1], columns))
    if len(result) != 15 or any(not cols for _, cols in result):
        raise RuntimeError("基础模式解析失败，停止生成数据字典")
    return result


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
    for name, size in [("Normal", 11), ("Title", 24), ("Heading 1", 16), ("Heading 2", 14),
                       ("Heading 3", 12), ("Caption", 10), ("Table Grid", 10), ("TOC 1", 11), ("TOC 2", 10)]:
        if name not in doc.styles: doc.styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        style = doc.styles[name]
        style.font.name = "Times New Roman" if name == "Normal" else "Arial"
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor(0, 0, 0)
        style.font.italic = False
        style.element.get_or_add_rPr().get_or_add_rFonts().set(qn("w:eastAsia"), "宋体" if name in ("Normal", "Caption", "Table Grid") else "黑体")
        if name.startswith("Heading"):
            style.font.color.rgb = RGBColor(0, 0, 0)
            style.paragraph_format.keep_with_next = True
            style.paragraph_format.space_before, style.paragraph_format.space_after = Pt(15), Pt(7)
    normal = doc.styles["Normal"].paragraph_format
    normal.line_spacing, normal.space_after = 1.35, Pt(5)
    doc.settings.element.append(OxmlElement("w:updateFields"))
    doc.settings.element[-1].set(qn("w:val"), "true")

    lines = source.read_text(encoding="utf-8").splitlines()
    report_title = lines[0].removeprefix("# ").replace("Reconis ", "")
    doc.core_properties.title = "Reconis " + report_title
    doc.core_properties.subject = "数据库课程设计"
    doc.core_properties.author = "、".join(m.get("name", "") for m in cover.get("members", []) if m.get("name"))
    p = doc.add_paragraph("Reconis " + report_title, "Title")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.runs[0].font.size = Pt(24)
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
    header.style = doc.styles["Caption"]
    footer = section.footer.paragraphs[0]
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
                    p.paragraph_format.keep_with_next = i < min(2, len(rows) - 1)
                    p.paragraph_format.space_after = Pt(3)
                    p.paragraph_format.space_before = Pt(3)
                    p.paragraph_format.line_spacing = 1.15
                    for r in p.runs: r.font.size = Pt(10)
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
                    key = "data-flow" if "system-design" in source.stem else "modules"
                    picture(figures[key], f"图 {chapter}.1　{'主要数据流' if key == 'data-flow' else '系统功能与技术结构'}")
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
    if "system-design" in source.stem:
        doc.add_heading("附录 A　基础表字段字典", level=1)
        table_counter = 0
        paragraph("字段字典对应 database/001_schema.sql，自动账单扩展列于附录末尾。复合键、CHECK 与过滤索引见正文第 5、6、8 章。")
        for name, columns in sql_columns():
            doc.add_heading(name, level=2)
            table([["字段", "SQL 类型", "键与约束摘要"], *[list(row) for row in columns]], f"表 A.{table_counter + 1}　{name}")
        doc.add_heading("自动账单扩展（迁移 007）", level=2)
        paragraph("reconciliation_batch 增加 acquisition_key、acquisition_state、acquisition_env、acquisition_app、acquisition_attempts、acquisition_generation、acquisition_token、acquisition_lease_until、acquisition_next_at、acquisition_message、acquisition_format、acquisition_result、acquisition_daily、acquisition_owner。唯一任务键、有限重试和租约支持恢复，不新增业务表。")
    else:
        doc.add_heading("附录 A　数据库端关键代码摘录", level=1)
        for filename, object_name, explanation in [
            ("003_views_functions_procedures.sql", "fn_refundable_amount", "成功与预留退款一并扣减，结果未知时不释放额度。"),
            ("003_views_functions_procedures.sql", "usp_approve_refund", "先锁原支付，再检查独立审批及最新额度，避免并发超退。"),
            ("004_triggers_fulltext.sql", "trg_refund_audit", "使用 inserted/deleted 集合记录审核变化，与业务事务共同提交。"),
        ]:
            doc.add_heading(object_name, level=2)
            paragraph(explanation)
            sql = (ROOT / "database" / filename).read_text(encoding="utf-8-sig")
            match = re.search(r"CREATE OR ALTER (?:FUNCTION|PROCEDURE|TRIGGER) dbo\." + object_name + r"\b.*?(?=\nGO|\Z)", sql, re.S)
            if not match: raise RuntimeError(f"缺少 SQL 对象：{object_name}")
            for line in match[0].splitlines():
                # Wrap code at token boundaries; preserve every source token.
                import textwrap
                for wrapped in textwrap.wrap(line, width=76, replace_whitespace=False, drop_whitespace=False, break_long_words=False, break_on_hyphens=False) or [""]:
                    p = doc.add_paragraph(wrapped)
                    p.paragraph_format.line_spacing = 1.05
                    p.paragraph_format.space_after = Pt(1)
                    for r in p.runs: r.font.name = "Consolas"; r.font.size = Pt(8.5)
    target.parent.mkdir(parents=True, exist_ok=True)
    doc.save(target)
    print("Report:", target, flush=True)


def pdf_export(path: Path, outdir: Path) -> None:
    office = shutil.which("libreoffice") or shutil.which("soffice")
    if not office: raise RuntimeError("缺少 LibreOffice，无法生成 PDF 预览；Word 文件已保留")
    with tempfile.TemporaryDirectory(prefix="reconis-office-") as profile:
        runtime = Path(profile) / "runtime"
        runtime.mkdir(mode=0o700)
        run([office, f"-env:UserInstallation={Path(profile).as_uri()}", "--headless", "--convert-to", "pdf", "--outdir", str(outdir), str(path)],
            env={**os.environ, "SAL_USE_VCLPLUGIN": "svp", "GSETTINGS_BACKEND": "memory", "XDG_RUNTIME_DIR": str(runtime), "XDG_CACHE_HOME": str(Path(profile) / "cache")}, timeout=150)
    if not (outdir / (path.stem + ".pdf")).exists(): raise RuntimeError(f"PDF 未生成：{path.name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/delivery")
    parser.add_argument("--presentation-skill-root", type=Path)
    parser.add_argument("--skip-pdf", action="store_true")
    parser.add_argument("--pdf-engine", choices=("auto", "office", "libreoffice"), default="auto")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    figures = diagrams()
    cover = json.loads((ROOT / "docs/reports/cover.json").read_text(encoding="utf-8"))
    generated = []
    for source, name in [("system-design", "Reconis-系统设计报告"), ("implementation-report", "Reconis-系统实现总结报告")]:
        target = output / f"{name}.docx"
        build_report(ROOT / "docs/reports" / f"{source}.md", target, figures, cover)
        generated.append(target)
    if args.presentation_skill_root:
        builder = args.presentation_skill_root.resolve() / "scripts/build_deck.py"
        if not builder.is_file(): raise RuntimeError(f"未找到 presentation-skill 构建器：{builder}")
        deck = output / "Reconis-课程答辩.pptx"
        run([sys.executable, str(builder), "--outline", str(ROOT / "docs/presentation/outline.json"), "--output", str(deck), "--style-preset", "forest-research", "--layout-16x9", "--overwrite"])
        generated.append(deck)
    else:
        print("未指定 presentation-skill 目录：本次只生成报告，PPT 源稿保留在 docs/presentation。")
    if not args.skip_pdf:
        powershell = shutil.which("powershell.exe")
        if not powershell and Path("/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe").exists():
            powershell = "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"

        def windows_path(path):
            if os.name == "nt": return str(path)
            match = re.fullmatch(r"/mnt/([A-Za-z])/(.*)", str(path))
            if match: return match[1].upper() + ":\\" + match[2].replace("/", "\\")
            return None

        office = powershell and windows_path(ROOT) and windows_path(output)
        if args.pdf_engine == "office" and not office:
            raise RuntimeError("Microsoft Office 导出需要可访问 Windows 路径及 Windows PowerShell")
        if args.pdf_engine == "office" or (args.pdf_engine == "auto" and office):
            command = [powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windows_path(ROOT / "scripts/finalize-course-documents.ps1"), "-Directory", windows_path(output)]
            if args.presentation_skill_root: command.append("-IncludePresentation")
            run(command, timeout=240)
        else:
            for target in generated: pdf_export(target, output)
    files = sorted(path for path in output.iterdir() if path.suffix in (".docx", ".pdf", ".pptx"))
    (output / "SHA256SUMS.txt").write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files), encoding="utf-8")


if __name__ == "__main__":
    main()
