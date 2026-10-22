CREATE UNIQUE INDEX UX_payment_primary ON dbo.payment_attempt(order_id) WHERE is_primary=1;
CREATE UNIQUE INDEX UX_payment_channel ON dbo.payment_attempt(channel_env,app_id,channel_trade_no) WHERE channel_trade_no IS NOT NULL;
CREATE UNIQUE INDEX UX_entry_payment ON dbo.transaction_entry(payment_id) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX UX_entry_refund ON dbo.transaction_entry(refund_id) WHERE refund_id IS NOT NULL;
CREATE UNIQUE INDEX UX_import_file ON dbo.reconciliation_batch(scope_type,merchant_id,file_sha256,ingest_options_sha256) WHERE file_sha256 IS NOT NULL AND statement_source_batch_id IS NULL;
CREATE INDEX IX_order_store_time ON dbo.business_order(store_id,created_at) INCLUDE(status);
CREATE INDEX IX_payment_pending ON dbo.payment_attempt(status,next_query_at);
CREATE INDEX IX_refund_pending ON dbo.refund_request(status,next_query_at) INCLUDE(payment_id,amount_cent);
CREATE INDEX IX_entry_time ON dbo.transaction_entry(channel_occurred_at) INCLUDE(entry_type,amount_cent,payment_id,refund_id);
CREATE INDEX IX_result_batch ON dbo.reconciliation_result(batch_id,classification);
CREATE INDEX IX_result_event_observation ON dbo.reconciliation_result(source_event_key,detected_at) INCLUDE(batch_id,classification);
CREATE INDEX IX_case_assignee ON dbo.exception_task(assignee_id,status,due_at);
CREATE INDEX IX_statement_key ON dbo.statement_line(import_batch_id,out_trade_no,out_request_no);
CREATE INDEX IX_audit_scope ON dbo.audit_log(scope_merchant_id,occurred_at);
CREATE INDEX IX_audit_actor ON dbo.audit_log(actor_user_id,occurred_at);
GO
