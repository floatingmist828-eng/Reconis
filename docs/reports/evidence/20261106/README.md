# 2026年11月6日最终界面版本证据

发布版本：`category-artwork-20261106-r2`。此目录是最终报告的证据附件，不属于正式报告正文。11月4日完整数据库、HTTP业务及规模实验记录保留在相邻的`../20261104`，不与本次结果合并计数。

| 文件 | 内容与来源 |
| --- | --- |
| release-manifest.json | 项目`artifacts/releases/category-artwork-20261106-r2/manifest.json`的逐字节副本；含发布标识、源码与JAR散列、构建测试统计。 |
| build-r2.log | 项目`artifacts/category-artwork-build-r2.log`的逐字节副本；前端9项通过、类型检查及构建成功，后端118项执行通过、72项跳过。 |
| browser-r2.log | 项目`artifacts/category-artwork-browser-r2.log`的逐字节副本；11项浏览器测试全部通过。 |
| browser-results.json | 项目`artifacts/validation/browser-results.json`的逐字节副本；运行起点2026-11-06T12:25:06.302Z，11项预期通过，无失败、跳过和不稳定结果。 |
| visual-check.json | 项目`artifacts/category-artwork-20261106/visual-check.json`的逐字节副本；1440/1280/390宽度及页面操作检查，修复后浏览器错误列表为空。内部截图文件名相对于原项目证据目录。 |
| backend-test-summary.json | 由最终版本构建快照下Surefire XML逐套件提取的统计；同时保留每份XML源路径和SHA256。 |
| test-summary.json | 从本目录原始构建日志、发布清单及浏览器JSON提取的汇总，以及复制文件的来源和两侧SHA256。 |
| initial-design-preservation.json | 直接读取既有ZIP内三份初期设计文件，与11月4日记录的SHA256比较；三项一致，均未恢复进最终报告目录。不声称已验证任何未访问的外部现存初期报告。 |

本次未重新执行72项数据库集成测试，未重跑规模实验，也未新增支付宝沙箱联调。初期设计内容只读核对，备份ZIP保持原样。

最终报告的逐页渲染写入`render-final`；正式文档结构检查写入`final-artifact-check.json`。这些文件在最终Word/PDF重新生成后执行相应脚本产生，视觉核验记录另保存为`visual-qa-final-*.md`。
