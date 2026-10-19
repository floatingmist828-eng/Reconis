SET XACT_ABORT ON;
GO
CREATE TABLE dbo.app_user (
 id BIGINT IDENTITY PRIMARY KEY, username NVARCHAR(64) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, display_name NVARCHAR(80) NOT NULL,
 platform_role VARCHAR(24) NOT NULL DEFAULT 'MEMBER' CHECK(platform_role IN ('MEMBER','PLATFORM_FINANCE','ADMIN','AUDITOR')),
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACTIVE','REJECTED','DISABLED')),
 reviewed_by BIGINT NULL REFERENCES dbo.app_user(id), reviewed_at DATETIME2(3), review_reason NVARCHAR(500),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), version ROWVERSION
);
CREATE TABLE dbo.merchant (
 id BIGINT IDENTITY PRIMARY KEY, merchant_code VARCHAR(32) NOT NULL UNIQUE,
 name NVARCHAR(100) NOT NULL, category_code NVARCHAR(30) NOT NULL, contact NVARCHAR(150),
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACTIVE','REJECTED','DISABLED')),
 proposed_name NVARCHAR(100), proposed_category NVARCHAR(30), proposed_contact NVARCHAR(150),
 reviewed_by BIGINT REFERENCES dbo.app_user(id), reviewed_at DATETIME2(3), review_reason NVARCHAR(500),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), version ROWVERSION
);
CREATE TABLE dbo.merchant_member (
 id BIGINT IDENTITY PRIMARY KEY, merchant_id BIGINT NOT NULL REFERENCES dbo.merchant(id), user_id BIGINT NOT NULL REFERENCES dbo.app_user(id),
 role_code VARCHAR(16) NOT NULL CHECK(role_code IN ('OWNER','CASHIER','FINANCE','REVIEWER')),
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), UNIQUE(merchant_id,user_id)
);
CREATE TABLE dbo.store (
 id BIGINT IDENTITY PRIMARY KEY, merchant_id BIGINT NOT NULL REFERENCES dbo.merchant(id), store_code VARCHAR(32) NOT NULL,
 name NVARCHAR(100) NOT NULL, location_text NVARCHAR(200), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), version ROWVERSION,
 UNIQUE(merchant_id,store_code)
);
CREATE TABLE dbo.business_order (
 id BIGINT IDENTITY PRIMARY KEY, order_no VARCHAR(60) NOT NULL UNIQUE, store_id BIGINT NOT NULL REFERENCES dbo.store(id),
 consumer_id BIGINT REFERENCES dbo.app_user(id), created_by BIGINT NOT NULL REFERENCES dbo.app_user(id), client_request_id VARCHAR(36) NOT NULL UNIQUE,
 subject NVARCHAR(160) NOT NULL, remark NVARCHAR(1000),
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','WAITING_PAYMENT','PAID','CANCELED','PAYMENT_EXCEPTION')),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), version ROWVERSION
);
CREATE TABLE dbo.order_item (
 id BIGINT IDENTITY PRIMARY KEY, order_id BIGINT NOT NULL REFERENCES dbo.business_order(id), line_no INT NOT NULL,
 item_name NVARCHAR(120) NOT NULL, category_code NVARCHAR(30) NOT NULL DEFAULT N'其他',
 quantity INT NOT NULL CHECK(quantity>0), unit_price_cent BIGINT NOT NULL CHECK(unit_price_cent>=0), UNIQUE(order_id,line_no)
);
CREATE TABLE dbo.payment_attempt (
 id BIGINT IDENTITY PRIMARY KEY, order_id BIGINT NOT NULL REFERENCES dbo.business_order(id), client_request_id VARCHAR(36) NOT NULL UNIQUE,
 out_trade_no VARCHAR(64) NOT NULL UNIQUE, channel_code VARCHAR(16) NOT NULL DEFAULT 'ALIPAY',
 channel_env VARCHAR(24) NOT NULL CHECK(channel_env IN ('DEMO','ALIPAY_SANDBOX')), app_id VARCHAR(64) NOT NULL, seller_id VARCHAR(64) NOT NULL,
 channel_trade_no VARCHAR(100), status VARCHAR(16) NOT NULL DEFAULT 'CREATED' CHECK(status IN ('CREATED','PENDING','SUCCEEDED','CLOSED','FAILED','UNKNOWN')),
 is_primary BIT NOT NULL DEFAULT 0, next_query_at DATETIME2(3), query_count INT NOT NULL DEFAULT 0, confirmed_at DATETIME2(3),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE TABLE dbo.payment_notification (
 id BIGINT IDENTITY PRIMARY KEY, payment_id BIGINT REFERENCES dbo.payment_attempt(id), notify_id VARCHAR(100),
 payload_file_uri NVARCHAR(300), payload_hash CHAR(64) NOT NULL, signature_valid BIT NOT NULL,
 handling_result NVARCHAR(300) NOT NULL, received_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), request_id VARCHAR(36) NOT NULL
);
CREATE TABLE dbo.refund_request (
 id BIGINT IDENTITY PRIMARY KEY, payment_id BIGINT NOT NULL REFERENCES dbo.payment_attempt(id), client_request_id VARCHAR(36) NOT NULL UNIQUE,
 out_request_no VARCHAR(64) NOT NULL UNIQUE, amount_cent BIGINT NOT NULL CHECK(amount_cent>0), reason NVARCHAR(1000) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW' CHECK(status IN ('PENDING_REVIEW','APPROVED','PROCESSING','SUCCEEDED','REJECTED','FAILED','UNKNOWN')),
 applicant_id BIGINT NOT NULL REFERENCES dbo.app_user(id), reviewed_by BIGINT REFERENCES dbo.app_user(id), reviewed_at DATETIME2(3), review_reason NVARCHAR(1000),
 query_count INT NOT NULL DEFAULT 0, next_query_at DATETIME2(3), succeeded_at DATETIME2(3),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), version ROWVERSION
);
CREATE TABLE dbo.transaction_entry (
 id BIGINT IDENTITY PRIMARY KEY, entry_no VARCHAR(64) NOT NULL UNIQUE, event_key VARCHAR(150) NOT NULL UNIQUE,
 entry_type VARCHAR(12) NOT NULL CHECK(entry_type IN ('RECEIPT','REFUND')),
 payment_id BIGINT REFERENCES dbo.payment_attempt(id), refund_id BIGINT REFERENCES dbo.refund_request(id),
 amount_cent BIGINT NOT NULL CHECK(amount_cent>0), channel_occurred_at DATETIME2(3) NOT NULL,
 recorded_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), evidence_file_uri NVARCHAR(300),
 CHECK((entry_type='RECEIPT' AND payment_id IS NOT NULL AND refund_id IS NULL) OR (entry_type='REFUND' AND payment_id IS NULL AND refund_id IS NOT NULL))
);
CREATE TABLE dbo.reconciliation_batch (
 id BIGINT IDENTITY PRIMARY KEY, batch_no VARCHAR(64) NOT NULL UNIQUE,
 scope_type VARCHAR(24) NOT NULL CHECK(scope_type IN ('MERCHANT','PLATFORM_UNASSIGNED')),
 merchant_id BIGINT REFERENCES dbo.merchant(id), period_start DATETIME2(3) NOT NULL, period_end DATETIME2(3) NOT NULL,
 source_type VARCHAR(24) NOT NULL CHECK(source_type IN ('XLSX','XML','TRADE_QUERY','DEMO_SEED')),
 statement_source_batch_id BIGINT REFERENCES dbo.reconciliation_batch(id), baseline_batch_id BIGINT REFERENCES dbo.reconciliation_batch(id),
 source_file_uri NVARCHAR(300), file_sha256 CHAR(64), completeness VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' CHECK(completeness IN ('UNKNOWN','DECLARED_COMPLETE','PROVIDER_COMPLETE')),
 status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('IMPORTING','READY','RUNNING','COMPLETED','FAILED')),
 observed_at DATETIME2(3), started_at DATETIME2(3), finished_at DATETIME2(3), created_by BIGINT NULL REFERENCES dbo.app_user(id),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
 CHECK(period_end>period_start), CHECK((scope_type='MERCHANT' AND merchant_id IS NOT NULL) OR (scope_type='PLATFORM_UNASSIGNED' AND merchant_id IS NULL))
);
CREATE TABLE dbo.statement_line (
 id BIGINT IDENTITY PRIMARY KEY, import_batch_id BIGINT NOT NULL REFERENCES dbo.reconciliation_batch(id), line_no INT NOT NULL,
 event_type VARCHAR(12) NOT NULL CHECK(event_type IN ('RECEIPT','REFUND')), out_trade_no VARCHAR(64), channel_trade_no VARCHAR(100), out_request_no VARCHAR(64),
 amount_cent BIGINT NOT NULL CHECK(amount_cent>0), channel_occurred_at DATETIME2(3) NOT NULL, raw_line_file_uri NVARCHAR(300), UNIQUE(import_batch_id,line_no)
);
CREATE TABLE dbo.reconciliation_result (
 id BIGINT IDENTITY PRIMARY KEY, batch_id BIGINT NOT NULL REFERENCES dbo.reconciliation_batch(id), result_key VARCHAR(180) NOT NULL,
 source_event_key VARCHAR(150) NOT NULL, entry_id BIGINT REFERENCES dbo.transaction_entry(id), statement_line_id BIGINT REFERENCES dbo.statement_line(id),
 classification VARCHAR(32) NOT NULL CHECK(classification IN ('MATCHED','CHANNEL_ONLY','LOCAL_ONLY','AMOUNT_MISMATCH','BILL_DUPLICATE','KEY_UNRESOLVED','PENDING_CONFIRMATION','EXTRA_SUCCESS')),
 reason_code VARCHAR(60) NOT NULL, detected_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), UNIQUE(batch_id,result_key)
);
CREATE TABLE dbo.exception_task (
 id BIGINT IDENTITY CONSTRAINT PK_exception_task PRIMARY KEY, case_no VARCHAR(64) NOT NULL UNIQUE, case_key VARCHAR(240) NOT NULL UNIQUE,
 origin_result_id BIGINT NOT NULL UNIQUE REFERENCES dbo.reconciliation_result(id), latest_result_id BIGINT NOT NULL REFERENCES dbo.reconciliation_result(id),
 assignee_id BIGINT REFERENCES dbo.app_user(id), status VARCHAR(24) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ASSIGNED','PROCESSING','PENDING_REVIEW','CLOSED')),
 title NVARCHAR(200) NOT NULL, description NVARCHAR(MAX) NOT NULL, analysis_text NVARCHAR(MAX), resolution_text NVARCHAR(MAX), resolution_code VARCHAR(40), evidence_file_uri NVARCHAR(300),
 submitted_by BIGINT REFERENCES dbo.app_user(id), reviewed_by BIGINT REFERENCES dbo.app_user(id), due_at DATETIME2(3), closed_at DATETIME2(3),
 created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), version ROWVERSION
);
CREATE TABLE dbo.audit_log (
 id BIGINT IDENTITY PRIMARY KEY, event_category VARCHAR(24) NOT NULL,
 actor_user_id BIGINT REFERENCES dbo.app_user(id), scope_merchant_id BIGINT REFERENCES dbo.merchant(id), object_type VARCHAR(60), object_id BIGINT,
 action NVARCHAR(100) NOT NULL, result VARCHAR(24) NOT NULL, summary NVARCHAR(1000), before_json NVARCHAR(MAX), after_json NVARCHAR(MAX),
 occurred_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), request_id VARCHAR(36)
);
GO
