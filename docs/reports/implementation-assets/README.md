# 最终实践总结报告源文件与配套图表

本目录不与初期设计报告共享图片。12 张结构/流程图同时提供 SVG 和 PNG，图内不重复图号题注；正文应在相邻位置添加编号并引用。

- 统一物理宽度 6.3 英寸，PNG 为 300 dpi（1890 像素宽）。主要文字 9.1–10 pt，少数英文长标识/注释 8.8–9 pt。
- 使用冷白、黑灰、深蓝，全部为矢量构图；PNG 与 SVG 由同一绘制函数生成。
- ER 字段和显式外键连接自动核对 `../evidence/20261104/schema-summary.json`，实体主键/可空标记直接来自真实数据库元数据。总览省略辅助连线，分域图给主外键及外域引用，完整外键清单位于数据库证据附件。
- `figures-manifest.json` 给出实际尺寸；插入 Word 时保留比例，不应将长图压扁。资金 ER 高 8.61 英寸，适合单页；其他图按章节布局插入。
- 联系表和逐页渲染仅用于本地视觉检查，不纳入仓库或正式报告。

复现：

```powershell
python docs/reports/implementation-assets/build_figures.py
```

脚本依赖 Pillow 和本机 Microsoft YaHei 字体；只写本目录的图表和清单，不修改源码、数据库或设计报告。`two-clock-timeline` 明确标注为解释统计口径的示例，不冒充本次经营数据。

本次已实际打开 12 张图的联系表，检查中文、大小比例、箭头路径、边界和编号归属，随后单独检查 ER 资金图、对账图、工单状态图及时间轴。未发现裁切、文字重叠或连线穿字。

## 报告构建与检查

`drafts/` 保存经核验的章节片段；`assemble_report.py` 组合片段、运行截图及交叉引用，写入实际正文源文件 `../implementation-report.md`。`build_report.py` 将正文生成可编辑的原生 Word 段落、表格和目录字段。直接修改最终 Word 后再运行构建会覆盖这些手工修改。

按顺序运行以下入口：

1. `assemble_report.py`：重组正文。
2. `build_report.py`：生成 `../final/Reconis-系统实现总结报告.docx`。
3. `finalize_report.ps1`：用 Microsoft Word 更新目录、页码并导出同名 PDF，同时核对初期设计报告未变化。
4. `render_final_pdf.py`：将最终 PDF 渲染为逐页图片和联系表。
5. `validate_report.py`：核验目录目标、页码、图表数量、字体嵌入和初期设计文件散列；结果保存于 `../evidence/20261106-language-edit/final-artifact-check.json`。仓库不包含本机备份ZIP；其他电脑无法复验该历史备份时会明确记录 `ARCHIVE_UNAVAILABLE`，已完成的只读核验记录保存在 `../evidence/20261106/initial-design-preservation.json`。

图像可读性、自然分页及图文关系仍须实际逐页检查，不能由结构检查代替。工作用要求核对表和历史测试在 `../evidence/20261104/`，最终界面回归在 `../evidence/20261106/`，语言编辑定稿的检查记录在 `../evidence/20261106-language-edit/`，不纳入正式报告正文。此流程只生成最终实践报告，不调用会同时覆盖初期设计报告的旧课程打包脚本。

字体规则由 `../typography/report_typography.py` 与构建器共同维护。标题采用黑体/Arial，一级16 pt、二级13 pt；正文宋体/Times New Roman 11 pt，图表题注和表内文字9.5 pt。含长英文标识或多词SQL语句的正文采用左对齐，减少两端对齐造成的中文间距扩张。字号差异仅用于区分标题层级、正文、表格和代码。

字体复核后的报告仍为73页。当前检查可执行 `validate_report.py --evidence-dir docs/reports/evidence/20261106-typography/summary`；双报告字体实测与逐页渲染入口见 `../typography/README.md`。
