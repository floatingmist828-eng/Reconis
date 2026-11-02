# 课程要求与源码入口

本表对应指导书基础及高级功能。2026-11-02 已完成本地 SQL Server 升级与 26 组 DEMO 业务联调，记录见 implementation-status.md。

| 编号 | 要求 | 页面与实现 |
| --- | --- | --- |
| B1 | 关系数据增删改查 | 商户/门店/成员、订单草稿；MerchantController、OrderController，引用数据停用，事实不可改 |
| B2 | 首页/列表/详情/多条件/排序/分页/跳转 | 分角色概览、商户与7/30/90日筛选、实时待办、手机订单卡片；Db.page计数和OFFSET/FETCH |
| B3 | 实际业务 | 开单→收款→退款→对账→工单，PaymentService与5个usp过程 |
| B4 | 多角度统计/表格图形/PDF或Word | AnalyticsService共用口径，ECharts、JFreeChart、PDFBox和POI双报告 |
| B5 | 多角色/注册审核/权限分配/登录 | IdentityController、Access、SecurityConfig，平台/商户分层，BCrypt+Session+CSRF |
| A1 | Excel或者XML导入导出 | 两种均实现，ImportService、ExportController；模板、预览、提交、错误反馈、SHA256 |
| A2 | 高级用户审核数据 | 商户入驻和proposed_*候选审核，现行资料继续有效，审核历史写审计 |
| A3 | 自动获取数据 | 定时交易/退款恢复查询、自动账单任务、账期补取、重试与原件归档；支持账单预览和后续对账 |
| A4 | 全文检索 | FREETEXTTABLE、2052中文全文索引、相关度与索引状态；不以LIKE替代全文 |
| A5 | 数据比较 | 本地/渠道证据双栏，baseline/current逐事件变化，历史不可覆盖 |
| A6 | 数据推荐 | 仅已结案可见案例，类型/环节/文本相关度及解释，排除自身与未解决问题 |
| A7 | 审核和任务分派 | 退款独立审批与数据库额度检查；工单分派/处理/证据/提交/独立复核/退回 |
| A8 | 粒度钻取 | 商户→门店→流水，年→月→日→流水，保留筛选及返回路径 |
| A9 | 数据关联统计 | 差错→结果→账单/流水→支付退款→订单→门店→商户，分组数量/金额/处理时长 |
| A10 | 访问/操作日志检索统计 | RequestAuditFilter与审核触发器，人员/商户/类别/结果/时间筛选与统计 |

指导书的全文/比较/推荐为替代项，本项目主动全部覆盖；Excel/XML和PDF/Word也是主动双格式覆盖。

| 其他要求 | 材料 |
| --- | --- |
| 12–15个实体（含联系） | 001_schema.sql共15表；design.md区分概念对象与物理关系 |
| 3NF | reports/system-design.md逐关系候选键与依赖 |
| 服务器端过程/触发器/函数 | 003/004脚本，业务接口实际调用usp |
| 外模式与授权 | 消费者/商户/对账/案例/审计视图，运行和审计角色，同一连接会话上下文清理 |
| 两份报告 | reports/final 中的正式 Word 与 PDF，黑体标题，标题—目录—正文；含字段字典、3NF与关键 SQL |
| 演示及申优 | presentation/final 中的14页可编辑 PPT与PDF，docs/assets 本轮运行画面，demo-and-defense.md 演示路线 |
| 源程序与数据库 | 前后端、版本 SQL、种子、迁移、备份脚本；本机数据库已备份并通过 RESTORE VERIFYONLY |
