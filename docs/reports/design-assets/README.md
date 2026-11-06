# 系统设计报告源文件与构建

`../system-design.md` 保存正文。`appendix-intro.md` 和 `acquisition-extension.md` 保存附录说明，`schema-fields.json` 保存初期设计的 15 张表、193 个基础字段及约束摘要。后续语言编辑需直接更新这些源文件。

字段字典和 `diagrams/` 中的四幅图沿用初期报告，来源及散列记录于 `source-provenance.json`。构建器不读取当前系统代码或数据库脚本，避免重新生成时将最终实现倒写为初期方案。

使用带有 python-docx、Pillow 的文档运行环境执行：

```powershell
python -X utf8 docs/reports/design-assets/build_report.py
& 'docs/reports/design-assets/finalize_report.ps1'
python -X utf8 docs/reports/design-assets/render_final_pdf.py
python -X utf8 docs/reports/design-assets/validate_report.py
```

生成文件为 `../final/Reconis-系统设计报告.docx`。目录域与页码需通过 Microsoft Word 更新，并从同一份 Word 导出 PDF，再渲染检查全部页面。脚本不生成或修改实践总结报告。

导出 PDF 后执行 `validate_report.py`，检查正文一致性、目录定位、页码、表格数据、图像散列与字体嵌入，并将结果写入 `../evidence/20261106-design-language-edit/final-artifact-check.json`。本地存在原归档时，同时核对标题、图表代码和初期字段字典。逐页视觉检查单独进行。

`finalize_report.ps1` 只打开设计报告，并核对同目录实践总结报告的散列保持不变。结构核验和逐页检查记录在 `../evidence/20261106-design-language-edit/`。

排版保留 A4 页面和章节结构，字体通过 `../typography/report_typography.py` 显式设置：一级标题16 pt、二级标题13 pt，中文黑体、英文与编号Arial；正文宋体/Times New Roman 11 pt，图表题注和表内文字9.5 pt，页眉页脚9 pt。文档语言为简体中文，清除会覆盖显式字体的主题引用。作者元数据采用小组已确认的三位成员姓名；封面内容及设计阶段的技术内容保持原样。

2026年11月6日字体复核后的设计报告为21页。执行 `validate_report.py --evidence-dir docs/reports/evidence/20261106-typography/design` 可把当前检查写入字体复核目录；统一字体检查与双报告渲染见 `../typography/README.md`。
