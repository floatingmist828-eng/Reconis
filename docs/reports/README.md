# Reconis 系统实践总结报告

## 提交文件

- `final/Reconis-系统实现总结报告.docx`：可编辑 Word，内含已更新的目录和页码。
- `final/Reconis-系统实现总结报告.pdf`：由同一份 Word 导出的打印版。

本次以 `category-artwork-20261106-r2` 为界面定稿版本，更新18张运行截图、相关说明、最终版本信息和回归证据。12张结构与流程图保留；业务实例沿用11月4日验证记录，截图采集日期为11月6日。

## 源文件与附件

- `implementation-report.md`：报告正文源文件。
- `implementation-assets/`：构建脚本、图表、原始截图和裁切清单。
- `evidence/20261104/`：完整业务、数据库与规模实验的历史证据。
- `evidence/20261106/`：最终界面版本回归记录、截图更新后的文档检查及逐页检查记录。逐页渲染缓存由脚本在本地生成，不纳入仓库。

此目录保存最终实践总结报告及可编辑源文件。Word、PDF和正文素材与2026年11月6日课程目录交付版一致。初期系统设计报告仍单独保存，未恢复进此目录，也未修改。

## 重新生成

依次运行 `implementation-assets/build_report.py`、`implementation-assets/finalize_report.ps1`、`implementation-assets/render_final_pdf.py` 和 `implementation-assets/validate_report.py`。Python脚本使用带有python-docx、Pillow、pypdfium2、pdfplumber和pypdf的文档运行环境；PowerShell脚本调用Microsoft Word更新域与导出PDF。排版后需重新检查实际渲染页面。
