CREATE OR ALTER TRIGGER dbo.trg_refund_audit ON dbo.refund_request AFTER UPDATE AS
BEGIN
 SET NOCOUNT ON;
 IF EXISTS(SELECT 1 FROM inserted i JOIN deleted d ON d.id=i.id
  WHERE i.payment_id<>d.payment_id OR i.client_request_id<>d.client_request_id OR i.out_request_no<>d.out_request_no
   OR i.amount_cent<>d.amount_cent OR i.applicant_id<>d.applicant_id OR i.reason<>d.reason)
  THROW 51047,N'退款请求标识、原支付、金额和申请内容不可变更',1;
 INSERT dbo.audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),s.merchant_id,'REFUND',i.id,'REFUND_STATE','SUCCESS',CONCAT(d.status,' → ',i.status),
 (SELECT d.status,d.amount_cent,d.reason,d.applicant_id,d.reviewed_by,d.review_reason,d.reviewed_at,d.next_query_at,d.succeeded_at FOR JSON PATH,INCLUDE_NULL_VALUES,WITHOUT_ARRAY_WRAPPER),
 (SELECT i.status,i.amount_cent,i.reason,i.applicant_id,i.reviewed_by,i.review_reason,i.reviewed_at,i.next_query_at,i.succeeded_at FOR JSON PATH,INCLUDE_NULL_VALUES,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
 FROM inserted i JOIN deleted d ON d.id=i.id JOIN dbo.payment_attempt p ON p.id=i.payment_id JOIN dbo.business_order o ON o.id=p.order_id JOIN dbo.store s ON s.id=o.store_id WHERE i.status<>d.status;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_case_audit ON dbo.exception_task AFTER INSERT,UPDATE AS
BEGIN
 SET NOCOUNT ON;
 IF EXISTS(SELECT 1 FROM inserted i JOIN deleted d ON d.id=i.id WHERE i.origin_result_id<>d.origin_result_id OR i.case_key<>d.case_key)
  THROW 51044,N'工单起因和归属标识不可变更',1;
 IF EXISTS(SELECT 1 FROM inserted i JOIN deleted d ON d.id=i.id WHERE d.status IN ('PENDING_REVIEW','CLOSED')
  AND (ISNULL(i.analysis_text,N'')<>ISNULL(d.analysis_text,N'') OR ISNULL(i.evidence_file_uri,N'')<>ISNULL(d.evidence_file_uri,N'')
   OR (d.status='CLOSED' AND ISNULL(i.resolution_text,N'')<>ISNULL(d.resolution_text,N''))))
  THROW 51048,N'已提交或已结案的处理分析和证据不可替换',1;
 IF EXISTS(SELECT 1 FROM inserted i JOIN dbo.reconciliation_result origin ON origin.id=i.origin_result_id
  JOIN dbo.reconciliation_batch ob ON ob.id=origin.batch_id JOIN dbo.reconciliation_result latest ON latest.id=i.latest_result_id
  JOIN dbo.reconciliation_batch lb ON lb.id=latest.batch_id WHERE origin.source_event_key<>latest.source_event_key
   OR ob.scope_type<>lb.scope_type OR ISNULL(ob.merchant_id,-1)<>ISNULL(lb.merchant_id,-1))
  THROW 51045,N'工单证据必须属于同一事件和同一商户范围',1;
 INSERT dbo.audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),b.merchant_id,'CASE',i.id,'CASE_WORKFLOW','SUCCESS',CONCAT(COALESCE(d.status,'NEW'),' → ',i.status),
 (SELECT d.status,d.assignee_id,d.origin_result_id,d.latest_result_id,d.title,d.description,d.analysis_text,d.resolution_text,d.resolution_code,d.evidence_file_uri,d.submitted_by,d.reviewed_by,d.due_at,d.closed_at,d.updated_at FOR JSON PATH,INCLUDE_NULL_VALUES,WITHOUT_ARRAY_WRAPPER),
 (SELECT i.status,i.assignee_id,i.origin_result_id,i.latest_result_id,i.title,i.description,i.analysis_text,i.resolution_text,i.resolution_code,i.evidence_file_uri,i.submitted_by,i.reviewed_by,i.due_at,i.closed_at,i.updated_at FOR JSON PATH,INCLUDE_NULL_VALUES,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
 FROM inserted i LEFT JOIN deleted d ON d.id=i.id JOIN dbo.reconciliation_result r ON r.id=i.origin_result_id JOIN dbo.reconciliation_batch b ON b.id=r.batch_id;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_audit_immutable ON dbo.audit_log INSTEAD OF UPDATE,DELETE AS BEGIN THROW 51046,N'审计历史只能追加，不可修改或删除',1; END;
GO
CREATE OR ALTER TRIGGER dbo.trg_entry_immutable ON dbo.transaction_entry INSTEAD OF UPDATE,DELETE AS BEGIN THROW 51040,N'交易事实不可修改或删除',1; END;
GO
CREATE OR ALTER TRIGGER dbo.trg_merchant_review_audit ON dbo.merchant AFTER UPDATE AS
BEGIN
 SET NOCOUNT ON;
 INSERT dbo.audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,before_json,after_json,request_id)
 SELECT 'DB_CHANGE',TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id')),i.id,'MERCHANT',i.id,'MERCHANT_REVIEW','SUCCESS',COALESCE(i.review_reason,N'资料变更'),
 (SELECT d.name,d.category_code,d.contact,d.status,d.proposed_name,d.proposed_category,d.proposed_contact,d.reviewed_by,d.reviewed_at,d.review_reason FOR JSON PATH,INCLUDE_NULL_VALUES,WITHOUT_ARRAY_WRAPPER),
 (SELECT i.name,i.category_code,i.contact,i.status,i.proposed_name,i.proposed_category,i.proposed_contact,i.reviewed_by,i.reviewed_at,i.review_reason FOR JSON PATH,INCLUDE_NULL_VALUES,WITHOUT_ARRAY_WRAPPER),CONVERT(VARCHAR(36),SESSION_CONTEXT(N'request_id'))
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
IF NOT EXISTS(SELECT 1 FROM sys.fulltext_catalogs WHERE name='ReconisCases') CREATE FULLTEXT CATALOG ReconisCases AS DEFAULT;
GO
IF NOT EXISTS(SELECT 1 FROM sys.fulltext_indexes WHERE object_id=OBJECT_ID('dbo.exception_task'))
 CREATE FULLTEXT INDEX ON dbo.exception_task(title LANGUAGE 2052,description LANGUAGE 2052,analysis_text LANGUAGE 2052,resolution_text LANGUAGE 2052)
 KEY INDEX PK_exception_task ON ReconisCases WITH CHANGE_TRACKING AUTO;
GO
IF DATABASE_PRINCIPAL_ID('reconis_runtime') IS NULL CREATE ROLE reconis_runtime;
IF DATABASE_PRINCIPAL_ID('reconis_auditor') IS NULL CREATE ROLE reconis_auditor;
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
