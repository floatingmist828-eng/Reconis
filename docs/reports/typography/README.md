# 两份报告的字体与排版

`report_typography.py` 由设计报告和实践总结报告构建器共同使用，显式写入字体、字号和语言，清除主题字体引用，并同步复杂文字字号和关联字符样式。

| 用途 | 中文 / 英文与数字 | 字号 |
|---|---|---|
| 一级标题 | 黑体 / Arial | 16 pt |
| 二级标题 | 黑体 / Arial | 13 pt |
| 三级标题 | 黑体 / Arial | 11.5 pt |
| 正文 | 宋体 / Times New Roman | 11 pt |
| 表格及图表题注 | 宋体 / Times New Roman | 9.5 pt |
| 页眉页脚 | 宋体 / Times New Roman | 9 pt |
| 实践总结中的代码块 | 宋体 / Consolas | 8.5 pt |

封面字号按各报告的既有构图设置。同一级标题采用同一规则，不将不同级别标题或正文、表格强制设为同一字号。默认东亚语言为 `zh-CN`。

## 生成与检查

使用包含 python-docx、Pillow、pypdfium2、pdfplumber 和 pypdf 的 Python 环境。先依次运行两份报告各自的 `build_report.py`、`finalize_report.ps1`，后者使用 Microsoft Word 更新目录与页码，再从同一 Word 导出 PDF。两个 Word 导出操作应顺序运行。

```powershell
python -X utf8 docs/reports/typography/render_reports.py
python -X utf8 docs/reports/typography/validate_typography.py
python -X utf8 docs/reports/design-assets/validate_report.py --evidence-dir docs/reports/evidence/20261106-typography/design
python -X utf8 docs/reports/implementation-assets/validate_report.py --evidence-dir docs/reports/evidence/20261106-typography/summary
```

`render_reports.py` 以140 dpi渲染两个最终PDF，逐页图片放入字体复核证据目录下的 `render-design/`、`render-summary/`。渲染缓存不纳入仓库。`validate_typography.py` 同时检查 Word 样式、实际 PDF 字符的字体与字号、标题数量及字体嵌入；分页、表格、图题和字距仍需实际查看全部页面。

字体修复不修改正文源稿或图像素材。重新生成时必须保留共享字体文件，避免模板的主题字体或语言设置再次覆盖标题字体。
