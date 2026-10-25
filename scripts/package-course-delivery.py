#!/usr/bin/env python3
"""Package the current safe project tree and generated course materials."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = {"backend", "frontend", "database", "docs", "scripts", "samples"}
SOURCE_FILES = {"README.md", ".env.example", ".gitignore", ".gitattributes", "启动Reconis.cmd"}
EXCLUDED_PARTS = {"node_modules", "target", "dist", "__pycache__", "private", "data", ".tools", ".git", ".codex", ".aws", ".agents", "playwright-report", "test-results", ".vite", "build"}
EXCLUDED_SUFFIXES = {".pyc", ".bak", ".mdf", ".ldf", ".pem", ".key", ".p12", ".pfx", ".log", ".jar", ".class"}
VALIDATION_FILES = ("closeout.json", "frontend-build.log", "frontend-tests.log", "backend-build.log", "material-build.log", "material-checks.json", "windows-runtime.json", "bootstrap-check.json", "pptx-qa/qa_report.json", "pptx-qa/manual_review_passed.flag")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_allowed(relative: str) -> bool:
    path = PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts or any(part in EXCLUDED_PARTS for part in path.parts): return False
    if path.parts[0] not in SOURCE_ROOTS and relative not in SOURCE_FILES: return False
    if any(part.startswith(".env") and part != ".env.example" for part in path.parts): return False
    if path.suffix.lower() in EXCLUDED_SUFFIXES: return False
    if relative.startswith("docs/presentation/assets/style_reference/"): return False
    if relative in {"docs/presentation/deck_start_packet.json", "docs/presentation/agent_brief.json", "docs/presentation/style_contract.json", "docs/presentation/assets/icons/starter_icon.png"}: return False
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/delivery/Reconis-课程交付包.zip")
    parser.add_argument("--materials", type=Path, default=ROOT / "artifacts/delivery")
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    filenames = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    entries: dict[str, bytes] = {}
    for relative in sorted(set(filter(None, filenames))):
        if not source_allowed(relative): continue
        path = ROOT / relative
        if path.is_symlink() or not path.is_file(): continue
        if not path.resolve().is_relative_to(ROOT): raise RuntimeError(f"文件超出项目目录：{relative}")
        entries["source/" + relative] = path.read_bytes()
    expected = ["Reconis-系统设计报告.docx", "Reconis-系统设计报告.pdf", "Reconis-系统实现总结报告.docx", "Reconis-系统实现总结报告.pdf", "Reconis-课程答辩.pptx", "Reconis-课程答辩.pdf"]
    for name in expected:
        path = args.materials / name
        if not path.is_file(): raise RuntimeError(f"缺少生成材料，停止打包：{path}")
        entries["materials/" + name] = path.read_bytes()
    validation_root = ROOT / "artifacts/validation"
    if not (validation_root / "closeout.json").is_file(): raise RuntimeError("缺少本次验证记录，停止打包")
    for name in VALIDATION_FILES:
        path = validation_root / name
        if path.is_file(): entries["validation/" + name] = path.read_bytes()
    entries["交付说明.md"] = (ROOT / "docs/handover.md").read_bytes()
    manifest = {
        "schemaVersion": 1,
        "sourceMode": "current-working-tree-including-uncommitted-project-files",
        "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "databaseBackupIncluded": False,
        "applicationJarIncluded": False,
        "privateConfigurationIncluded": False,
        "files": [{"path": name, "bytes": len(data), "sha256": sha(data)} for name, data in sorted(entries.items())],
    }
    entries["manifest.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    entries["SHA256SUMS.txt"] = "".join(f"{sha(data)}  {name}\n" for name, data in sorted(entries.items())).encode()
    temporary = output.with_suffix(output.suffix + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(entries.items()): archive.writestr(name, data)
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None: raise RuntimeError("ZIP 完整性校验失败")
            if len(archive.namelist()) != len(set(archive.namelist())): raise RuntimeError("ZIP 内有重复路径")
            for item in manifest["files"]:
                if sha(archive.read(item["path"])) != item["sha256"]: raise RuntimeError(f"归档散列不一致：{item['path']}")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    digest = sha(output.read_bytes())
    output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n", encoding="utf-8")
    print(f"已验证 {len(entries)} 个归档文件：{output}\nSHA256：{digest}")


if __name__ == "__main__":
    main()
