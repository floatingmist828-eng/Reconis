# Reconis 课程交付说明

整理日期：2026-10-25—26。课题为《校园多商户跨日支付对账与差错协同处理系统的设计与实现》。

## 交付内容

| 内容 | 位置与说明 |
| --- | --- |
| 系统设计报告 | docs/reports/system-design.md；生成 Word、PDF，包含需求、数据流、E-R、关系模式与 3NF |
| 系统实现总结报告 | docs/reports/implementation-report.md；生成 Word、PDF，包含模块、数据库端程序与历史运行画面 |
| 答辩 PPT | docs/presentation/outline.json 为可编辑结构化源稿；生成 .pptx 与 PDF |
| 演示与答辩讲稿 | docs/reports/demo-and-defense.md |
| 源程序及数据库重建文件 | backend、frontend、database、scripts；包含教学种子和 006–010 迁移 |
| 本次验证 | artifacts/validation/closeout.json 与同目录日志，区分通过、跳过和缺失条件 |
| 本次重建的应用 | backend/target/reconis-server-1.0.0.jar，包含前端资源；运行仍需 JDK 21、SQL Server 及本机配置，源码提交包不内嵌该二进制文件 |
| 生成材料与提交包 | artifacts/delivery，附 SHA256 清单；不含密钥、账号密码和真实数据库原件 |

学校、班级、姓名、学号、指导教师及实际分工由 docs/reports/cover.json 提供；空白项在 Word 封面保留待填栏，不编造成员或工作量。没有学校模板时采用统一 A4 报告格式，正式提交前按学校要求调整。

## 当前证据

核心功能均有源码入口。2026-10-22 的历史实施记录记载完整 DEMO 闭环和真实沙箱支付、查询、退款成功；原始日志、JAR、数据库备份与私有配置在忽略目录中，未随本次源码复制。该记录不能直接证明当前目录可以启动。

本次构建与测试的实际输出保存在 artifacts/validation。数据库集成测试需要 SQL Server，跳过数必须如实保留；不能把普通单元测试通过写成数据库业务验收通过。报告内历史截图来自 docs/assets，不冒充本次新截图。

本次前端类型检查、生产构建及 5 项现有断言通过；后端 190 项中实际执行 118 项，0 失败、0 错误，72 项数据库测试跳过。环境准备脚本已改为检查 JDK 21 与编译器，避免把已有 JRE 8/Java 17 当作准备完成；检测函数已在 Windows 检查，未执行新的 Windows 软件安装。

## 材料重建

1. Python 3 环境需已有 python-docx、matplotlib、Pillow。运行 `python3 scripts/build-course-delivery.py` 生成两份 Word 报告、配图及数据字典。Windows/WSL 下优先调用本机 Microsoft Word 原生导出 PDF；也可指定 `--pdf-engine libreoffice`，要求安装 Writer 组件。脚本不安装依赖。
2. PPT 依照 presentation-skill 的仓库构建器生成。运行 `python3 scripts/build-course-delivery.py --presentation-skill-root <presentation-skill目录>`，使用其中的 scripts/build_deck.py 与 scripts/qa_gate.py；结构化源稿保留在 docs/presentation。没有该技能目录时，可先生成报告并保留 PPT 源稿，命令会明确报告缺失条件。
3. 构建日志与 Surefire XML 生成后，运行 `python3 scripts/collect-closeout.py` 汇总本次结果；脚本只读取已经存在的执行输出，不会将未执行项目写成通过。再重建报告，使实现报告附录包含本次结果。
4. 运行 `python3 scripts/package-course-delivery.py`，打包当前工作区的项目源码、数据库脚本及生成材料。清单记录逐文件 SHA256；未提交的项目文件也会纳入，private、data、工具、依赖和旧 artifacts 不会自动加入。

也可先加 `--skip-pdf` 只生成 Word/PPT，再在 Windows PowerShell 运行 `scripts/finalize-course-documents.ps1 -IncludePresentation`。该脚本仅处理交付目录中的两份报告与 PPT，打开前禁用宏，更新字段后导出 PDF；不关闭其他已打开的文档。

## 运行与演示恢复

只有源码时，先按 README 准备 Windows、PowerShell 7.4+、JDK 21、Node 24 及 SQL Server 2022 Developer 全文组件。执行 bootstrap、build、initialize、migrate 后再启动；已有数据库先备份，不重新清库。DEMO 与沙箱分别使用数据库、文件目录和本机配置。

从原运行电脑取回时，需要配套保存：选定版本的 JAR/manifest、两库 bak、data/demo 与 data/sandbox、private 中本机配置及沙箱密钥。数据库备份先执行 RESTORE VERIFYONLY，恢复到新实例后重新映射运行登录；按运行说明配置新密码与本机路径。这些私有文件通过课程指定的私有渠道提交，不放入公开源码包。

恢复后依次完成健康接口、全文索引状态、接口业务链与浏览器验收。演示前检查各角色账号、100/30 元跨日样例、带差错的教学账单、已解决案例和可供独立复核的证据。

## 尚需完成的验收

| 项目 | 已知状态 | 完成依据 |
| --- | --- | --- |
| 真实签名通知 | 2026-10-22 记录为 0 条 | 公网 HTTPS 443 转发至沙箱回调路径；新支付产生有效通知，落库且重放不重复记账 |
| 实际渠道账单 | 下载结果为 TEMPLATE_ONLY | 渠道提供含实际交易的账单，核对原件、归属、时间、金额与对账结果 |
| 目标电脑现场演示 | 当前源码目录缺少原运行环境 | 数据库与文件恢复后重跑业务及浏览器验收，保存新日志和截图 |
| 团队与封面信息 | 待成员提供 | 填写真实学校、成员身份及独立设计实现内容 |

不需要重新提供已配置过的沙箱账号与密钥；只有在无法取回原私有目录时才需重新配置。无公网通知时可以演示主动查询恢复，但应在答辩中如实说明通知验收状态。
