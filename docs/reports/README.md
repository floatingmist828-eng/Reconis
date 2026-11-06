# Reconis 课程报告

## 提交文件

- `final/Reconis-系统实现总结报告.docx`：可编辑 Word，内含已更新的目录和页码。
- `final/Reconis-系统实现总结报告.pdf`：由同一份 Word 导出的打印版。
- `final/Reconis-系统设计报告.docx`：保留初期方案的可编辑 Word。
- `final/Reconis-系统设计报告.pdf`：与设计报告 Word 一致的打印版。

本次以 `category-artwork-20261106-r2` 为界面定稿版本，更新18张运行截图、相关说明、最终版本信息和回归证据。12张结构与流程图保留；业务实例沿用11月4日验证记录，截图采集日期为11月6日。

语言编辑定稿清理了预设反驳、重复限制声明和编校口吻，测试日期与数量集中到第9章。正文与章节源稿同步；全部截图、结构图和技术数据保留。最终 Word/PDF 为73页，含30幅图、38张表，目录和逐页版面已核验。

## 源文件与附件

- `implementation-report.md`：报告正文源文件。
- `system-design.md`：初期系统设计报告源文件。
- `design-assets/`：设计报告专用构建脚本、原有图表及冻结的字段字典。
- `implementation-assets/`：构建脚本、图表、原始截图和裁切清单。
- `evidence/20261104/`：完整业务、数据库与规模实验的历史证据。
- `evidence/20261106/`：最终界面版本回归记录、截图更新后的文档检查及逐页检查记录。逐页渲染缓存由脚本在本地生成，不纳入仓库。
- `evidence/20261106-language-edit/`：本次语言编辑的工作记录、内容完整性检查、最终文档检查和逐页视觉检查。此前两组日期目录保留各自的历史结果。

两份报告均已完成语言编辑。初期设计从原有归档恢复，保留其方案、数据结构和图表，未按最终实现重写；编辑记录与检查结果保存在 `evidence/20261106-design-language-edit/`。Word、PDF和正文素材与2026年11月6日课程目录交付版一致。此前的检查记录保留各自的范围和结果。

字体排版复核已修正设计报告的日文字体回退，并统一两份报告的正文、标题、图表题注和页眉页脚字体规则。当前设计报告为21页，实践总结报告为73页。格式源文件在 `typography/`；字体实测、内容完整性、逐页视觉检查和交付副本核对记录在 `evidence/20261106-typography/`。本次排版不改动正文源稿、技术数据或图像素材。

## 重新生成

依次运行 `implementation-assets/build_report.py`、`implementation-assets/finalize_report.ps1`、`implementation-assets/render_final_pdf.py` 和 `implementation-assets/validate_report.py`。Python脚本使用带有python-docx、Pillow、pypdfium2、pdfplumber和pypdf的文档运行环境；PowerShell脚本调用Microsoft Word更新域与导出PDF。排版后需重新检查实际渲染页面。

设计报告使用 `design-assets/` 中的同名专用脚本，具体命令见该目录的 `README.md`。其附录读取冻结字典，不从当前数据库重新推导初期方案。
