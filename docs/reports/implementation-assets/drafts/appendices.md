# 附录A 完整字段字典

本附录列出最终数据库的15张表和207个字段。两套运行数据库结构一致。所有表均以 id 为 BIGINT IDENTITY 主键；字符串长度按字符数列示，NVARCHAR(MAX) 为可变长Unicode文本，ROWVERSION 为8字节自动版本标记。金额单位均为分，DATETIME2(3) 按UTC约定保存。

“非空／可空”对应列的 NULL 属性；PK 表示主键，UQ 表示单列唯一约束，UK 表示组合唯一约束中的一列，FK 表示外键。组合唯一约束要求所列字段的组合值唯一。各表下列出完整的唯一约束与外键，全部32个外键的更新和删除动作均为 NO_ACTION。筛选唯一索引另见附录B。

## A.1 用户账号（app_user）

**表A-1 用户账号字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 账号主键 |
| username | NVARCHAR(64) | 非空；UQ | 唯一登录名 |
| password_hash | VARCHAR(100) | 非空 | 密码哈希，不保存明文密码 |
| display_name | NVARCHAR(80) | 非空 | 显示姓名 |
| platform_role | VARCHAR(24) | 非空 | 平台身份，默认MEMBER |
| status | VARCHAR(16) | 非空 | 账号审核及启停状态 |
| reviewed_by | BIGINT | 可空；FK | 审核该账号的用户 |
| reviewed_at | DATETIME2(3) | 可空 | 审核完成时间 |
| review_reason | NVARCHAR(500) | 可空 | 审核意见 |
| created_at | DATETIME2(3) | 非空 | 账号创建时间 |
| updated_at | DATETIME2(3) | 非空 | 账号最近更新时间 |
| version | ROWVERSION | 非空 | 并发修改版本标记 |

主键：id。唯一约束：(username)。

外键：reviewed_by → app_user(id)。

## A.2 商户（merchant）

**表A-2 商户字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 商户主键 |
| merchant_code | VARCHAR(32) | 非空；UQ | 唯一商户编号 |
| name | NVARCHAR(100) | 非空 | 正式商户名称 |
| category_code | NVARCHAR(30) | 非空 | 正式经营类别 |
| contact | NVARCHAR(150) | 可空 | 正式联系信息 |
| status | VARCHAR(16) | 非空 | 商户审核及启停状态 |
| proposed_name | NVARCHAR(100) | 可空 | 待审修订名称 |
| proposed_category | NVARCHAR(30) | 可空 | 待审修订经营类别 |
| proposed_contact | NVARCHAR(150) | 可空 | 待审修订联系信息 |
| reviewed_by | BIGINT | 可空；FK | 商户资料审核人 |
| reviewed_at | DATETIME2(3) | 可空 | 最近审核时间 |
| review_reason | NVARCHAR(500) | 可空 | 审核意见 |
| created_at | DATETIME2(3) | 非空 | 商户创建时间 |
| updated_at | DATETIME2(3) | 非空 | 资料最近更新时间 |
| version | ROWVERSION | 非空 | 并发修改版本标记 |

主键：id。唯一约束：(merchant_code)。

外键：reviewed_by → app_user(id)。

## A.3 商户成员（merchant_member）

**表A-3 商户成员字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 成员关系主键 |
| merchant_id | BIGINT | 非空；UK；FK | 所属商户 |
| user_id | BIGINT | 非空；UK；FK | 成员对应账号 |
| role_code | VARCHAR(16) | 非空 | 商户内角色 |
| status | VARCHAR(16) | 非空 | 成员关系是否有效 |
| created_at | DATETIME2(3) | 非空 | 加入商户时间 |

主键：id。唯一约束：(merchant_id, user_id)。

外键：merchant_id → merchant(id)；user_id → app_user(id)。

## A.4 门店（store）

**表A-4 门店字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 门店主键 |
| merchant_id | BIGINT | 非空；UK；FK | 所属商户 |
| store_code | VARCHAR(32) | 非空；UK | 商户内唯一门店编号 |
| name | NVARCHAR(100) | 非空 | 门店名称 |
| location_text | NVARCHAR(200) | 可空 | 门店位置说明 |
| status | VARCHAR(16) | 非空 | 门店启停状态 |
| created_at | DATETIME2(3) | 非空 | 门店创建时间 |
| updated_at | DATETIME2(3) | 非空 | 门店最近更新时间 |
| version | ROWVERSION | 非空 | 并发修改版本标记 |

主键：id。唯一约束：(merchant_id, store_code)。

外键：merchant_id → merchant(id)。

## A.5 业务订单（business_order）

**表A-5 业务订单字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 订单主键 |
| order_no | VARCHAR(60) | 非空；UQ | 唯一订单业务编号 |
| store_id | BIGINT | 非空；FK | 收款门店 |
| consumer_id | BIGINT | 可空；FK | 关联消费者，可不指定 |
| created_by | BIGINT | 非空；FK | 订单创建人 |
| client_request_id | VARCHAR(36) | 非空；UQ | 创建操作幂等标识 |
| subject | NVARCHAR(160) | 非空 | 收款事项标题 |
| remark | NVARCHAR(1000) | 可空 | 订单备注 |
| status | VARCHAR(24) | 非空 | 订单业务状态 |
| created_at | DATETIME2(3) | 非空 | 订单创建时间 |
| updated_at | DATETIME2(3) | 非空 | 订单最近更新时间 |
| version | ROWVERSION | 非空 | 并发修改版本标记 |

主键：id。唯一约束：(client_request_id)；(order_no)。

外键：consumer_id → app_user(id)；created_by → app_user(id)；store_id → store(id)。

## A.6 订单明细（order_item）

**表A-6 订单明细字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 明细行主键 |
| order_id | BIGINT | 非空；UK；FK | 所属订单 |
| line_no | INT | 非空；UK | 订单内唯一行号 |
| item_name | NVARCHAR(120) | 非空 | 成交时的项目名称 |
| category_code | NVARCHAR(30) | 非空 | 项目分类 |
| quantity | INT | 非空 | 项目数量，须为正整数 |
| unit_price_cent | BIGINT | 非空 | 成交单价，单位分，允许零 |

主键：id。唯一约束：(order_id, line_no)。

外键：order_id → business_order(id)。

## A.7 支付尝试（payment_attempt）

**表A-7 支付尝试字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 支付尝试主键 |
| order_id | BIGINT | 非空；FK | 对应订单 |
| client_request_id | VARCHAR(36) | 非空；UQ | 预下单操作幂等标识 |
| out_trade_no | VARCHAR(64) | 非空；UQ | 商户交易号，跨渠道请求追踪 |
| channel_code | VARCHAR(16) | 非空 | 渠道代码，默认ALIPAY |
| channel_env | VARCHAR(24) | 非空 | DEMO或ALIPAY_SANDBOX |
| app_id | VARCHAR(64) | 非空 | 渠道应用标识 |
| seller_id | VARCHAR(64) | 非空 | 渠道收款方标识 |
| channel_trade_no | VARCHAR(100) | 可空 | 渠道返回的交易号 |
| status | VARCHAR(16) | 非空 | 支付尝试状态 |
| is_primary | BIT | 非空 | 是否为订单主支付 |
| next_query_at | DATETIME2(3) | 可空 | 下次自动查询时刻 |
| query_count | INT | 非空 | 已执行的结果查询次数 |
| confirmed_at | DATETIME2(3) | 可空 | 渠道确认的支付发生时刻 |
| created_at | DATETIME2(3) | 非空 | 支付尝试创建时间 |
| updated_at | DATETIME2(3) | 非空 | 支付尝试最近更新时间 |
| qr_code | NVARCHAR(2048) | 可空 | 当前可用支付二维码内容 |
| last_result_code | VARCHAR(64) | 可空 | 最近渠道或业务结果代码 |
| last_result_message | NVARCHAR(300) | 可空 | 面向用户的最近结果说明 |
| next_action | VARCHAR(32) | 可空 | 当前建议动作，如查询或重试 |
| precreate_count | INT | 非空 | 预下单尝试次数 |
| dispatch_token | VARCHAR(36) | 可空 | 本轮发送的排他租约令牌 |
| dispatch_lease_until | DATETIME2(3) | 可空 | 发送租约失效时刻 |

主键：id。唯一约束：(out_trade_no)；(client_request_id)。

外键：order_id → business_order(id)。

## A.8 支付通知（payment_notification）

**表A-8 支付通知字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 通知记录主键 |
| payment_id | BIGINT | 可空；FK | 能够识别时关联支付尝试 |
| notify_id | VARCHAR(100) | 可空 | 渠道通知标识，允许缺失 |
| payload_file_uri | NVARCHAR(300) | 可空 | 通知原始内容的受控文件位置 |
| payload_hash | CHAR(64) | 非空 | 通知内容SHA-256摘要 |
| signature_valid | BIT | 非空 | 签名校验是否通过 |
| handling_result | NVARCHAR(300) | 非空 | 通知处理结果或拒绝原因 |
| received_at | DATETIME2(3) | 非空 | 系统接收通知的时刻 |
| request_id | VARCHAR(36) | 非空 | 当前接口请求追踪标识 |

主键：id。唯一约束：无额外唯一约束。

外键：payment_id → payment_attempt(id)。

## A.9 退款申请（refund_request）

**表A-9 退款申请字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 退款申请主键 |
| payment_id | BIGINT | 非空；FK | 原支付尝试 |
| client_request_id | VARCHAR(36) | 非空；UQ | 申请操作幂等标识 |
| out_request_no | VARCHAR(64) | 非空；UQ | 渠道退款请求号 |
| amount_cent | BIGINT | 非空 | 申请退款金额，单位分 |
| reason | NVARCHAR(1000) | 非空 | 申请退款的业务原因 |
| status | VARCHAR(24) | 非空 | 申请、发送及确认状态 |
| applicant_id | BIGINT | 非空；FK | 退款申请人 |
| reviewed_by | BIGINT | 可空；FK | 退款审核人 |
| reviewed_at | DATETIME2(3) | 可空 | 退款审核时间 |
| review_reason | NVARCHAR(1000) | 可空 | 审核意见 |
| query_count | INT | 非空 | 已执行的退款查询次数 |
| next_query_at | DATETIME2(3) | 可空 | 下次自动查询时刻 |
| succeeded_at | DATETIME2(3) | 可空 | 渠道确认的退款发生时刻 |
| dispatch_token | VARCHAR(36) | 可空 | 本轮退款发送租约令牌 |
| dispatch_lease_until | DATETIME2(3) | 可空 | 退款发送租约失效时刻 |
| created_at | DATETIME2(3) | 非空 | 退款申请创建时间 |
| updated_at | DATETIME2(3) | 非空 | 退款申请最近更新时间 |
| version | ROWVERSION | 非空 | 并发修改版本标记 |

主键：id。唯一约束：(out_request_no)；(client_request_id)。

外键：applicant_id → app_user(id)；payment_id → payment_attempt(id)；reviewed_by → app_user(id)。

## A.10 资金事实（transaction_entry）

**表A-10 资金事实字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 资金事实主键 |
| entry_no | VARCHAR(64) | 非空；UQ | 唯一流水业务编号 |
| event_key | VARCHAR(150) | 非空；UQ | 唯一事件键：P:交易号或R:退款号 |
| entry_type | VARCHAR(12) | 非空 | 收款或退款方向 |
| payment_id | BIGINT | 可空；FK | 收款来源支付；退款时为空 |
| refund_id | BIGINT | 可空；FK | 退款来源申请；收款时为空 |
| amount_cent | BIGINT | 非空 | 已确认金额，单位分，始终为正 |
| channel_occurred_at | DATETIME2(3) | 非空 | 渠道实际发生时刻 |
| recorded_at | DATETIME2(3) | 非空 | 本系统记录资金事实的时刻 |
| evidence_file_uri | NVARCHAR(300) | 可空 | 确认依据的受控文件位置 |

主键：id。唯一约束：(event_key)；(entry_no)。

外键：payment_id → payment_attempt(id)；refund_id → refund_request(id)。

## A.11 对账批次与获取任务（reconciliation_batch）

**表A-11 对账批次与获取任务字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 批次或任务主键 |
| batch_no | VARCHAR(64) | 非空；UQ | 唯一批次业务编号 |
| scope_type | VARCHAR(24) | 非空 | 商户范围或平台未归属范围 |
| merchant_id | BIGINT | 可空；FK | 所属商户；未归属范围为空 |
| period_start | DATETIME2(3) | 非空 | 账期起点，包含该时刻 |
| period_end | DATETIME2(3) | 非空 | 账期终点，不包含该时刻 |
| source_type | VARCHAR(24) | 非空 | 文件、查询、种子或自动任务来源 |
| statement_source_batch_id | BIGINT | 可空；FK | 重跑时引用的原始输入批次 |
| baseline_batch_id | BIGINT | 可空；FK | 重跑比较使用的基线批次 |
| source_file_uri | NVARCHAR(300) | 可空 | 账单原件的受控文件位置 |
| file_sha256 | CHAR(64) | 可空 | 原件SHA-256摘要 |
| ingest_options_sha256 | CHAR(64) | 可空 | 归属、账期等导入选项的摘要 |
| completeness | VARCHAR(24) | 非空 | 账单完整性声明 |
| status | VARCHAR(16) | 非空 | 导入或对账批次状态 |
| observed_at | DATETIME2(3) | 可空 | 本次对账观察时刻 |
| started_at | DATETIME2(3) | 可空 | 本次对账开始时刻 |
| finished_at | DATETIME2(3) | 可空 | 批次或获取任务结束时刻 |
| created_by | BIGINT | 可空；FK | 批次或任务创建人 |
| created_at | DATETIME2(3) | 非空 | 批次或任务创建时间 |
| acquisition_key | CHAR(64) | 可空 | 环境、应用与账日组合的任务摘要键 |
| acquisition_state | VARCHAR(24) | 可空 | 自动获取任务状态 |
| acquisition_env | VARCHAR(20) | 可空 | 自动任务使用的渠道环境 |
| acquisition_app | VARCHAR(64) | 可空 | 自动任务使用的应用标识 |
| acquisition_attempts | INT | 非空 | 本轮获取已尝试次数，最多4次 |
| acquisition_generation | INT | 非空 | 补跑代次；申请补跑时递增 |
| acquisition_token | VARCHAR(36) | 可空 | 当前执行者持有的租约令牌 |
| acquisition_lease_until | DATETIME2(3) | 可空 | 自动任务执行租约失效时刻 |
| acquisition_next_at | DATETIME2(3) | 可空 | 下次自动获取或重试时刻 |
| acquisition_message | NVARCHAR(500) | 可空 | 任务进度、结果或失败说明 |
| acquisition_format | VARCHAR(8) | 可空 | 已保存原件的格式，如xml、zip、csv |
| acquisition_result | NVARCHAR(MAX) | 可空 | 任务结果JSON，含子批次或错误信息 |
| acquisition_daily | BIT | 非空 | 是否作为每日获取的启用配置 |
| acquisition_owner | BIGINT | 可空；FK | 当前授权执行者；权限失效可暂停任务 |

主键：id。唯一约束：(batch_no)。

外键：acquisition_owner → app_user(id)；baseline_batch_id → reconciliation_batch(id)；created_by → app_user(id)；merchant_id → merchant(id)；statement_source_batch_id → reconciliation_batch(id)。

source_type=AUTO_JOB 时，status 保持任务容器的 IMPORTING，执行进度使用 acquisition_state。普通账单批次的生命周期使用 status。acquisition_owner 是授权执行账号，acquisition_token 是工作者租约。

## A.12 原始账单行（statement_line）

**表A-12 原始账单行字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 账单行主键 |
| import_batch_id | BIGINT | 非空；UK；FK | 原始导入批次 |
| line_no | INT | 非空；UK | 原件内行号，同批次唯一 |
| event_type | VARCHAR(12) | 非空 | 渠道记录的收款或退款类型 |
| out_trade_no | VARCHAR(64) | 可空 | 商户交易号，可缺失 |
| channel_trade_no | VARCHAR(100) | 可空 | 渠道交易号，可缺失 |
| out_request_no | VARCHAR(64) | 可空 | 退款请求号，可缺失 |
| amount_cent | BIGINT | 非空 | 渠道记录金额，单位分 |
| channel_occurred_at | DATETIME2(3) | 非空 | 渠道记录的发生时刻 |
| raw_line_file_uri | NVARCHAR(300) | 可空 | 该原始行内容的受控文件位置 |

主键：id。唯一约束：(import_batch_id, line_no)。

外键：import_batch_id → reconciliation_batch(id)。

## A.13 对账结果（reconciliation_result）

**表A-13 对账结果字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 观察结果主键 |
| batch_id | BIGINT | 非空；UK；FK | 所属对账观察批次 |
| result_key | VARCHAR(180) | 非空；UK | 同批次内唯一结果键 |
| source_event_key | VARCHAR(150) | 非空 | 规范事件键或问题行追踪键 |
| entry_id | BIGINT | 可空；FK | 命中的本地资金事实，可空 |
| statement_line_id | BIGINT | 可空；FK | 命中的渠道账单行，可空 |
| classification | VARCHAR(32) | 非空 | 八种对账分类之一 |
| reason_code | VARCHAR(60) | 非空 | 分类的具体原因代码 |
| detected_at | DATETIME2(3) | 非空 | 该结果被检测的时刻 |

主键：id。唯一约束：(batch_id, result_key)。

外键：batch_id → reconciliation_batch(id)；entry_id → transaction_entry(id)；statement_line_id → statement_line(id)。

## A.14 差错工单（exception_task）

**表A-14 差错工单字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 差错工单主键 |
| case_no | VARCHAR(64) | 非空；UQ | 唯一工单业务编号 |
| case_key | VARCHAR(240) | 非空；UQ | 范围、事件与分类构成的去重键 |
| origin_result_id | BIGINT | 非空；UQ；FK | 最初异常结果，不可改写 |
| latest_result_id | BIGINT | 非空；FK | 当前关联的最新观察结果 |
| assignee_id | BIGINT | 可空；FK | 当前经办人 |
| status | VARCHAR(24) | 非空 | 工单处理阶段 |
| title | NVARCHAR(200) | 非空 | 工单标题 |
| description | NVARCHAR(MAX) | 非空 | 异常起因描述 |
| analysis_text | NVARCHAR(MAX) | 可空 | 经办分析正文 |
| resolution_text | NVARCHAR(MAX) | 可空 | 处理或复核结论说明 |
| resolution_code | VARCHAR(40) | 可空 | 结案类型代码 |
| evidence_file_uri | NVARCHAR(300) | 可空 | 经办证据附件的受控位置 |
| submitted_by | BIGINT | 可空；FK | 提交分析并申请复核的人员 |
| reviewed_by | BIGINT | 可空；FK | 最近复核人员 |
| due_at | DATETIME2(3) | 可空 | 要求完成的截止时间 |
| closed_at | DATETIME2(3) | 可空 | 最近结案时间 |
| created_at | DATETIME2(3) | 非空 | 工单创建时间 |
| updated_at | DATETIME2(3) | 非空 | 工单最近更新时间 |
| version | ROWVERSION | 非空 | 并发修改版本标记 |

主键：id。唯一约束：(origin_result_id)；(case_key)；(case_no)。

外键：assignee_id → app_user(id)；latest_result_id → reconciliation_result(id)；origin_result_id → reconciliation_result(id)；reviewed_by → app_user(id)；submitted_by → app_user(id)。

## A.15 审计日志（audit_log）

**表A-15 审计日志字段字典**

| 字段 | 类型 | 空值与键 | 含义 |
|---|---|---|---|
| id | BIGINT | 非空；PK | 审计记录主键 |
| event_category | VARCHAR(24) | 非空 | 审计事件类别 |
| actor_user_id | BIGINT | 可空；FK | 操作用户；系统事件可空 |
| scope_merchant_id | BIGINT | 可空；FK | 事件涉及的商户范围 |
| object_type | VARCHAR(60) | 可空 | 多态业务对象类型 |
| object_id | BIGINT | 可空 | 对应类型的业务对象编号 |
| action | NVARCHAR(100) | 非空 | 操作名称或动作代码 |
| result | VARCHAR(24) | 非空 | 操作结果代码 |
| summary | NVARCHAR(1000) | 可空 | 可读事件摘要 |
| before_json | NVARCHAR(MAX) | 可空 | 变更前字段的JSON快照 |
| after_json | NVARCHAR(MAX) | 可空 | 变更后字段或结果的JSON快照 |
| occurred_at | DATETIME2(3) | 非空 | 审计事件发生时刻 |
| request_id | VARCHAR(36) | 可空 | 接口请求追踪标识，可空 |

主键：id。唯一约束：无额外唯一约束。

外键：actor_user_id → app_user(id)；scope_merchant_id → merchant(id)。

object_type 与 object_id 共同定位不同类型的业务对象，object_id 未设置指向固定业务表的外键。快照保存审计事件发生时的内容。

# 附录B 显式索引与CHECK约束

主键和唯一约束产生34个索引，另有19个显式业务索引，合计53个B树索引。本附录列出这19个显式索引；均为非聚集索引。键列未标注方向时为升序；INCLUDE列仅覆盖查询，不参与唯一性判断。全文索引独立于本表统计。

## B.1 显式业务索引

**表B-1 六个筛选唯一索引**

| 索引 | 所在表及键列 | WHERE条件 |
|---|---|---|
| UX_payment_primary | payment_attempt(order_id) | ([is_primary]=(1)) |
| UX_payment_channel | payment_attempt(channel_env, app_id, channel_trade_no) | ([channel_trade_no] IS NOT NULL) |
| UX_entry_payment | transaction_entry(payment_id) | ([payment_id] IS NOT NULL) |
| UX_entry_refund | transaction_entry(refund_id) | ([refund_id] IS NOT NULL) |
| UX_import_file | reconciliation_batch(scope_type, merchant_id, file_sha256, ingest_options_sha256) | ([file_sha256] IS NOT NULL AND [statement_source_batch_id] IS NULL) |
| UX_batch_acquisition_key | reconciliation_batch(acquisition_key) | ([acquisition_key] IS NOT NULL) |

**表B-2 十三个非唯一业务索引**

| 索引与所在表 | 键列 | INCLUDE列 | WHERE条件 |
|---|---|---|---|
| IX_order_store_time（business_order） | store_id, created_at | status | 无 |
| IX_payment_pending（payment_attempt） | status, next_query_at | 无 | 无 |
| IX_payment_order（payment_attempt） | order_id | status, is_primary | 无 |
| IX_refund_pending（refund_request） | status, next_query_at | payment_id, amount_cent | 无 |
| IX_entry_time（transaction_entry） | channel_occurred_at | entry_type, amount_cent, payment_id, refund_id | 无 |
| IX_batch_acquisition_work（reconciliation_batch） | acquisition_env, acquisition_app, acquisition_state, acquisition_next_at | acquisition_lease_until | ([source_type] = 'AUTO_JOB') |
| IX_statement_key（statement_line） | import_batch_id, out_trade_no, out_request_no | 无 | 无 |
| IX_result_batch（reconciliation_result） | batch_id, classification | 无 | 无 |
| IX_result_event_observation（reconciliation_result） | source_event_key, detected_at | batch_id, classification | 无 |
| IX_case_assignee（exception_task） | assignee_id, status, due_at | 无 | 无 |
| IX_audit_scope（audit_log） | scope_merchant_id, occurred_at | 无 | 无 |
| IX_audit_actor（audit_log） | actor_user_id, occurred_at | 无 | 无 |
| IX_audit_case_snapshot（audit_log） | object_id, occurred_at DESC, id DESC | after_json | ([object_type] = 'CASE' AND [event_category] = 'DB_CHANGE') |

定义示例：IX_refund_pending 的键为 (status, next_query_at)，包含 payment_id、amount_cent，允许不同退款记录具有相同的状态和查询时间。UX_payment_primary 则只覆盖 is_primary=1 的记录，约束每个订单至多有一条主支付，不限制订单具有多次普通支付尝试。

## B.2 状态与类型CHECK约束

数据库实际共有27个CHECK约束。以下按业务作用使用报告编号B-C01至B-C27，SQL Server中各约束另有名称。表达式将同列的多个等值OR合并为等价的IN写法，保留原有允许值及空值语义；行列是否允许NULL仍以附录A为准。

**表B-3 状态与类型约束（18项）**

| 编号 | 表与字段 | CHECK表达式 |
|---|---|---|
| B-C01 | app_user.platform_role | platform_role IN ('MEMBER', 'PLATFORM_FINANCE', 'ADMIN', 'AUDITOR') |
| B-C02 | app_user.status | status IN ('PENDING', 'ACTIVE', 'REJECTED', 'DISABLED') |
| B-C03 | merchant.status | status IN ('PENDING', 'ACTIVE', 'REJECTED', 'DISABLED') |
| B-C04 | merchant_member.role_code | role_code IN ('OWNER', 'CASHIER', 'FINANCE', 'REVIEWER') |
| B-C05 | merchant_member.status | status IN ('ACTIVE', 'DISABLED') |
| B-C06 | store.status | status IN ('ACTIVE', 'DISABLED') |
| B-C07 | business_order.status | status IN ('DRAFT', 'WAITING_PAYMENT', 'PAID', 'CANCELED', 'PAYMENT_EXCEPTION') |
| B-C08 | payment_attempt.channel_env | channel_env IN ('DEMO', 'ALIPAY_SANDBOX') |
| B-C09 | payment_attempt.status | status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'CLOSED', 'FAILED', 'UNKNOWN') |
| B-C10 | refund_request.status | status IN ('PENDING_REVIEW', 'APPROVED', 'PROCESSING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'UNKNOWN') |
| B-C11 | transaction_entry.entry_type | entry_type IN ('RECEIPT', 'REFUND') |
| B-C12 | statement_line.event_type | event_type IN ('RECEIPT', 'REFUND') |
| B-C13 | reconciliation_batch.scope_type | scope_type IN ('MERCHANT', 'PLATFORM_UNASSIGNED') |
| B-C14 | reconciliation_batch.completeness | completeness IN ('UNKNOWN', 'DECLARED_COMPLETE', 'PROVIDER_COMPLETE') |
| B-C15 | reconciliation_batch.status | status IN ('IMPORTING', 'READY', 'RUNNING', 'COMPLETED', 'FAILED') |
| B-C16 | reconciliation_batch.source_type | source_type IN ('XLSX', 'XML', 'ALIPAY_CSV', 'ALIPAY_ZIP', 'TRADE_QUERY', 'DEMO_SEED', 'AUTO_JOB') |
| B-C17 | reconciliation_result.classification | classification IN ('MATCHED', 'CHANNEL_ONLY', 'LOCAL_ONLY', 'AMOUNT_MISMATCH', 'BILL_DUPLICATE', 'KEY_UNRESOLVED', 'PENDING_CONFIRMATION', 'EXTRA_SUCCESS') |
| B-C18 | exception_task.status | status IN ('OPEN', 'ASSIGNED', 'PROCESSING', 'PENDING_REVIEW', 'CLOSED') |

## B.3 数量、金额与时间CHECK约束

**表B-4 数值及账期约束（6项）**

| 编号 | 所在表 | CHECK表达式及含义 |
|---|---|---|
| B-C19 | order_item | quantity > 0；项目数量为正 |
| B-C20 | order_item | unit_price_cent >= 0；单价允许零 |
| B-C21 | refund_request | amount_cent > 0；退款申请金额为正 |
| B-C22 | transaction_entry | amount_cent > 0；记账方向由类型另行表达 |
| B-C23 | statement_line | amount_cent > 0；渠道行金额为正 |
| B-C24 | reconciliation_batch | period_end > period_start；账期非空且顺序正确 |

金额列CHECK不承担跨行退款总额控制；累计额度由函数和审批事务共同校验。账期使用左闭右开的范围，结束时刻不计入该批次。

## B.4 跨字段CHECK约束

B-C25约束 reconciliation_batch 的范围与商户是否指定相一致。它允许未归属账单进入平台处理范围，避免把缺少商户归属的记录归入任意商户。

```sql
CHECK ((scope_type='MERCHANT' AND merchant_id IS NOT NULL)
    OR (scope_type='PLATFORM_UNASSIGNED' AND merchant_id IS NULL))
```

B-C26约束 transaction_entry 的流水来源互斥。收款必须关联支付，退款必须关联退款申请，不能两者同时存在或同时为空。

```sql
CHECK ((entry_type='RECEIPT' AND payment_id IS NOT NULL
        AND refund_id IS NULL)
    OR (entry_type='REFUND' AND payment_id IS NULL
        AND refund_id IS NOT NULL))
```

B-C27为 reconciliation_batch 的 CK_batch_acquisition。普通批次不设置获取任务键；自动获取任务需要完整的环境、应用、执行状态及有效次数，保持任务容器与普通导入批次的区别。该约束不强制普通批次的全部 acquisition_* 字段均为空。

```sql
CHECK ((source_type<>'AUTO_JOB' AND acquisition_key IS NULL)
    OR (source_type='AUTO_JOB'
        AND scope_type='PLATFORM_UNASSIGNED'
        AND merchant_id IS NULL
        AND status='IMPORTING'
        AND acquisition_key IS NOT NULL
        AND acquisition_env IS NOT NULL
        AND acquisition_app IS NOT NULL
        AND acquisition_state IS NOT NULL
        AND acquisition_state IN (
          'QUEUED','FETCHING','VALIDATING','RECONCILING',
          'RETRY_WAIT','SUCCEEDED','FAILED','UNCONFIGURED',
          'UNSUPPORTED','TEMPLATE_ONLY')
        AND acquisition_attempts BETWEEN 0 AND 4
        AND acquisition_generation>0))
```

acquisition_state=TEMPLATE_ONLY 表示已取得并保存沙箱模板，该任务未进入真实交易账单核对。工单结案证据、退款申请人与审核人分离等跨行规则由存储过程及触发器校验。
