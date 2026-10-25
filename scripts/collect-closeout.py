#!/usr/bin/env python3
"""Summarize existing verification outputs without inventing execution results."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--surefire-directory", type=Path, default=ROOT / "backend/target/surefire-reports")
    args = parser.parse_args()
    validation = ROOT / "artifacts/validation"
    validation.mkdir(parents=True, exist_ok=True)
    checks = []

    def add(name, status, detail, evidence=None):
        checks.append(dict(name=name, status=status, detail=detail, **({"evidence": evidence} if evidence else {})))

    def log(name):
        path = validation / name
        return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""

    frontend = log("frontend-tests.log")
    count = re.search(r"(?:ℹ|#)\s*tests\s+(\d+)", frontend)
    failures = re.search(r"(?:ℹ|#)\s*fail\s+(\d+)", frontend)
    if count and failures:
        add("前端现有断言", "PASSED" if failures[1] == "0" else "FAILED", f"{count[1]} 项断言，{failures[1]} 项失败；筛选钻取与金额比例", "frontend-tests.log")
    else: add("前端现有断言", "NOT_RUN", "未找到含测试汇总的本次日志")
    web_build = log("frontend-build.log")
    add("前端类型检查与生产构建", "PASSED" if re.search(r"built in", web_build) else "NOT_VERIFIED", "npm run build；TypeScript 检查及 Vite 生产打包" if "built in" in web_build else "未发现成功构建日志", "frontend-build.log")
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = list(args.surefire_directory.glob("TEST-*.xml"))
    for report in reports:
        root = ET.parse(report).getroot()
        for key in totals: totals[key] += int(root.attrib.get(key, 0))
    backend_log = log("backend-build.log")
    if reports and "BUILD SUCCESS" in backend_log:
        status = "PASSED" if totals["failures"] == totals["errors"] == 0 else "FAILED"
        executed = totals["tests"] - totals["skipped"]
        detail = f"共 {totals['tests']} 项，实际执行 {executed} 项；失败 {totals['failures']}、错误 {totals['errors']}、跳过 {totals['skipped']}；JDK 21 / Maven 3.9.11，JAR 打包完成"
    elif "BUILD FAILURE" in backend_log:
        status, detail = "FAILED", "构建失败；详见日志，不以已有测试报告代替本次构建成功"
    else:
        status, detail = "NOT_VERIFIED", "本次后端构建尚无完整成功结果"
    add("后端构建与现有测试", status, detail, "backend-build.log")
    runtime_file = validation / "windows-runtime.json"
    runtime = json.loads(runtime_file.read_text()) if runtime_file.exists() else None
    if runtime and not runtime.get("sqlServices") and not runtime.get("listeners"):
        add("数据库与浏览器业务验收", "NOT_RUN", "Windows 检查无 MSSQL 服务及 1433/8080/8081 监听；当前目录无配套数据库及私有配置", "windows-runtime.json")
    else:
        add("数据库与浏览器业务验收", "NOT_VERIFIED", "需要另行运行真实 SQL 与浏览器验收；健康接口和单元测试不能替代")
    qa_path = validation / "pptx-qa/qa_report.json"
    if qa_path.exists():
        qa = json.loads(qa_path.read_text())
        good = not any(qa.get(key, 0) for key in ("overflow_count", "overlap_count", "geometry_error_count", "geometry_warning_count", "issue_shape_count")) and not qa.get("placeholder_hits")
        add("PPT 结构检查", "PASSED" if good else "FAILED", f"{qa.get('expected_slide_count', 0)} 页；溢出 {qa.get('overflow_count', 0)}、重叠 {qa.get('overlap_count', 0)}、几何错误/警告 {qa.get('geometry_error_count', 0)}/{qa.get('geometry_warning_count', 0)}", "pptx-qa/qa_report.json")
    else: add("PPT 结构检查", "NOT_RUN", "缺少 qa_report.json")
    review = validation / "material-checks.json"
    if review.exists():
        result = json.loads(review.read_text())
        add("材料内容与原生渲染", result["status"], result["summary"], "material-checks.json")
    else: add("材料内容与原生渲染", "NOT_VERIFIED", "等待 Word/PowerPoint 导出与逐页检查")
    add("真实支付宝签名通知", "PENDING_EXTERNAL", "2026-10-22 历史记录为 0 条；待公网 HTTPS 443 接入并以新支付验收")
    add("实际渠道账单", "PENDING_EXTERNAL", "历史下载为 TEMPLATE_ONLY；待渠道提供含实际交易的账单")
    record = {
        "schemaVersion": 1,
        "checkedAt": datetime.now().astimezone().isoformat(),
        "source": "current working tree; original execution outputs retained",
        "backendTotals": totals if reports else None,
        "checks": checks,
    }
    (validation / "closeout.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for check in checks: print(f"{check['status']}: {check['name']} — {check['detail']}")


if __name__ == "__main__":
    main()
