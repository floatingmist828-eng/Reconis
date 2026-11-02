# Reconis 课程交付说明

更新：2026-11-02。课题：校园多商户跨日支付对账与差错协同处理系统。

## 交付文件

| 内容 | 位置 |
| --- | --- |
| 系统设计报告 | docs/reports/final/Reconis-系统设计报告.docx、.pdf |
| 系统实现总结报告 | docs/reports/final/Reconis-系统实现总结报告.docx、.pdf |
| 答辩文件 | docs/presentation/final/Reconis-课程答辩.pptx、.pdf |
| 源程序与建库文件 | backend、frontend、database、scripts、samples |
| 课程交付压缩包 | artifacts/delivery/Reconis-课程交付包.zip |
| 本机运行程序 | artifacts/releases/20261102-delivery-final/reconis-server.jar |
| 数据库备份 | SQL Server 默认 Backup 目录中的 ReconisDemo-20261102-204830.bak |
| 证据原件 | artifacts/delivery/private-database/ReconisDemo-files.zip |
| 业务运行记录 | artifacts/validation/V202611021226132e11a7.json |

两份报告采用 A4 页面、黑体标题与自动目录，按“标题、目录、正文”编排。源稿位于 docs/reports。答辩文件使用原生文本、表格和图表，可在 PowerPoint 中编辑。

## 本机演示

运行根目录的“启动Reconis.cmd”，打开 http://localhost:8080。账号名称与初始密码保存在 private/demo-accounts.txt。

建议按以下顺序演示：

1. 财务工作台切换商户和时间范围，进入退款与工单待办。
2. 查看订单、支付确认和退款，说明金额约束与跨日归属。
3. 导入 Excel 或 XML 账单，查看预览、分类和两侧证据。
4. 比较历史批次，派发工单，补充证据并由另一人复核。
5. 检索已结案案例，展示推荐、统计钻取、报告导出与审计。
6. 在支付接入页创建账期任务，查看自动获取进度与原件。

## 在其他电脑部署

准备 PowerShell 7.4+、JDK 21、Node 24、SQL Server 2022 Developer 及全文组件。

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1
.\scripts\install-sqlserver.ps1  # 管理员 PowerShell
.\scripts\initialize.ps1
.\scripts\start.ps1
```

已有数据库先备份，再执行 scripts/migrate.ps1。恢复 .bak 后重新映射本机运行登录，配置 private 下的环境文件，并恢复对应 data 目录。DEMO 与 ALIPAY_SANDBOX 使用各自的数据库、文件目录和渠道配置。

## 报告重建与源码打包

```powershell
python scripts/build-course-delivery.py --output docs/reports/final --skip-pdf
powershell -File scripts/finalize-course-documents.ps1 -Directory docs/reports/final
python scripts/package-course-delivery.py --materials artifacts/delivery
```

生成 Word 后，finalize-course-documents.ps1 调用本机 Word 更新目录、页码并导出 PDF。交付包汇集源码、SQL、样例和正式材料；数据库备份与证据原件另行保存。GitHub 保存源代码、报告和答辩文件，private、data、工具及数据库备份留在本机。

## 运行记录

SQL Server 实测为 15 张基础表、5 个业务过程、2 个函数和9个触发器，中文全文索引就绪。26组 DEMO 业务流程通过，覆盖支付恢复、退款审批、导入与逐笔对账、工单复核、全文与推荐、统计关联、自动账单、归档和导出。桌面与手机页面已分别检查。

支付宝沙箱按支付接入页的应用配置运行。公网回调地址在沙箱环境文件中配置；实际账单按渠道提供的账期下载。教学演示使用 DEMO 数据与已归档样例。
