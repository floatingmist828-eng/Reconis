# 4 最终数据库设计

## 4.1 数据组织原则与总体结构

校园支付对账涉及订单、支付渠道、账单和人员处理四类信息。订单说明应收内容，支付结果说明渠道确认结果，资金流水记录已经发生的收付，渠道账单提供另一侧核对依据。差错工单保存的是对这些证据的处理过程。我们将这些对象分别建模，使支付状态变化、重新导入和复核结案不会覆盖原始资金事实。

最终数据库运行于 SQL Server 2022 Developer Edition，版本为 16.0.4305.5，兼容级别为 160，排序规则为 Chinese_PRC_CI_AS。教学模拟与支付宝沙箱分别使用 ReconisDemo、ReconisSandbox，避免两种环境的资金记录混入同一核对范围。两库采用相同结构，每库包含 15 张业务表、207 个字段、32 个外键和 27 个 CHECK 约束。图4-1展示最终实体关系的总体划分。

表4-1按数据职责列出基础关系。身份与商户部分确定业务归属；资金部分保留请求与确认事实；对账部分保留输入、观察结果和处理记录；审计日志贯穿各模块。

**表4-1 最终数据库关系及职责**

| 数据范围 | 关系 | 主要职责 |
|---|---|---|
| 身份与商户 | app_user | 账号、平台角色、注册审核与启停 |
| 身份与商户 | merchant | 商户正式资料和待审资料修订 |
| 身份与商户 | merchant_member | 用户在指定商户中的成员身份 |
| 身份与商户 | store | 商户下的门店及启停状态 |
| 订单与资金 | business_order | 收款订单、消费者和创建人 |
| 订单与资金 | order_item | 订单项目、数量和单价 |
| 订单与资金 | payment_attempt | 每次支付尝试、渠道结果及补查状态 |
| 订单与资金 | payment_notification | 通知原件摘要、验签和处理结果 |
| 订单与资金 | refund_request | 退款申请、审批、发送和确认 |
| 订单与资金 | transaction_entry | 已确认的收款或退款事实 |
| 对账与处理 | reconciliation_batch | 导入来源、对账观察和自动获取任务 |
| 对账与处理 | statement_line | 原始账单的逐行记录 |
| 对账与处理 | reconciliation_result | 一次观察中的分类和两侧证据 |
| 对账与处理 | exception_task | 分派、分析、证据、复核与结案 |
| 审计 | audit_log | 操作主体、业务范围和变更前后快照 |

[图4-1 er-overview]

## 4.2 主要关系模式与实体联系

以下列出各关系的主要业务属性及实体之间的联系。所有关系均以 id 为 BIGINT IDENTITY 主键，完整的207个字段、类型、空值性及键约束见附录A，默认表达式见数据库元数据附件。

1. 用户 app_user（id，username，password_hash，display_name，platform_role，status，reviewed_by，version）。username 唯一；reviewed_by 自引用审核账号。平台角色与账号状态分别保存，待审核账号不会因为已选择身份而直接取得操作资格。
2. 商户 merchant（id，merchant_code，name，category_code，contact，status，proposed_name，proposed_category，proposed_contact，reviewed_by，version）。merchant_code 唯一；正式资料与待审修订值分开，审核前仍可识别原有业务归属。
3. 成员 merchant_member（id，merchant_id，user_id，role_code，status，created_at）。merchant_id、user_id 分别引用商户与用户，组合唯一；一名用户在不同商户中可以承担不同工作。
4. 门店 store（id，merchant_id，store_code，name，location_text，status，version）。门店编号在所属商户内唯一，不要求不同商户使用不同的门店简称。
5. 订单 business_order（id，order_no，store_id，consumer_id，created_by，client_request_id，subject，remark，status，version）。order_no 和 client_request_id 各自唯一；消费者可空，创建人不可空，商户归属通过门店确定。
6. 明细 order_item（id，order_id，line_no，item_name，category_code，quantity，unit_price_cent）。order_id 引用订单，订单内行号唯一。项目名称和单价表示成交内容，数据库没有额外的商品主数据表。
7. 支付 payment_attempt（id，order_id，client_request_id，out_trade_no，channel_env，app_id，seller_id，channel_trade_no，status，is_primary，next_query_at，dispatch_token，dispatch_lease_until）。客户端请求号与商户交易号分别唯一，一笔订单可以有多次支付尝试。
8. 通知 payment_notification（id，payment_id，notify_id，payload_file_uri，payload_hash，signature_valid，handling_result，received_at，request_id）。payment_id 可空，使无法归属或验签失败的通知仍可保留处理证据。payload_hash 不设唯一约束，重复到达的通知也能分别留痕。
9. 退款 refund_request（id，payment_id，client_request_id，out_request_no，amount_cent，reason，status，applicant_id，reviewed_by，dispatch_token，dispatch_lease_until，version）。每笔退款关联一笔原支付，请求号唯一；多次部分退款分别记录金额和状态。
10. 资金事实 transaction_entry（id，entry_no，event_key，entry_type，payment_id，refund_id，amount_cent，channel_occurred_at，recorded_at，evidence_file_uri）。entry_no、event_key 各自唯一，payment_id 与 refund_id 按流水类型互斥使用。
11. 批次 reconciliation_batch（id，batch_no，scope_type，merchant_id，period_start，period_end，source_type，statement_source_batch_id，baseline_batch_id，file_sha256，ingest_options_sha256，completeness，status，observed_at，acquisition_key，acquisition_state）。该关系还保存任务租约、重试与获取结果等属性，字段全集见附录。
12. 账单行 statement_line（id，import_batch_id，line_no，event_type，out_trade_no，channel_trade_no，out_request_no，amount_cent，channel_occurred_at，raw_line_file_uri）。原始批次与行号组合唯一，交易标识允许缺失，使有问题的原始行也能完整保留。
13. 对账结果 reconciliation_result（id，batch_id，result_key，source_event_key，entry_id，statement_line_id，classification，reason_code，detected_at）。batch_id 与 result_key 组合唯一；本地流水和渠道行均为可空引用，以表示仅存在一侧的情况。
14. 差错工单 exception_task（id，case_no，case_key，origin_result_id，latest_result_id，assignee_id，status，analysis_text，resolution_text，resolution_code，evidence_file_uri，submitted_by，reviewed_by，version）。case_no、case_key、origin_result_id 分别唯一；最新证据引用不设全局唯一。
15. 审计 audit_log（id，event_category，actor_user_id，scope_merchant_id，object_type，object_id，action，result，summary，before_json，after_json，occurred_at，request_id）。操作人和商户具有外键，object_type 与 object_id 表示多态业务对象，没有跨所有业务表的统一外键。

图4-2中的 merchant_member 将用户与商户的多对多联系转换为独立关系，防止把商户角色错误地直接放进用户表。消费者身份也不要求先成为商户成员，订单的 consumer_id 独立表达消费者与订单的联系。

[图4-2 er-identity]

资金关系见图4-3。一笔订单有多条明细和多次支付尝试；一笔支付可对应多次部分退款。成功支付最多产生一条收款流水，成功退款最多产生一条退款流水。支付请求表承担状态恢复，资金事实表承担记账依据，两者保持联系但各有职责。

[图4-3 er-funds]

对账关系见图4-4。原始导入批次保存 statement_line，对账观察批次保存 reconciliation_result。重跑批次通过 statement_source_batch_id 复用原始账单，通过 baseline_batch_id 指向比较基线。工单保留最初异常和最新证据两个引用，既能沿当前处理状态继续工作，也能回答异常最初如何产生。

[图4-4 er-reconciliation]

## 4.3 数据类型与完整性约束

金额采用 BIGINT 分值存储。订单单价、退款金额、资金事实金额和账单金额都使用同一单位，比较时不依赖浮点舍入。数量为 INT，SQL 汇总先将数量转换为 BIGINT；应用创建明细时使用精确乘加，并限制订单合计大于零且不超过100万元。数据库允许明细单价为零，但要求退款、资金事实和账单金额为正；这些行内约束与应用层总额范围校验分别承担不同层次的合法性检查。

订单总金额由 v_order 汇总明细得到，business_order 不另存总额。草稿编辑因此只维护一份明细事实。发起支付后，数据库触发器冻结订单明细，避免支付请求与后台价格修改产生金额不一致。收款和退款流水的 amount_cent 均为正值，由 entry_type 指明方向；净收款计算时再将退款作负向合计。

数据库共有15个主键、19个唯一约束。业务编号便于跨系统追踪，技术主键用于关联，两者并存。client_request_id 标识同一次客户端操作，网络重试据此返回原业务记录。支付环境、应用和非空渠道交易号组成筛选唯一索引，区分不同渠道环境中的同名交易编号。

32个外键均采用 NO_ACTION，不进行级联删除。删除上层对象时，存在资金或证据引用会阻止直接删除，避免商户、订单与历史流水同时消失。27个 CHECK 约束覆盖状态枚举、正金额、正数量、账期先后和范围归属等规则。批次要求结束时间大于开始时间；MERCHANT 范围必须指定商户，PLATFORM_UNASSIGNED 范围必须不指定商户。

资金事实的来源采用互斥约束：

```sql
CHECK (
  (entry_type='RECEIPT' AND payment_id IS NOT NULL AND refund_id IS NULL)
  OR
  (entry_type='REFUND' AND payment_id IS NULL AND refund_id IS NOT NULL)
);
```

该约束使每行资金事实只具有一种收付来源。配合两个非空来源字段的筛选唯一索引，同一支付或退款不能重复形成资金事实。空值行不参与对应筛选索引，因此不会错误地限制其他类型的流水数量。

业务时间和操作时间采用 DATETIME2(3)，统一约定保存 UTC，缺省值为 SYSUTCDATETIME()。channel_occurred_at 表示渠道实际发生时间，recorded_at 表示系统入账时间，两者分离使晚到通知仍能归入正确业务日期。v_transaction_fact 将发生时间转换为中国标准时间的业务日期，供日、月、年统计使用。

用户、商户、门店、订单、退款和工单采用 ROWVERSION。该字段是数据库自动维护的8字节版本标记[3]。工单复核使用请求携带的预期版本与当前版本比较，防止两名操作人员从旧页面提交相互覆盖的决定。

## 4.4 函数依赖与规范化分析

规范化分析以关系中的真实依赖为基础。表4-2列出代表性函数依赖及其对结构拆分的影响。每个关系除列出的业务候选键外，还具有 id 主键。

**表4-2 主要函数依赖及结构依据**

| 关系 | 函数依赖 | 结构作用 |
|---|---|---|
| merchant | merchant_code → name, category_code, contact, status | 商户资料只维护于商户实体 |
| store | (merchant_id, store_code) → name, location_text, status | 门店编号在商户范围内决定门店资料 |
| merchant_member | (merchant_id, user_id) → role_code, status | 角色由用户与商户共同决定，不由用户单独决定 |
| business_order | order_no → store_id, consumer_id, created_by, subject, status | 订单归属与订单级属性集中保存 |
| order_item | (order_id, line_no) → item_name, category_code, quantity, unit_price_cent | 行项目依赖完整复合键，不重复订单级资料 |
| payment_attempt | out_trade_no → order_id, channel_env, status 等尝试属性 | 独立表达一次支付尝试 |
| refund_request | out_request_no → payment_id, amount_cent, applicant_id, status | 每笔退款具有独立请求身份 |
| statement_line | (import_batch_id, line_no) → 类型、标识、金额、发生时间 | 原件中的行由原始批次与行号共同确定 |
| reconciliation_result | (batch_id, result_key) → 两侧引用、分类、原因、检测时间 | 分类属于某次观察，而非永久属于某笔交易 |

例如，若在订单中同时保存 store_id、merchant_id 和 merchant_name，则 store_id 决定 merchant_id，merchant_id 又决定 merchant_name，会引入非键属性之间的传递依赖。最终订单只引用 store，商户信息通过视图连接，避免商户改名时逐单更新。同样，明细表没有重复保存消费者、创建人等订单级属性，不会因一笔订单包含多行而产生多份相同资料。

支付与退款单独建表解决一对多事实的重复组问题。若把第二次支付或第二次退款继续放入订单的“支付号2”“退款金额2”等列，既限制次数，又使空值和状态组合迅速增多。独立关系允许任意合法次数的请求，同时分别建立唯一标识和关联约束。order_item 的项目名称表示当时的成交内容。系统未定义“项目名称决定单价或类别”的函数依赖，这些属性按成交明细保存。

在这些已识别的函数依赖下，核心实体表的非键属性依赖于候选键，复合键关系不存在已定义的部分依赖，实体之间的属性通过外键连接，能够按关系说明第三范式设计。历史与工作流中的半结构化字段和快照保留了受控冗余，其用途和维护约束如下。

transaction_entry 保存确认时的金额和来源，是已发生事实的独立记录；exception_task 保存 case_key、最初结果、最新结果和处理状态，便于差错去重与历史追溯；audit_log 的 before_json、after_json 保存变更时的内容，当前表的外键查询则返回现时数据。上述数据由事务、唯一索引和触发器共同维护。

自动获取任务目前复用 reconciliation_batch，增加14个 acquisition_* 字段，普通导入批次的大部分任务字段为空。该取舍减少了批次与任务间的协调代码，但使一张表承担输入来源与获取任务两种职责。系统通过 CK_batch_acquisition 区分合法组合，后续仍可拆分任务实体，改善职责划分和稀疏字段问题。

## 4.5 索引与查询组织

实际数据库共有53个B树索引，其中34个由主键和唯一约束产生，另外19个为显式业务索引。索引设计分别服务于唯一性、工作队列、统计取数和证据追溯。

**表4-3 代表性业务索引及使用目的**

| 索引 | 主要键或筛选条件 | 目的 |
|---|---|---|
| UX_payment_primary | order_id；is_primary=1 | 每单最多一个主支付 |
| UX_entry_payment、UX_entry_refund | 非空来源支付或退款ID | 确认重试不重复记账 |
| UX_import_file | 范围、商户、文件摘要、选项摘要 | 同一导入含义下去重 |
| IX_payment_pending、IX_refund_pending | status, next_query_at | 扫描到期补查记录 |
| IX_entry_time | channel_occurred_at，包含金额与来源列 | 账期取数与资金汇总 |
| IX_result_event_observation | source_event_key, detected_at | 查找同事件后续观察 |
| IX_case_assignee | assignee_id, status, due_at | 经办人待办与逾期查询 |
| IX_audit_case_snapshot | object_id, occurred_at DESC, id DESC；工单变更 | 重建截止时间的工单状态 |
| IX_payment_order | order_id，包含状态与主支付标记 | 检查订单是否存在任意支付尝试 |

明细冻结要求检查所有支付尝试，IX_payment_order 为这些记录提供按订单查找的索引；UX_payment_primary 只覆盖 is_primary=1 的主支付。导入唯一性也不只依赖文件摘要；同一原件在不同账期、归属或解析选项下可以构成不同导入，故需要同时保存 ingest_options_sha256。

在当前数据库的估计执行计划中，按订单查支付使用 IX_payment_order 的 Index Seek，按退款状态与下次查询时间筛选使用 IX_refund_pending 的 Index Seek。这两项估计计划反映了相应谓词的索引选择；实测耗时和实验条件见第9.3节。新增索引会增加写入维护和存储成本，后续扩展应继续结合实际查询频率、执行计划与数据规模调整。

# 5 数据库程序与一致性控制

## 5.1 视图形成统一查询口径

系统部署12个视图。实际业务的主要查询由 v_order、v_transaction_fact、v_case 及两个结案证据视图承担，v_statement_normalized 被对账存储过程使用，其余视图提供已部署的受限查询或摘要入口。表5-1列出各视图的职责及实际调用情况。

**表5-1 最终视图及调用情况**

| 视图 | 数据组织 | 实际调用 |
|---|---|---|
| v_order | 明细总额、门店、商户与订单 | 订单、支付、权限和证据查询 |
| v_transaction_fact | 收退款统一事实及业务日期 | 分析、对账、订单详情 |
| v_statement_normalized | 生成规范事件键 | 对账存储过程 |
| v_case | 最初分类、最新结果、人员与商户 | 工单、权限和风险分析 |
| v_case_matched_evidence | 有效的新匹配结果 | 工单详情、复核过程 |
| v_case_attribution_evidence | 原件同一行的新归属证据 | 工单详情、复核过程 |
| v_public_merchant | 有效商户的公开字段 | 已部署，Java接口未直接引用 |
| v_consumer_order | 会话用户的订单 | 已部署，主要接口使用权限谓词 |
| v_merchant_transaction | 会话商户的资金事实 | 已部署，主要接口使用权限谓词 |
| v_reconciliation_summary | 批次分类计数 | 已部署并授权数据库审计角色 |
| v_case_search | 会话限定的工单范围 | 已部署，全文接口直接连接v_case并限定范围 |
| v_audit_summary | 审计摘要字段 | 已部署并授权数据库审计角色 |

v_transaction_fact 通过退款找到原支付，再连接订单、门店和商户，使收款与退款具有同一归属路径。经营分析由此统一计算收款、退款和净收款，退款按实际退款日期计入，不会因为原订单在另一天创建而漏入当前账期。

## 5.2 存储过程划定业务原子操作

五个存储过程分别承担支付确认、退款审核、退款确认、执行对账和工单复核。我们把必须同时成功或失败的数据库变化放在同一事务内，使用 SET XACT_ABORT ON，并在 TRY/CATCH 中提交或回滚。外部支付请求在数据库事务之外执行，超时与结果未知通过状态恢复处理。

usp_confirm_payment 接收支付ID、渠道交易号、金额、发生时间与证据。过程锁定订单，重新汇总明细校验金额，再同时更新支付状态、插入收款事实和更新订单。已经成功且渠道号相同的重复确认返回 ALREADY_CONFIRMED；相同支付记录出现冲突渠道号则报错。若订单已存在主支付而另一尝试确实成功，过程保留额外资金事实，并标记 PAYMENT_EXCEPTION，避免把异常收款误当无效请求丢弃。

退款流程的事务边界见图6-2。usp_approve_refund 先锁定原支付，再锁定申请，要求申请仍处于待审核状态、审核人与申请人不同，并在锁内重新检查可退额度。通过审核后写入 APPROVED，使额度立即进入预留范围；拒绝则写入 REJECTED。

```sql
SELECT @dummy=id
FROM dbo.payment_attempt WITH(UPDLOCK,HOLDLOCK)
WHERE id=@payment;

IF @applicant=@reviewer
  THROW 51011,N'申请人不能审批自己的退款',1;
IF @approve=1 AND @amount>dbo.fn_refundable_amount(@payment)
  THROW 51012,N'可退额度不足，已有成功或预留退款',1;
```

同一支付上的两个审批事务争用同一行锁，后获得锁的事务重新看到扣除前一申请预留后的余额。两个申请即使各自未超过收款额，其合计也受到同一支付可退额度的限制。

usp_confirm_refund 只接受 APPROVED、PROCESSING、UNKNOWN 等可确认状态，并核对金额和发生时间。退款终态与退款流水在一个事务中写入；已成功退款按相同金额再次确认，不再插入新流水。UNKNOWN 不能简单判为失败，否则原退款可能已经在渠道成功，又被错误释放额度。

usp_run_reconciliation 在 SNAPSHOT 事务中读取本地事实与渠道记录，保存分类结果并生成或更新工单。已有历史结果的批次不可覆盖，重新核对另建观察批次。两库已开启 SNAPSHOT 支持，但默认 READ_COMMITTED_SNAPSHOT 未开启；对账过程显式切换隔离级别，正常或异常结束后恢复 READ COMMITTED。

usp_review_exception 接收复核决定、结案类型、意见、预期版本及可选的新证据。过程要求工单处于 PENDING_REVIEW，复核人与 submitted_by 不同，复核人当前账号有效且仍有相应商户或平台复核权限。版本不符、权限变化或证据不满足条件时均不执行结案。

## 5.3 标量函数与幂等控制

fn_refundable_amount 返回已确认收款减去 APPROVED、PROCESSING、UNKNOWN、SUCCEEDED 退款之和。待审核申请不提前占用额度，额度竞争在审批事务中解决；未知结果继续占用额度，直到查明成功或失败。该函数同时供详情展示与审批决策使用，统一两处的额度计算口径。

函数的核心计算如下。两处 COALESCE 将无收款或无退款记录的空聚合结果转为零；成功退款仍保留在扣减集合中，因此状态从预留转为成功不会重新释放额度。

```sql
RETURN (
  SELECT COALESCE((SELECT amount_cent
    FROM dbo.transaction_entry WHERE payment_id=@payment_id),0)
  -COALESCE((SELECT SUM(amount_cent)
    FROM dbo.refund_request WHERE payment_id=@payment_id
      AND status IN ('APPROVED','PROCESSING','UNKNOWN','SUCCEEDED')),0)
);
```

fn_case_overdue_days 以 due_at 与 closed_at 或给定 as_of 计算超期天数，负值截为零。其实现使用 DATEDIFF(DAY)，按跨日边界计数，不按满24小时折算。历史风险分析传入指定截止时点，避免把当前日期直接代入过去的状态。

幂等在不同层次使用不同标识。订单、支付、退款以 client_request_id 识别同一次操作；资金流水以 event_key 和非空来源唯一索引防止重复记账；导入以文件摘要、解析选项摘要和业务范围识别同一输入；自动任务以 acquisition_key 复用任务。

导入还使用事务级 sp_getapplock，对文件与选项组合加互斥锁，使两个并发导入不会同时完成查重后各自插入。唯一索引保留为最后一道约束。原件相同但账期、范围或完整性声明不同的输入，按新的导入含义处理。

## 5.4 对账分类与可追溯结案

对账先把收款键规范化为 P:加商户交易号，把退款键规范化为 R:加退款请求号。缺少事件键或同键重复的渠道行先形成 KEY_UNRESOLVED 或 BILL_DUPLICATE，剩余唯一有效键再与本地事实进行 FULL OUTER JOIN，使本地单边和渠道单边记录都被保留。

最终分类共8种：MATCHED、CHANNEL_ONLY、LOCAL_ONLY、AMOUNT_MISMATCH、BILL_DUPLICATE、KEY_UNRESOLVED、PENDING_CONFIRMATION、EXTRA_SUCCESS。分类同时考察是否存在两侧记录、金额、退款关联、渠道交易号和支付是否为额外成功。若账单完整性为 UNKNOWN，本地存在而渠道缺失只能判为 PENDING_CONFIRMATION；已确认完整性后，才可判为 LOCAL_ONLY。

差错工单的 case_key 结合归属、事件键和分类生成，相同范围内同类异常的后续观察复用工单。原始异常保存在 origin_result_id，最新证据保存在 latest_result_id。后续又出现异常时，已结案工单可以重新打开，待复核工单回到处理中，防止人员继续批准已经过时的分析。

结案支持 RECONCILED、INVALID_INPUT、FALSE_POSITIVE、KNOWN_EXCEPTION、OWNERSHIP_CONFIRMED。RECONCILED 必须引用同范围同事件的新匹配结果，并且没有更新异常推翻该证据；OWNERSHIP_CONFIRMED 需要相同原件、相同行号及一致的业务内容，并通过原支付关联证明新的商户归属。其他结案类型用于记录无效输入、误报或已知异常，均不直接修改原资金流水。

2026年11月4日的流程验证中，工单115结案后，origin_result_id仍指向金额差异结果371，latest_result_id则指向匹配结果381。工单状态为 CLOSED，结案类型为 RECONCILED，经办提交人与复核人分别为286、287。原始差异与结案依据同时保留。

## 5.5 触发器维护历史边界

九个触发器承担两类职责：记录变更，以及阻止破坏性改写。表5-2给出其作用范围。

**表5-2 触发器及一致性职责**

| 触发器 | 作用 |
|---|---|
| trg_refund_audit | 冻结退款关键请求属性，状态变化时保存前后快照 |
| trg_case_audit | 固定起因与case_key，校验证据范围，限制提交后替换分析与证据，保存工作流快照 |
| trg_audit_immutable | 禁止更新、删除审计日志 |
| trg_entry_immutable | 禁止更新、删除资金事实 |
| trg_statement_immutable | 禁止更新、删除原始账单行 |
| trg_result_immutable | 禁止更新、删除历史对账结果 |
| trg_merchant_review_audit | 保存商户资料与审核变化 |
| trg_user_review_audit | 保存账号状态和平台身份变化 |
| trg_order_item_freeze | 存在支付尝试后禁止订单明细变更 |

触发器使用 inserted、deleted 集合处理受影响行，审计记录包括变更前后内容及 request_id。工单触发器还要求最初结果和最新结果属于同一事件与业务范围，防止把其他商户的匹配记录作为本工单的处理证据。审计表本身只能追加，使业务改动与相应记录形成连续历史。

资金事实的保护由一个短触发器实现。它截获整个 UPDATE 或 DELETE 语句，使直接SQL修改与接口中的误操作都不能绕过“只追加”的业务边界。需要补充新证据时创建新记录，不更改已经确认的资金事实。

```sql
CREATE OR ALTER TRIGGER dbo.trg_entry_immutable
ON dbo.transaction_entry INSTEAD OF UPDATE, DELETE
AS
BEGIN
  THROW 51040,N'交易事实不可修改或删除',1;
END;
```

这些触发器约束正常数据库写操作。管理员仍可能停用触发器或改变数据库结构，因此运行账号权限、备份和操作管理仍是数据保护的一部分。

## 5.6 全文查询、权限范围与结果边界

全文目录 ReconisCases 对工单的 title、description、analysis_text、resolution_text 四列建立中文全文索引，语言编号为2052，使用自动变更跟踪。知识检索通过 FREETEXTTABLE 返回匹配键和相关性排名，再连接 v_case 加入可见范围条件。搜索结果既能定位历史处理文本，也能回到具体工单和证据链。

数据库定义了 reconis_runtime 和 reconis_auditor 两个角色。消费者、收银、经办和复核属于应用层身份，没有分别建立 SQL 登录。实际应用权限由认证结果、Access 的动作检查及查询范围谓词落实。SessionContextPlugin 在SQL调用前设置 user_id、role、merchant_id、request_id，并在 finally 中清空，供会话限定视图和审计触发器使用，防止连接池复用时残留上一请求上下文。外模式与行范围的实现见第8.2节。

2026年11月4日对两库进行的只读一致性查询中，成功支付缺流水、成功退款缺流水、退款事实金额冲突、收款与订单金额冲突、可退额度为负、工单证据范围冲突六类记录数均为零；外键、CHECK和触发器均有效。

文件本体与关系数据分属文件系统和 SQL Server，本地事务不能天然保证跨介质原子性。后续仍需完善孤立文件清理、附件与数据库一致备份及恢复演练。审计JSON快照和自动任务复用批次表也有演进成本，进一步优化需保留现有证据链语义和历史记录。
