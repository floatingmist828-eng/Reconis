# Reconis 答辩 PPT 源稿

14 页答辩材料依据当前源码、课程要求及带日期的历史实施记录编写。outline.json 是幻灯片的事实源；content_plan、design_brief、evidence_plan 与 asset_plan 分别保存叙事、设计、证据和资产选择。历史截图位于 ../assets，关系图的 PNG/SVG 由 scripts/build-course-delivery.py 生成。

从项目根目录运行：

```bash
python3 scripts/build-course-delivery.py --presentation-skill-root <presentation-skill目录>
```

脚本调用技能自带的 scripts/build_deck.py，使用 forest-research 风格；当前技能环境未安装 PptxGenJS，采用已有 Python 构建器。无需安装新的演示依赖。生成 Word、PPT 及 PDF 后，运行 scripts/package-course-delivery.py 打包。完整运行条件见 ../handover.md。

构建器保留表格、图表和文字对象；界面截图与关系图为图片，关系图另有可编辑 SVG 源文件。修改 outline.json 后重建，不直接补丁生成的 PPTX。图表数据 100/-30 元为固定教学场景。学校、成员与贡献由 ../reports/cover.json 提供，不编造身份。

交付前运行技能的 qa_gate.py 对 PPTX 检查几何与可读性，使用本机 PowerPoint 导出 PDF 后逐页检查；本次检查结果放在 artifacts/validation。真实签名通知、实际交易账单和目标电脑现场演示的状态以交付说明为准。
