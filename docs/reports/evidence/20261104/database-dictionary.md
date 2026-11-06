# 最终字段字典

来自 2026 年 11 月 4 日 ReconisDemo 的 sys 元数据，共 15 表 207 列。nvarchar/nchar 长度按字符展示，ROWVERSION 为自动维护的 8 字节版本标记。组合唯一约束和筛选唯一索引在涉及列中重复注明，不能将其误读为每列分别唯一。

## app_user

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| username | NVARCHAR(64) | 否 | UNIQUE(username) | — |
| password_hash | VARCHAR(100) | 否 | — | — |
| display_name | NVARCHAR(80) | 否 | — | — |
| platform_role | VARCHAR(24) | 否 | — | ('MEMBER') |
| status | VARCHAR(16) | 否 | — | ('PENDING') |
| reviewed_by | BIGINT | 是 | FK app_user.id | — |
| reviewed_at | DATETIME2(3) | 是 | — | — |
| review_reason | NVARCHAR(500) | 是 | — | — |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| version | ROWVERSION (BINARY(8)) | 否 | — | — |

表级 CHECK：

```sql
([platform_role]='AUDITOR' OR [platform_role]='ADMIN' OR [platform_role]='PLATFORM_FINANCE' OR [platform_role]='MEMBER')
```

```sql
([status]='DISABLED' OR [status]='REJECTED' OR [status]='ACTIVE' OR [status]='PENDING')
```

## audit_log

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| event_category | VARCHAR(24) | 否 | — | — |
| actor_user_id | BIGINT | 是 | FK app_user.id | — |
| scope_merchant_id | BIGINT | 是 | FK merchant.id | — |
| object_type | VARCHAR(60) | 是 | — | — |
| object_id | BIGINT | 是 | — | — |
| action | NVARCHAR(100) | 否 | — | — |
| result | VARCHAR(24) | 否 | — | — |
| summary | NVARCHAR(1000) | 是 | — | — |
| before_json | NVARCHAR(MAX) | 是 | — | — |
| after_json | NVARCHAR(MAX) | 是 | — | — |
| occurred_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| request_id | VARCHAR(36) | 是 | — | — |

## business_order

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| order_no | VARCHAR(60) | 否 | UNIQUE(order_no) | — |
| store_id | BIGINT | 否 | FK store.id | — |
| consumer_id | BIGINT | 是 | FK app_user.id | — |
| created_by | BIGINT | 否 | FK app_user.id | — |
| client_request_id | VARCHAR(36) | 否 | UNIQUE(client_request_id) | — |
| subject | NVARCHAR(160) | 否 | — | — |
| remark | NVARCHAR(1000) | 是 | — | — |
| status | VARCHAR(24) | 否 | — | ('DRAFT') |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| version | ROWVERSION (BINARY(8)) | 否 | — | — |

表级 CHECK：

```sql
([status]='PAYMENT_EXCEPTION' OR [status]='CANCELED' OR [status]='PAID' OR [status]='WAITING_PAYMENT' OR [status]='DRAFT')
```

## exception_task

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| case_no | VARCHAR(64) | 否 | UNIQUE(case_no) | — |
| case_key | VARCHAR(240) | 否 | UNIQUE(case_key) | — |
| origin_result_id | BIGINT | 否 | UNIQUE(origin_result_id); FK reconciliation_result.id | — |
| latest_result_id | BIGINT | 否 | FK reconciliation_result.id | — |
| assignee_id | BIGINT | 是 | FK app_user.id | — |
| status | VARCHAR(24) | 否 | — | ('OPEN') |
| title | NVARCHAR(200) | 否 | — | — |
| description | NVARCHAR(MAX) | 否 | — | — |
| analysis_text | NVARCHAR(MAX) | 是 | — | — |
| resolution_text | NVARCHAR(MAX) | 是 | — | — |
| resolution_code | VARCHAR(40) | 是 | — | — |
| evidence_file_uri | NVARCHAR(300) | 是 | — | — |
| submitted_by | BIGINT | 是 | FK app_user.id | — |
| reviewed_by | BIGINT | 是 | FK app_user.id | — |
| due_at | DATETIME2(3) | 是 | — | — |
| closed_at | DATETIME2(3) | 是 | — | — |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| version | ROWVERSION (BINARY(8)) | 否 | — | — |

表级 CHECK：

```sql
([status]='CLOSED' OR [status]='PENDING_REVIEW' OR [status]='PROCESSING' OR [status]='ASSIGNED' OR [status]='OPEN')
```

## merchant

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| merchant_code | VARCHAR(32) | 否 | UNIQUE(merchant_code) | — |
| name | NVARCHAR(100) | 否 | — | — |
| category_code | NVARCHAR(30) | 否 | — | — |
| contact | NVARCHAR(150) | 是 | — | — |
| status | VARCHAR(16) | 否 | — | ('PENDING') |
| proposed_name | NVARCHAR(100) | 是 | — | — |
| proposed_category | NVARCHAR(30) | 是 | — | — |
| proposed_contact | NVARCHAR(150) | 是 | — | — |
| reviewed_by | BIGINT | 是 | FK app_user.id | — |
| reviewed_at | DATETIME2(3) | 是 | — | — |
| review_reason | NVARCHAR(500) | 是 | — | — |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| version | ROWVERSION (BINARY(8)) | 否 | — | — |

表级 CHECK：

```sql
([status]='DISABLED' OR [status]='REJECTED' OR [status]='ACTIVE' OR [status]='PENDING')
```

## merchant_member

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| merchant_id | BIGINT | 否 | UNIQUE(merchant_id,user_id); FK merchant.id | — |
| user_id | BIGINT | 否 | UNIQUE(merchant_id,user_id); FK app_user.id | — |
| role_code | VARCHAR(16) | 否 | — | — |
| status | VARCHAR(16) | 否 | — | ('ACTIVE') |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |

表级 CHECK：

```sql
([role_code]='REVIEWER' OR [role_code]='FINANCE' OR [role_code]='CASHIER' OR [role_code]='OWNER')
```

```sql
([status]='DISABLED' OR [status]='ACTIVE')
```

## order_item

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| order_id | BIGINT | 否 | UNIQUE(order_id,line_no); FK business_order.id | — |
| line_no | INT | 否 | UNIQUE(order_id,line_no) | — |
| item_name | NVARCHAR(120) | 否 | — | — |
| category_code | NVARCHAR(30) | 否 | — | (N'其他') |
| quantity | INT | 否 | — | — |
| unit_price_cent | BIGINT | 否 | — | — |

表级 CHECK：

```sql
([quantity]>(0))
```

```sql
([unit_price_cent]>=(0))
```

## payment_attempt

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| order_id | BIGINT | 否 | UNIQUE(order_id) WHERE ([is_primary]=(1)); FK business_order.id | — |
| client_request_id | VARCHAR(36) | 否 | UNIQUE(client_request_id) | — |
| out_trade_no | VARCHAR(64) | 否 | UNIQUE(out_trade_no) | — |
| channel_code | VARCHAR(16) | 否 | — | ('ALIPAY') |
| channel_env | VARCHAR(24) | 否 | UNIQUE(channel_env,app_id,channel_trade_no) WHERE ([channel_trade_no] IS NOT NULL) | — |
| app_id | VARCHAR(64) | 否 | UNIQUE(channel_env,app_id,channel_trade_no) WHERE ([channel_trade_no] IS NOT NULL) | — |
| seller_id | VARCHAR(64) | 否 | — | — |
| channel_trade_no | VARCHAR(100) | 是 | UNIQUE(channel_env,app_id,channel_trade_no) WHERE ([channel_trade_no] IS NOT NULL) | — |
| status | VARCHAR(16) | 否 | — | ('CREATED') |
| is_primary | BIT | 否 | — | ((0)) |
| next_query_at | DATETIME2(3) | 是 | — | — |
| query_count | INT | 否 | — | ((0)) |
| confirmed_at | DATETIME2(3) | 是 | — | — |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| qr_code | NVARCHAR(2048) | 是 | — | — |
| last_result_code | VARCHAR(64) | 是 | — | — |
| last_result_message | NVARCHAR(300) | 是 | — | — |
| next_action | VARCHAR(32) | 是 | — | — |
| precreate_count | INT | 否 | — | ((0)) |
| dispatch_token | VARCHAR(36) | 是 | — | — |
| dispatch_lease_until | DATETIME2(3) | 是 | — | — |

表级 CHECK：

```sql
([channel_env]='ALIPAY_SANDBOX' OR [channel_env]='DEMO')
```

```sql
([status]='UNKNOWN' OR [status]='FAILED' OR [status]='CLOSED' OR [status]='SUCCEEDED' OR [status]='PENDING' OR [status]='CREATED')
```

## payment_notification

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| payment_id | BIGINT | 是 | FK payment_attempt.id | — |
| notify_id | VARCHAR(100) | 是 | — | — |
| payload_file_uri | NVARCHAR(300) | 是 | — | — |
| payload_hash | CHAR(64) | 否 | — | — |
| signature_valid | BIT | 否 | — | — |
| handling_result | NVARCHAR(300) | 否 | — | — |
| received_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| request_id | VARCHAR(36) | 否 | — | — |

## reconciliation_batch

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| batch_no | VARCHAR(64) | 否 | UNIQUE(batch_no) | — |
| scope_type | VARCHAR(24) | 否 | UNIQUE(scope_type,merchant_id,file_sha256,ingest_options_sha256) WHERE ([file_sha256] IS NOT NULL AND [statement_source_batch_id] IS NULL) | — |
| merchant_id | BIGINT | 是 | UNIQUE(scope_type,merchant_id,file_sha256,ingest_options_sha256) WHERE ([file_sha256] IS NOT NULL AND [statement_source_batch_id] IS NULL); FK merchant.id | — |
| period_start | DATETIME2(3) | 否 | — | — |
| period_end | DATETIME2(3) | 否 | — | — |
| source_type | VARCHAR(24) | 否 | — | — |
| statement_source_batch_id | BIGINT | 是 | FK reconciliation_batch.id | — |
| baseline_batch_id | BIGINT | 是 | FK reconciliation_batch.id | — |
| source_file_uri | NVARCHAR(300) | 是 | — | — |
| file_sha256 | CHAR(64) | 是 | UNIQUE(scope_type,merchant_id,file_sha256,ingest_options_sha256) WHERE ([file_sha256] IS NOT NULL AND [statement_source_batch_id] IS NULL) | — |
| ingest_options_sha256 | CHAR(64) | 是 | UNIQUE(scope_type,merchant_id,file_sha256,ingest_options_sha256) WHERE ([file_sha256] IS NOT NULL AND [statement_source_batch_id] IS NULL) | — |
| completeness | VARCHAR(24) | 否 | — | ('UNKNOWN') |
| status | VARCHAR(16) | 否 | — | ('READY') |
| observed_at | DATETIME2(3) | 是 | — | — |
| started_at | DATETIME2(3) | 是 | — | — |
| finished_at | DATETIME2(3) | 是 | — | — |
| created_by | BIGINT | 是 | FK app_user.id | — |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| acquisition_key | CHAR(64) | 是 | UNIQUE(acquisition_key) WHERE ([acquisition_key] IS NOT NULL) | — |
| acquisition_state | VARCHAR(24) | 是 | — | — |
| acquisition_env | VARCHAR(20) | 是 | — | — |
| acquisition_app | VARCHAR(64) | 是 | — | — |
| acquisition_attempts | INT | 否 | — | ((0)) |
| acquisition_generation | INT | 否 | — | ((1)) |
| acquisition_token | VARCHAR(36) | 是 | — | — |
| acquisition_lease_until | DATETIME2(3) | 是 | — | — |
| acquisition_next_at | DATETIME2(3) | 是 | — | — |
| acquisition_message | NVARCHAR(500) | 是 | — | — |
| acquisition_format | VARCHAR(8) | 是 | — | — |
| acquisition_result | NVARCHAR(MAX) | 是 | — | — |
| acquisition_daily | BIT | 否 | — | ((0)) |
| acquisition_owner | BIGINT | 是 | FK app_user.id | — |

表级 CHECK：

```sql
([scope_type]='PLATFORM_UNASSIGNED' OR [scope_type]='MERCHANT')
```

```sql
([completeness]='PROVIDER_COMPLETE' OR [completeness]='DECLARED_COMPLETE' OR [completeness]='UNKNOWN')
```

```sql
([status]='FAILED' OR [status]='COMPLETED' OR [status]='RUNNING' OR [status]='READY' OR [status]='IMPORTING')
```

```sql
([period_end]>[period_start])
```

```sql
([scope_type]='MERCHANT' AND [merchant_id] IS NOT NULL OR [scope_type]='PLATFORM_UNASSIGNED' AND [merchant_id] IS NULL)
```

```sql
([source_type]='AUTO_JOB' OR [source_type]='DEMO_SEED' OR [source_type]='TRADE_QUERY' OR [source_type]='ALIPAY_ZIP' OR [source_type]='ALIPAY_CSV' OR [source_type]='XML' OR [source_type]='XLSX')
```

```sql
([source_type]<>'AUTO_JOB' AND [acquisition_key] IS NULL OR [source_type]='AUTO_JOB' AND [scope_type]='PLATFORM_UNASSIGNED' AND [merchant_id] IS NULL AND [status]='IMPORTING' AND [acquisition_key] IS NOT NULL AND [acquisition_env] IS NOT NULL AND [acquisition_app] IS NOT NULL AND [acquisition_state] IS NOT NULL AND ([acquisition_state]='TEMPLATE_ONLY' OR [acquisition_state]='UNSUPPORTED' OR [acquisition_state]='UNCONFIGURED' OR [acquisition_state]='FAILED' OR [acquisition_state]='SUCCEEDED' OR [acquisition_state]='RETRY_WAIT' OR [acquisition_state]='RECONCILING' OR [acquisition_state]='VALIDATING' OR [acquisition_state]='FETCHING' OR [acquisition_state]='QUEUED') AND ([acquisition_attempts]>=(0) AND [acquisition_attempts]<=(4)) AND [acquisition_generation]>(0))
```

## reconciliation_result

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| batch_id | BIGINT | 否 | UNIQUE(batch_id,result_key); FK reconciliation_batch.id | — |
| result_key | VARCHAR(180) | 否 | UNIQUE(batch_id,result_key) | — |
| source_event_key | VARCHAR(150) | 否 | — | — |
| entry_id | BIGINT | 是 | FK transaction_entry.id | — |
| statement_line_id | BIGINT | 是 | FK statement_line.id | — |
| classification | VARCHAR(32) | 否 | — | — |
| reason_code | VARCHAR(60) | 否 | — | — |
| detected_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |

表级 CHECK：

```sql
([classification]='EXTRA_SUCCESS' OR [classification]='PENDING_CONFIRMATION' OR [classification]='KEY_UNRESOLVED' OR [classification]='BILL_DUPLICATE' OR [classification]='AMOUNT_MISMATCH' OR [classification]='LOCAL_ONLY' OR [classification]='CHANNEL_ONLY' OR [classification]='MATCHED')
```

## refund_request

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| payment_id | BIGINT | 否 | FK payment_attempt.id | — |
| client_request_id | VARCHAR(36) | 否 | UNIQUE(client_request_id) | — |
| out_request_no | VARCHAR(64) | 否 | UNIQUE(out_request_no) | — |
| amount_cent | BIGINT | 否 | — | — |
| reason | NVARCHAR(1000) | 否 | — | — |
| status | VARCHAR(24) | 否 | — | ('PENDING_REVIEW') |
| applicant_id | BIGINT | 否 | FK app_user.id | — |
| reviewed_by | BIGINT | 是 | FK app_user.id | — |
| reviewed_at | DATETIME2(3) | 是 | — | — |
| review_reason | NVARCHAR(1000) | 是 | — | — |
| query_count | INT | 否 | — | ((0)) |
| next_query_at | DATETIME2(3) | 是 | — | — |
| succeeded_at | DATETIME2(3) | 是 | — | — |
| dispatch_token | VARCHAR(36) | 是 | — | — |
| dispatch_lease_until | DATETIME2(3) | 是 | — | — |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| version | ROWVERSION (BINARY(8)) | 否 | — | — |

表级 CHECK：

```sql
([amount_cent]>(0))
```

```sql
([status]='UNKNOWN' OR [status]='FAILED' OR [status]='REJECTED' OR [status]='SUCCEEDED' OR [status]='PROCESSING' OR [status]='APPROVED' OR [status]='PENDING_REVIEW')
```

## statement_line

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| import_batch_id | BIGINT | 否 | UNIQUE(import_batch_id,line_no); FK reconciliation_batch.id | — |
| line_no | INT | 否 | UNIQUE(import_batch_id,line_no) | — |
| event_type | VARCHAR(12) | 否 | — | — |
| out_trade_no | VARCHAR(64) | 是 | — | — |
| channel_trade_no | VARCHAR(100) | 是 | — | — |
| out_request_no | VARCHAR(64) | 是 | — | — |
| amount_cent | BIGINT | 否 | — | — |
| channel_occurred_at | DATETIME2(3) | 否 | — | — |
| raw_line_file_uri | NVARCHAR(300) | 是 | — | — |

表级 CHECK：

```sql
([event_type]='REFUND' OR [event_type]='RECEIPT')
```

```sql
([amount_cent]>(0))
```

## store

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| merchant_id | BIGINT | 否 | UNIQUE(merchant_id,store_code); FK merchant.id | — |
| store_code | VARCHAR(32) | 否 | UNIQUE(merchant_id,store_code) | — |
| name | NVARCHAR(100) | 否 | — | — |
| location_text | NVARCHAR(200) | 是 | — | — |
| status | VARCHAR(16) | 否 | — | ('ACTIVE') |
| created_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| updated_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| version | ROWVERSION (BINARY(8)) | 否 | — | — |

表级 CHECK：

```sql
([status]='DISABLED' OR [status]='ACTIVE')
```

## transaction_entry

| 字段 | SQL 类型 | 可空 | 键与约束 | 默认值 |
|---|---|---|---|---|
| id | BIGINT | 否 | PK; IDENTITY | — |
| entry_no | VARCHAR(64) | 否 | UNIQUE(entry_no) | — |
| event_key | VARCHAR(150) | 否 | UNIQUE(event_key) | — |
| entry_type | VARCHAR(12) | 否 | — | — |
| payment_id | BIGINT | 是 | UNIQUE(payment_id) WHERE ([payment_id] IS NOT NULL); FK payment_attempt.id | — |
| refund_id | BIGINT | 是 | UNIQUE(refund_id) WHERE ([refund_id] IS NOT NULL); FK refund_request.id | — |
| amount_cent | BIGINT | 否 | — | — |
| channel_occurred_at | DATETIME2(3) | 否 | — | — |
| recorded_at | DATETIME2(3) | 否 | — | (sysutcdatetime()) |
| evidence_file_uri | NVARCHAR(300) | 是 | — | — |

表级 CHECK：

```sql
([entry_type]='REFUND' OR [entry_type]='RECEIPT')
```

```sql
([amount_cent]>(0))
```

```sql
([entry_type]='RECEIPT' AND [payment_id] IS NOT NULL AND [refund_id] IS NULL OR [entry_type]='REFUND' AND [payment_id] IS NULL AND [refund_id] IS NOT NULL)
```
