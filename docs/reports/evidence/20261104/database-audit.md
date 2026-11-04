# 最终数据库实现核验资料

## 1 核验范围和口径

核验日期为 2026 年 11 月 4 日。证据来自当前工作区 `database/001_schema.sql` 至 `004_triggers_fulltext.sql`、`migrations/006` 至 `010`、实际运行的 ReconisDemo 与 ReconisSandbox 两个库，以及后端 Java 中的 SQL 调用。未执行数据库升级、未更改表结构、未写入业务记录。结构快照见同目录 `schema-summary.json`，可用 `read-database-metadata.ps1` 重采；一致性检查见 `database-checks.json`；查询计划见 `query-plan-summary.json` 与两个 `.sqlplan` 文件。

实际业务数据会随验收增加。`schema-summary.json` 中的 row_count 仅是采集时表行数，不是本次测试数，也不是产品用户数量，不应把它们作为性能或经营业绩宣传。该快照采集后，另一验收流程又创建了测试业务记录，正文如使用数据规模应标明采集时点。

## 2 实际环境及对象统计

| 项目 | 本次实际读取结果 |
|---|---|
| 数据库产品 | Microsoft SQL Server 2022 Developer Edition 64 位 |
| 版本 | 16.0.4305.5，服务器版本串标注 RTM-CU27-GDR、KB5126934 |
| 本地库 | ReconisDemo、ReconisSandbox |
| 排序规则 | Chinese_PRC_CI_AS |
| 兼容级别 | 160 |
| SNAPSHOT 隔离 | 两库均为 ON |
| READ_COMMITTED_SNAPSHOT | 两库均为 OFF；不能写成默认读已提交自动使用快照 |
| 业务表与字段 | 每库 15 表、207 列 |
| 主键与唯一约束 | 15 个主键，19 个唯一约束 |
| 外键与 CHECK | 32 个外键，27 个 CHECK 约束；均未禁用且可信 |
| 普通 B 树索引 | 共 53 个，包含 15 主键索引、19 唯一约束索引、6 个筛选唯一索引、13 个非唯一索引 |
| 数据库可编程对象 | 12 个视图、5 个存储过程、2 个标量函数、9 个触发器 |
| 全文检索 | 1 个目录 ReconisCases，1 个全文索引，4 个中文文本字段，language_id=2052 |

对 28 个视图/过程/函数/触发器模块定义进行 CREATE 语句、大小写与空白归一化后，两库均与当前 003、004 文件一致。SQL Server 会将 `CREATE OR ALTER` 存为 `CREATE` 加空格，该语法差异不属于功能漂移。字段与索引快照同时包含 007 的自动任务列、008 的历史状态索引、009 的按订单支付索引、010 的支付反馈列。两库对象数一致不代表业务数据一致，更不代表沙箱所有外部渠道功能已经本轮验证。

## 3 最终实体及关系

### 3.1 表的职责与边界

| 表 | 列数 | 主要职责 | 候选标识或复合唯一性 |
|---|---:|---|---|
| app_user | 12 | 账号、平台身份、审核状态 | id；username |
| merchant | 15 | 商户正式资料与待审修订资料 | id；merchant_code |
| merchant_member | 6 | 用户在某商户下的身份 | id；(merchant_id,user_id) |
| store | 9 | 商户下的门店 | id；(merchant_id,store_code) |
| business_order | 12 | 消费订单、所属门店、消费者与创建人 | id；order_no；client_request_id |
| order_item | 7 | 订单行项目、数量和单价 | id；(order_id,line_no) |
| payment_attempt | 23 | 单次支付请求、渠道结果、查单与派发状态 | id；client_request_id；out_trade_no；非空(channel_env,app_id,channel_trade_no) |
| payment_notification | 9 | 收到的通知事实、验签与处理结果 | id；payload_hash 不是 UNIQUE，重复通知可留痕 |
| refund_request | 19 | 退款申请、审核、发送、查询和终态 | id；client_request_id；out_request_no |
| transaction_entry | 10 | 经确认的收款或退款事实 | id；entry_no；event_key；各类非空来源外键分别唯一 |
| reconciliation_batch | 33 | 导入来源、观察批次与自动获取任务 | id；batch_no；符合筛选条件的导入哈希组合及 acquisition_key |
| statement_line | 10 | 原始账单的逐行记录 | id；(import_batch_id,line_no) |
| reconciliation_result | 9 | 某次观察下的分类及两侧证据引用 | id；(batch_id,result_key) |
| exception_task | 20 | 差错分派、分析、证据、独立复核与结案 | id；case_no；case_key；origin_result_id |
| audit_log | 13 | 请求、业务、数据库变更审计 | id；不以请求号做唯一键，一个请求可产生多条审计 |

数据库不另设“商品主数据”表。`order_item.item_name/category_code/unit_price_cent` 表示订单成交时的项目内容，不能据此画出实际不存在的商品表或购物车关系。当前实际 15 张表中也没有独立的角色字典、附件、工单评论、消息通知、发票或自动任务表。

### 3.2 适用于最终 E-R 图的主干

1. 用户与商户经 merchant_member 构成多对多关系；同一用户可以在不同商户拥有不同角色，同一商户内一名用户只对应一行成员关系。
2. merchant 1:N store；store 1:N business_order；business_order 1:N order_item；business_order 1:N payment_attempt。
3. payment_attempt 1:N refund_request；payment_attempt 1:N payment_notification，通知的 payment_id 可空，用于保存无法归属的回调证据。
4. 一次成功支付最多对应一条 RECEIPT 流水；一次成功退款最多对应一条 REFUND 流水。transaction_entry 的两个来源外键互斥，一个为空、另一个非空，分别由 CHECK 和筛选唯一索引约束。
5. reconciliation_batch 1:N statement_line（原始导入批次）；reconciliation_batch 1:N reconciliation_result（观察批次）。观察结果可同时指向一条本地流水与一条渠道账单行，也可能仅有一侧；原始输入无法识别时允许缺本地引用。
6. exception_task.origin_result_id 唯一且不可变，保存最初异常。latest_result_id 保存最新证据并允许受控变化。一个结果可以被其他工单引用为最新证据，不能把 latest_result_id 画成全局唯一。
7. reconciliation_batch.statement_source_batch_id 自引用到原始输入来源；baseline_batch_id 自引用用于版本比较。不是复制或覆盖旧结果。
8. 用户分别被申请人、审核人、经办人等角色外键引用；这些外键在图中可以折叠为“参与用户”局部图，但不得把不同角色语义合并成一个字段。
9. audit_log.actor_user_id 与 scope_merchant_id 有真实外键；object_type/object_id 是多态业务对象引用，没有跨表外键，不能画成数据库已强制保证的 FK。

全体 32 个 FK 的列映射、空值性及 NO_ACTION 行为见 schema-summary.json。所有 FK 的 UPDATE/DELETE 引用操作均为 NO_ACTION，没有 ON DELETE CASCADE。由此避免直接删除上层商户或订单时级联抹去资金与审计历史。

## 4 字段类型与设计选择

### 4.1 金额使用 BIGINT 分值

order_item.unit_price_cent、refund_request.amount_cent、transaction_entry.amount_cent、statement_line.amount_cent 均为 BIGINT。金额以分存储，界面转换为元，避免二进制浮点参与业务比较。订单数量是 INT，乘法先在 SQL 中转换 BIGINT；Java 创建明细使用 `Math.multiplyExact` 与 `Math.addExact`（OrderController.java:150–180），单据合计要求大于 0 且不超过 100 万元。

订单总金额没有重复保存在 business_order，而是由 v_order 将明细的 `quantity × unit_price_cent` 求和。这样草稿编辑只维护一份价格事实。发起任何支付后，trg_order_item_freeze 阻止明细新增、修改和删除，使订单金额成为稳定的支付校验依据。修改收款事实不是更改原金额；成功退款另记一条 REFUND 事实。

transaction_entry 与 statement_line 的金额始终为正，收付方向由 entry_type/event_type 表示。报表净额以 RECEIPT 正、REFUND 负求和。因此数据库字段 `amount_cent=-1200` 是不合法的；展示图中“−12.00”仅是资金方向的表达，不能写入原金额列。

### 4.2 技术主键与业务标识分离

15 张表统一使用 BIGINT IDENTITY 主键，连接关系紧凑稳定。order_no、out_trade_no、out_request_no、batch_no、case_no 等业务编号另设唯一性，用于跨系统查找与人工追溯。`client_request_id` 是订单、支付、退款入口的幂等键，不能用自增 ID 替代客户端重试身份。

支付可能不止一次尝试，因此 payment_attempt.order_id 非唯一；仅 `is_primary=1` 的记录被 `UX_payment_primary` 限制为每订单最多一条。额外成功收款仍然写入资金流水，并把订单置为 PAYMENT_EXCEPTION，不能因为已存在主支付而忽略真实收到的第二笔钱。

### 4.3 时间与版本

业务时间与操作时间采用 DATETIME2(3)，以 UTC 约定保存；数据库缺省值为 SYSUTCDATETIME()。v_transaction_fact 将 channel_occurred_at 转为 China Standard Time 的 business_date，经营分析按资金实际发生日统计，而不是按订单创建日统计。发生日与 recorded_at 分离，能区分支付实际发生时间与晚到通知的入账时间。

app_user、merchant、store、business_order、refund_request、exception_task 使用 ROWVERSION。它是 SQL Server 自动生成的 8 字节版本标记，不是日期、时间戳时刻或用户手工更新的计数。工单提交与复核请求携带预期版本；`usp_review_exception` 的 @expected_version 为 BINARY(8)，防止旧页面覆盖新处理结果。

### 4.4 证据和历史

文件本体存于受控文件目录，关系表保存 URI；文件摘要使用 SHA-256 十六进制 CHAR(64)。导入幂等不仅比较 file_sha256，还比较 ingest_options_sha256，因为同一文件在不同账期、完整性声明、归属或解析选项下可形成不同合法导入。ImportService.java:327 使用 sp_getapplock 对文件与选项组合加事务级互斥锁，数据库筛选唯一索引作最终兜底。

交易流水、原始账单行、历史对账结果、审计日志由 INSTEAD OF UPDATE/DELETE 触发器保护，只追加而不覆盖。工单保留 origin_result_id，同时增加 latest_result_id。审计 before_json/after_json 是事件发生时的快照，不应替换为对当前行的动态引用，否则历史复核依据会随业务修改变化。

## 5 规范化分析与明确的权衡

### 5.1 有实际结构依据的函数依赖

下列依赖来自表约束和业务语义，而非仅因“使用主键”就断言所有关系满足第三范式。

| 关系 | 主要函数依赖 | 拆分避免的问题 |
|---|---|---|
| store | id → merchant_id, store_code, name, location_text, status；(merchant_id,store_code) → id | 门店不重复商户名称和类别，避免每家店保存一份商户资料 |
| merchant_member | (merchant_id,user_id) → role_code,status,created_at；id → 全部非主键属性 | 同一用户跨商户角色不同，不能用 user_id → role_code 假设；用户账号资料独立保存 |
| business_order | id、order_no、client_request_id 各自决定订单非键属性 | 不重复 merchant_id/name、订单总额；商户归属经 store_id 连接，金额经明细汇总 |
| order_item | (order_id,line_no) → item_name,category_code,quantity,unit_price_cent；id → 全部非键属性 | 订单级消费者、创建人不下沉至每行。没有 item_name → category_code 或 unit_price_cent 的已定义依赖 |
| payment_attempt | id、out_trade_no、client_request_id 各自决定支付尝试属性 | 一单多次尝试被拆为独立关系，避免一条订单出现多个支付号重复组 |
| refund_request | id、out_request_no、client_request_id 各自决定退款属性 | 多次部分退款各有金额、申请人与状态，避免在支付行内增加 refund1/refund2 等重复字段 |
| statement_line | (import_batch_id,line_no) → event_type,交易标识,金额,发生时间 | 文件 URI、账期、归属与完整性主要由批次保存，避免每行重复批次元数据 |
| reconciliation_result | (batch_id,result_key) → source_event_key,entry_id,statement_line_id,classification,reason_code,detected_at | 分类属于某次观察，而不是永久属于交易；重跑另增批次，不覆写旧分类 |

在这些已识别的函数依赖范围内，核心主数据、订单、明细、成员、支付、退款关系将不同实体事实分离，未发现非键属性通过其他非键属性传递决定的已定义依赖；可以按具体关系论证第三范式设计，而不能无条件宣称 15 表及全部 JSON 文本“严格满足 BCNF/3NF”。

### 5.2 不宜粉饰的冗余与半结构化设计

1. transaction_entry 的确认金额与支付/退款原金额有业务相关性，但它表示已经发生的资金事实；支付结果与不可变流水在同一事务写入，避免把未确认请求金额当真实流水。按 RECEIPT 与 REFUND 子集分析时，非空来源外键分别是候选键；SQL 含 NULL 的互斥来源联合关系不宜机械套用无空值关系模型。
2. exception_task.case_key 由归属、事件与差错分类组成，是持久化去重键；origin/latest 引用和 status 是工作流所需受控冗余。触发器检验两引用属于同一事件与范围，但并没有逐字符重算 case_key，不能说全部冗余字段都由数据库计算列自动维护。
3. audit_log.before_json/after_json、reconciliation_batch.acquisition_result 为半结构化快照或任务结果，目标是保留当时内容；不是可任意拆解的规范化主数据。它们适合追溯，但复杂分析依赖 JSON_VALUE/OPENJSON，长期可考虑单独版本化事件模型。
4. 自动获取任务复用了 reconciliation_batch，新增 14 列 acquisition_*，非任务批次大多为空；CK_batch_acquisition 区分任务与普通批次。这是当前真实的实现取舍，报告不能画出不存在的 auto_job 表。后续可拆任务实体，减少职责混合与稀疏列。
5. v_order、v_transaction_fact、v_case 是为查询组合的宽视图，重复展示商户/门店/人员名称，不等于基础表存储重复实体信息。

## 6 实际索引设计

除 PK/UQ 自动产生的索引外，实际额外部署 19 个索引。

| 索引 | 键及筛选 | 业务作用 |
|---|---|---|
| UX_payment_primary | order_id，is_primary=1 | 每单最多一个主支付 |
| UX_payment_channel | channel_env,app_id,channel_trade_no，交易号非空 | 区分环境与应用，避免同一渠道交易号重复归属 |
| UX_entry_payment | payment_id 非空 | 支付确认不会重复产生收款流水 |
| UX_entry_refund | refund_id 非空 | 退款确认不会重复产生退款流水 |
| UX_import_file | scope_type,merchant_id,file_sha256,ingest_options_sha256；原始导入且哈希非空 | 同范围、同原件、同解析选项幂等 |
| UX_batch_acquisition_key | acquisition_key 非空 | 相同自动任务键复用，避免并行重复任务 |
| IX_order_store_time | store_id,created_at INCLUDE(status) | 门店范围内订单列表与时间筛选 |
| IX_payment_pending | status,next_query_at | 支付结果补查任务 |
| IX_refund_pending | status,next_query_at INCLUDE(payment_id,amount_cent) | 退款结果补查任务 |
| IX_entry_time | channel_occurred_at INCLUDE(entry_type,amount_cent,payment_id,refund_id) | 事实时间范围汇总及对账取数 |
| IX_result_batch | batch_id,classification | 批次差错分类和结果列表 |
| IX_result_event_observation | source_event_key,detected_at INCLUDE(batch_id,classification) | 查同一事件后续证据及历史观察 |
| IX_case_assignee | assignee_id,status,due_at | 经办人待办与逾期处理 |
| IX_statement_key | import_batch_id,out_trade_no,out_request_no | 输入批次内交易标识连接 |
| IX_audit_scope | scope_merchant_id,occurred_at | 商户审计时间线 |
| IX_audit_actor | actor_user_id,occurred_at | 操作人审计时间线 |
| IX_batch_acquisition_work | acquisition_env,acquisition_app,acquisition_state,acquisition_next_at INCLUDE(acquisition_lease_until)，source_type=AUTO_JOB | 按环境和租约扫描到期获取任务 |
| IX_audit_case_snapshot | object_id,occurred_at DESC,id DESC INCLUDE(after_json)，CASE/DB_CHANGE | 截止时点的最新工单状态重建 |
| IX_payment_order | order_id INCLUDE(status,is_primary) | 明细冻结触发器查任意支付尝试，不能只靠主支付筛选索引 |

本次在 ReconisDemo 用 SHOWPLAN_XML 获取两条**估计计划**：`SELECT id,status,is_primary FROM payment_attempt WHERE order_id=1` 使用 IX_payment_order 的 Index Seek；待查询退款的 status 与 next_query_at 范围查询使用 IX_refund_pending 的 Index Seek。查询没有执行，不是压力测试或耗时对比；不能据此编造“提高若干倍”或“百万级吞吐”。

## 7 视图 函数 存储过程和触发器

### 7.1 视图的实际调用层次

| 视图 | 主要用途 | 当前代码使用情况 |
|---|---|---|
| v_order | 明细金额汇总、门店与商户归属 | 订单、支付、权限与证据链大量调用 |
| v_transaction_fact | 统一收退款事实与业务日期 | 分析、对账、订单详情、证据链调用 |
| v_statement_normalized | 以 P:/R: 前缀规范化事件键 | usp_run_reconciliation 实际使用 |
| v_case | 起因分类、最新分类、人员与商户关联 | 工单列表、详情、权限、分析实际使用 |
| v_case_matched_evidence | 可用于“重新核对已匹配”结案的新证据 | 工单详情与复核存储过程实际使用 |
| v_case_attribution_evidence | 平台待归属记录的新商户归属证据 | 工单详情与复核过程实际使用 |
| v_public_merchant | 有效商户公开投影 | 已部署，未发现 Java 直接使用该视图 |
| v_consumer_order | SESSION_CONTEXT 用户限定订单 | 已部署，主要 Java 查询用 Access.ordersScope，未发现直接使用 |
| v_merchant_transaction | SESSION_CONTEXT 商户限定事实 | 已部署，Java 使用统一事实视图加权限谓词 |
| v_reconciliation_summary | 批次分类计数，排除 AUTO_JOB | 已部署并授权审计角色；未发现 Java 直接使用 |
| v_case_search | SESSION_CONTEXT 限定可见工单 | 已部署；当前全文检索连接 v_case 并加 Access.scope |
| v_audit_summary | 审计摘要投影 | 已部署并授权审计角色；未发现 Java 直接使用 |

SQL 权限限定视图不等于 SQL Server 原生行级安全。sys.security_policies 实际为 0，reconis_runtime 获得 dbo schema SELECT 权限。用户级隔离实际由 Spring 认证、Access 的动作权限与查询范围共同实现；SessionContextPlugin 在每次查询/更新前设置 user_id、role、merchant_id、request_id，并在 finally 清空，主要支持会话限定视图与触发器审计。

### 7.2 两个标量函数

- fn_refundable_amount：已确认收款减去 APPROVED、PROCESSING、UNKNOWN、SUCCEEDED 退款总额。UNKNOWN 仍占用额度，防止结果未明时再申请导致超退；REJECTED/FAILED 不保留该额度。申请尚在 PENDING_REVIEW 时不预留，真正争用在审核阶段串行检查。
- fn_case_overdue_days：以 closed_at 或指定 as_of 与 due_at 的 DATEDIFF(DAY) 计算，负值截为 0。此实现按跨日边界计天，不是精确满 24 小时的时长，报告不要把它写成小时级 SLA 计量。

### 7.3 五个存储过程

| 过程 | 输入与事务效果 | 关键异常 |
|---|---|---|
| usp_confirm_payment | 支付ID、渠道号、分值、时间、证据；锁订单，校验明细总额，确认支付、插收款事实、更新订单 | 金额/时间不一致、已成功后渠道号冲突；重复同结果返回 ALREADY_CONFIRMED；额外成功返回 EXTRA_SUCCESS |
| usp_approve_refund | 退款ID、审核人、通过/拒绝、意见；锁原支付再锁退款，检查可退额度，写审核状态 | 非待审、自审、超出已成功及预留退款后的余额 |
| usp_confirm_refund | 退款ID、分值、时间、证据；锁原支付与退款，确认终态并插退款事实 | 未批准、金额/时间冲突；同金额重复确认不重复记账 |
| usp_run_reconciliation | 批次ID；SNAPSHOT事务读取两侧、分类、生成/更新工单，完成批次 | 运行过的批次不可覆盖、有历史结果不可覆盖、来源范围不一致；失败回滚 |
| usp_review_exception | 工单ID、复核人、决定、结案类型、意见、预期版本及新证据 | 旧版本、自复核、当前权限失效、缺有效新匹配/归属证据、非法结案类型 |

所有过程使用 SET XACT_ABORT ON、TRY/CATCH、事务提交与异常回滚。并发退款不是数据库全局 SERIALIZABLE，而是同一 payment 行的 UPDLOCK/HOLDLOCK 串行化额度决策；不同支付可独立处理。对账过程显式采用 SNAPSHOT，结束或异常时恢复 READ COMMITTED。

### 7.4 九个触发器

- trg_refund_audit：禁止修改退款关键请求属性；状态变化时记录前后快照。
- trg_case_audit：起因与case_key不可变；提交或结案后分析/证据不可替换；新旧结果必须同事件同范围；保存完整状态快照。
- trg_audit_immutable、trg_entry_immutable、trg_statement_immutable、trg_result_immutable：禁止审计、流水、原始账单、历史结果的 UPDATE/DELETE。
- trg_merchant_review_audit、trg_user_review_audit：保存资料/身份审核的前后变化。
- trg_order_item_freeze：任意支付尝试存在后冻结订单明细。

上述九个触发器在两库均处于启用状态。其限制覆盖常规数据库写操作，不等于防御数据库管理员停用触发器或修改数据库文件的绝对防篡改技术。

## 8 对账分类和证据链的实现细节

`usp_run_reconciliation` 首先将渠道行规范化，再按 event_key 计重复数，缺键和重复键优先分类；剩余有效唯一键与本地事实 FULL OUTER JOIN。主要分类共 8 种：MATCHED、CHANNEL_ONLY、LOCAL_ONLY、AMOUNT_MISMATCH、BILL_DUPLICATE、KEY_UNRESOLVED、PENDING_CONFIRMATION、EXTRA_SUCCESS。

账单完整性 UNKNOWN 时，本地有而渠道无只标 PENDING_CONFIRMATION；已声明/提供完整账单后才可标 LOCAL_ONLY。这避免把输入缺页或部分导入直接断言为漏账。对于退款，还核实退款引用的原支付号；存在渠道交易号冲突也进入 KEY_UNRESOLVED，而不只看金额相等。

差错工单按“范围 + source_event_key + classification”生成 case_key，对同类异常的后续观察复用工单。原始异常保持不变，最新证据更新；已结案工单遇到新异常可重开，待复核工单会退回处理中，避免复核一份已经失效的结论。工单关闭不修改 transaction_entry，也不把原 reconciliation_result.classification 改成 MATCHED。

结案类型 RECONCILED 必须引用同商户同事件、晚于异常且没有更新异常推翻的新 MATCHED 证据；不是只要填写“已核对”即可关闭。OWNERSHIP_CONFIRMED 要求相同原件 SHA-256、相同行号/类型/金额/时间，且能通过支付链证明商户归属。无效输入、误报、已知异常可用其他结案类型，但保留原始分类，方便后续风险统计追溯。

## 9 高级查询的数据结构依据

### 9.1 钻取

最终不是“省→市→县”，而是校园资金的真实维度：商户→门店→资金流水，以及年→月→日→流水。AnalyticsService.java:118–180 仅从 merchant/store/year/month/day 白名单选择 SQL 分组表达式，金额合计均出自 v_transaction_fact；前端更换粒度时保留原筛选范围与 asOf。退款按退款发生日进入负向净额，额外成功收款也计入真实收款，不因订单异常被排除。

统计与明细使用 `channel_occurred_at` 账期和 `recorded_at <= asOf` 截止时间，防止迟到入账记录让同一次查看的上下级结果失配。风险分析使用 IX_audit_case_snapshot 对应的历史审计快照重建截至指定时点的工单状态，而不是用今天的关闭状态回填过去。

### 9.2 数据关联

订单详情能经 order_item、payment_attempt、refund_request、transaction_entry 追踪收退款；资金事实反向关联 store→merchant、consumer。工单详情从 origin/latest_result 连接批次、原账单行、本地事实、原支付、订单、门店、商户、经办与复核人。与学术论文场景中“论文→研究者→单位”的原理一致，具体业务对象替换为真实校园支付关系。

### 9.3 全文检索

在 exception_task.title/description/analysis_text/resolution_text 四列建立 SQL Server 中文全文索引，CHANGE_TRACKING AUTO。CaseController.java:66–69 使用 FREETEXTTABLE，连接返回的 KEY 到 v_case，按 RANK 排序并追加可见范围条件。是数据库全文检索，不是把 LIKE 包装成搜索框；中文分词语言为 2052。本次元数据确认索引启用、抓取完成且无进行中的填充，但一次元数据检查不能替代所有查询词的召回质量测评，查询实际结果由本轮验收单独记录。

## 10 可放入报告的关键 SQL 节选

### 10.1 资金来源互斥

来源：database/001_schema.sql:67–73，database/002_constraints_indexes.sql:3–4。

```sql
CHECK ((entry_type='RECEIPT' AND payment_id IS NOT NULL AND refund_id IS NULL)
    OR (entry_type='REFUND' AND payment_id IS NULL AND refund_id IS NOT NULL));
CREATE UNIQUE INDEX UX_entry_payment
  ON dbo.transaction_entry(payment_id) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX UX_entry_refund
  ON dbo.transaction_entry(refund_id) WHERE refund_id IS NOT NULL;
```

CHECK 保证一种事实只引用一种来源，两个筛选唯一索引保证同一确认来源只生成一次事实。NULL 来源不参与对应筛选唯一索引，因此多笔退款不会因 payment_id 都为空而冲突。

### 10.2 预留退款额度

来源：database/003_views_functions_procedures.sql:80–84、124–130。

```sql
SELECT @dummy=id FROM dbo.payment_attempt WITH(UPDLOCK,HOLDLOCK)
WHERE id=@payment;
IF @applicant=@reviewer THROW 51011,N'申请人不能审批自己的退款',1;
IF @approve=1 AND @amount>dbo.fn_refundable_amount(@payment)
  THROW 51012,N'可退额度不足，已有成功或预留退款',1;
```

同一支付上的并行审批先争用同一行锁；后获得锁的事务看到前一审批占用后的额度。网络结果未知不能立即释放额度，否则同一收款可能被多次退款。

### 10.3 双侧核对与完整性边界

来源：database/003_views_functions_procedures.sql:180–194，节选逻辑而非可单独执行的完整过程。

```sql
CASE
 WHEN l.id IS NULL THEN 'CHANNEL_ONLY'
 WHEN l.entry_type='RECEIPT' AND l.is_primary=0 THEN 'EXTRA_SUCCESS'
 WHEN c.id IS NULL AND @complete='UNKNOWN' THEN 'PENDING_CONFIRMATION'
 WHEN c.id IS NULL THEN 'LOCAL_ONLY'
 WHEN l.amount_cent<>c.amount_cent THEN 'AMOUNT_MISMATCH'
 ELSE 'MATCHED'
END
```

完整过程还在金额比较前检查原支付关联与渠道交易号冲突，且先处理缺键/重复键。正文不能把这段压缩节选描述为全分类算法。

## 11 本次只读一致性结果

2026-11-04 17:57:58 +08:00 在两库分别查询：成功支付无收款流水、成功退款无退款流水、退款事实与请求金额冲突、收款事实与订单总额冲突、可退款额为负、工单新旧证据事件或范围冲突，六类异常记录数均为 0。FK/CHECK 禁用或不可信数均为 0，禁用触发器数为 0。

这些结果证明检查时数据库未出现所查询的不变量违例；它们不是并发压力试验，也不能证明未来所有输入都正确。约束、过程代码、隔离的集成测试和当前数据检查应分别呈现，不能将这六项查询混入 JUnit 或浏览器自动化的通过数量。

## 12 限制与可改进点

- 业务用户经同一个运行数据库角色访问，数据库角色并未针对每位应用用户形成独立 RLS 边界；权限回归需持续覆盖所有 SQL 入口，后续可评估真正行级安全策略。
- 自动任务与对账批次同表，列较多且语义混合；可拆自动任务表并明确任务-导入批次关联。
- 审计快照依赖 JSON，适合证据保留，但跨版本结构演进与数据保留策略仍可完善。
- 文件存储与 SQL Server 事务不属于同一分布式事务；应明确恢复、备份和孤立文件清理策略，不能声称附件和关系写入天然具备跨介质原子性。
- 自增键、业务唯一键和较多索引有写入维护成本；当前规模的 Index Seek 估计计划不是生产容量证明。
- 全文检索依赖 SQL Server 全文组件和可用内存；单机课堂环境中的超时、预热和资源限制需如实记录。
- DATETIME2 不自带时区，UTC 是应用与SQL共同遵循的约定；外部导入时间解析必须保持同一规则。

以上事实用于最终实现报告，不用于反向改写初期设计报告。
