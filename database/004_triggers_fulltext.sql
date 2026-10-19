CREATE OR ALTER TRIGGER dbo.trg_refund_audit ON dbo.refund_request AFTER UPDATE AS
BEGIN
 SET NOCOUNT ON;
 INSERT dbo.audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),s.merchant_id,'REFUND',i.id,'REFUND_STATE','SUCCESS',CONCAT(d.status,' → ',i.status),
 (SELECT d.status,d.amount_cent FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),(SELECT i.status,i.amount_cent,i.reviewed_by FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
 FROM inserted i JOIN deleted d ON d.id=i.id JOIN dbo.payment_attempt p ON p.id=i.payment_id JOIN dbo.business_order o ON o.id=p.order_id JOIN dbo.store s ON s.id=o.store_id WHERE i.status<>d.status;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_case_audit ON dbo.exception_task AFTER INSERT,UPDATE AS
BEGIN
 SET NOCOUNT ON;
 INSERT dbo.audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),b.merchant_id,'CASE',i.id,'CASE_WORKFLOW','SUCCESS',CONCAT(COALESCE(d.status,'NEW'),' → ',i.status),
 (SELECT d.status,d.assignee_id FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),(SELECT i.status,i.assignee_id,i.reviewed_by,i.latest_result_id FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
 FROM inserted i LEFT JOIN deleted d ON d.id=i.id JOIN dbo.reconciliation_result r ON r.id=i.latest_result_id JOIN dbo.reconciliation_batch b ON b.id=r.batch_id;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_entry_immutable ON dbo.transaction_entry INSTEAD OF UPDATE,DELETE AS BEGIN THROW 51040,N'交易事实不可修改或删除',1; END;
GO
CREATE OR ALTER TRIGGER dbo.trg_merchant_review_audit ON dbo.merchant AFTER UPDATE AS
BEGIN
 SET NOCOUNT ON;
 INSERT dbo.audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),i.id,'MERCHANT',i.id,'MERCHANT_REVIEW','SUCCESS',COALESCE(i.review_reason,N'资料变更'),
 (SELECT d.name,d.status,d.proposed_name FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),(SELECT i.name,i.status,i.proposed_name,i.reviewed_by FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
 FROM inserted i JOIN deleted d ON d.id=i.id;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_user_review_audit ON dbo.app_user AFTER UPDATE AS
BEGIN
 SET NOCOUNT ON;
 INSERT dbo.audit_log(event_category,actor_user_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),'USER',i.id,'USER_REVIEW','SUCCESS',COALESCE(i.review_reason,N'账号状态或权限变更'),
 (SELECT d.status,d.platform_role FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),(SELECT i.status,i.platform_role,i.reviewed_by FOR JSON PATH,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
 FROM inserted i JOIN deleted d ON d.id=i.id WHERE i.status<>d.status OR i.platform_role<>d.platform_role;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_statement_immutable ON dbo.statement_line INSTEAD OF UPDATE,DELETE AS BEGIN THROW 51041,N'原始账单不可修改或删除',1; END;
GO
CREATE OR ALTER TRIGGER dbo.trg_result_immutable ON dbo.reconciliation_result INSTEAD OF UPDATE,DELETE AS BEGIN THROW 51042,N'历史对账结果不可修改或删除',1; END;
GO
CREATE OR ALTER TRIGGER dbo.trg_order_item_freeze ON dbo.order_item AFTER INSERT,UPDATE,DELETE AS
BEGIN
 IF EXISTS(SELECT 1 FROM dbo.payment_attempt p WHERE p.order_id IN(SELECT order_id FROM inserted UNION SELECT order_id FROM deleted)) THROW 51043,N'发起支付后订单明细冻结',1;
END;
GO
IF FULLTEXTSERVICEPROPERTY('IsFullTextInstalled')<>1 THROW 51050,N'需要安装 SQL Server 全文检索组件，不能用 LIKE 替代',1;
GO
CREATE FULLTEXT CATALOG ReconisCases AS DEFAULT;
GO
CREATE FULLTEXT INDEX ON dbo.exception_task(title LANGUAGE 2052,description LANGUAGE 2052,analysis_text LANGUAGE 2052,resolution_text LANGUAGE 2052)
 KEY INDEX PK_exception_task ON ReconisCases WITH CHANGE_TRACKING AUTO;
GO
CREATE ROLE reconis_runtime;
CREATE ROLE reconis_auditor;
GRANT SELECT ON SCHEMA::dbo TO reconis_runtime;
GRANT VIEW DEFINITION ON FULLTEXT CATALOG::ReconisCases TO reconis_runtime;
GRANT INSERT,UPDATE,DELETE ON dbo.app_user TO reconis_runtime;
GRANT INSERT,UPDATE,DELETE ON dbo.merchant TO reconis_runtime;
GRANT INSERT,UPDATE,DELETE ON dbo.merchant_member TO reconis_runtime;
GRANT INSERT,UPDATE,DELETE ON dbo.store TO reconis_runtime;
GRANT INSERT,UPDATE,DELETE ON dbo.business_order TO reconis_runtime;
GRANT INSERT,UPDATE,DELETE ON dbo.order_item TO reconis_runtime;
GRANT INSERT,UPDATE ON dbo.payment_attempt TO reconis_runtime;
GRANT INSERT ON dbo.payment_notification TO reconis_runtime;
GRANT INSERT,UPDATE ON dbo.refund_request TO reconis_runtime;
GRANT INSERT,UPDATE ON dbo.reconciliation_batch TO reconis_runtime;
GRANT INSERT ON dbo.statement_line TO reconis_runtime;
GRANT UPDATE ON dbo.exception_task TO reconis_runtime;
GRANT INSERT ON dbo.audit_log TO reconis_runtime;
GRANT EXECUTE ON dbo.usp_confirm_payment TO reconis_runtime;
GRANT EXECUTE ON dbo.usp_approve_refund TO reconis_runtime;
GRANT EXECUTE ON dbo.usp_confirm_refund TO reconis_runtime;
GRANT EXECUTE ON dbo.usp_run_reconciliation TO reconis_runtime;
GRANT EXECUTE ON dbo.usp_review_exception TO reconis_runtime;
GRANT EXECUTE ON dbo.fn_refundable_amount TO reconis_runtime;
GRANT EXECUTE ON dbo.fn_case_overdue_days TO reconis_runtime;
GRANT SELECT ON dbo.v_audit_summary TO reconis_auditor;
GRANT SELECT ON dbo.v_reconciliation_summary TO reconis_auditor;
GRANT SELECT ON dbo.v_case_search TO reconis_auditor;
GO
