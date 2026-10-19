CREATE OR ALTER VIEW dbo.v_order AS
 SELECT o.*,s.merchant_id,s.name store_name,m.name merchant_name,m.merchant_code,
 COALESCE((SELECT SUM(CONVERT(BIGINT,i.quantity)*i.unit_price_cent) FROM dbo.order_item i WHERE i.order_id=o.id),0) amount_cent
 FROM dbo.business_order o JOIN dbo.store s ON s.id=o.store_id JOIN dbo.merchant m ON m.id=s.merchant_id;
GO
CREATE OR ALTER VIEW dbo.v_transaction_fact AS
 SELECT e.*,p.id source_payment_id,p.order_id,p.out_trade_no,p.channel_trade_no,p.is_primary,p.channel_env,
 r.out_request_no,o.order_no,o.subject,o.store_id,o.consumer_id,s.name store_name,s.merchant_id,m.name merchant_name,m.merchant_code,
 CONVERT(DATE, e.channel_occurred_at AT TIME ZONE 'UTC' AT TIME ZONE 'China Standard Time') business_date
 FROM dbo.transaction_entry e LEFT JOIN dbo.refund_request r ON r.id=e.refund_id
 JOIN dbo.payment_attempt p ON p.id=COALESCE(e.payment_id,r.payment_id)
 JOIN dbo.business_order o ON o.id=p.order_id JOIN dbo.store s ON s.id=o.store_id JOIN dbo.merchant m ON m.id=s.merchant_id;
GO
CREATE OR ALTER VIEW dbo.v_statement_normalized AS
 SELECT l.*,CASE WHEN event_type='RECEIPT' AND NULLIF(out_trade_no,'') IS NOT NULL THEN CONCAT('P:',out_trade_no)
 WHEN event_type='REFUND' AND NULLIF(out_request_no,'') IS NOT NULL THEN CONCAT('R:',out_request_no) ELSE NULL END event_key
 FROM dbo.statement_line l;
GO
CREATE OR ALTER VIEW dbo.v_case AS
 SELECT t.*,origin.classification,origin.reason_code,r.classification latest_classification,r.batch_id,r.source_event_key,r.entry_id,r.statement_line_id,b.merchant_id,m.name merchant_name,
 a.display_name assignee_name,u.display_name reviewer_name,b.batch_no
 FROM dbo.exception_task t JOIN dbo.reconciliation_result r ON r.id=t.latest_result_id JOIN dbo.reconciliation_result origin ON origin.id=t.origin_result_id
 JOIN dbo.reconciliation_batch b ON b.id=r.batch_id LEFT JOIN dbo.merchant m ON m.id=b.merchant_id
 LEFT JOIN dbo.app_user a ON a.id=t.assignee_id LEFT JOIN dbo.app_user u ON u.id=t.reviewed_by;
GO
CREATE OR ALTER VIEW dbo.v_public_merchant AS SELECT id,merchant_code,name,category_code FROM dbo.merchant WHERE status='ACTIVE';
GO
CREATE OR ALTER VIEW dbo.v_consumer_order AS SELECT * FROM dbo.v_order WHERE consumer_id=TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'user_id'));
GO
CREATE OR ALTER VIEW dbo.v_merchant_transaction AS SELECT * FROM dbo.v_transaction_fact WHERE merchant_id=TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'merchant_id'));
GO
CREATE OR ALTER VIEW dbo.v_reconciliation_summary AS
 SELECT b.id,b.batch_no,b.merchant_id,b.status,r.classification,COUNT_BIG(r.id) result_count
 FROM dbo.reconciliation_batch b LEFT JOIN dbo.reconciliation_result r ON r.batch_id=b.id GROUP BY b.id,b.batch_no,b.merchant_id,b.status,r.classification;
GO
CREATE OR ALTER VIEW dbo.v_case_search AS SELECT * FROM dbo.v_case WHERE merchant_id=TRY_CONVERT(BIGINT,SESSION_CONTEXT(N'merchant_id')) OR CONVERT(VARCHAR(24),SESSION_CONTEXT(N'role')) IN ('PLATFORM_FINANCE','AUDITOR');
GO
CREATE OR ALTER VIEW dbo.v_audit_summary AS SELECT id,event_category,actor_user_id,scope_merchant_id,action,result,summary,occurred_at,request_id FROM dbo.audit_log;
GO
CREATE OR ALTER FUNCTION dbo.fn_refundable_amount(@payment_id BIGINT) RETURNS BIGINT AS
BEGIN
 RETURN (SELECT COALESCE((SELECT amount_cent FROM dbo.transaction_entry WHERE payment_id=@payment_id),0)
 -COALESCE((SELECT SUM(amount_cent) FROM dbo.refund_request WHERE payment_id=@payment_id AND status IN ('APPROVED','PROCESSING','UNKNOWN','SUCCEEDED')),0));
END;
GO
CREATE OR ALTER FUNCTION dbo.fn_case_overdue_days(@due_at DATETIME2(3),@closed_at DATETIME2(3),@as_of DATETIME2(3)) RETURNS INT AS
BEGIN
 DECLARE @n INT=DATEDIFF(DAY,@due_at,COALESCE(@closed_at,@as_of)); RETURN CASE WHEN @n>0 THEN @n ELSE 0 END;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_confirm_payment
 @payment_id BIGINT,@channel_trade_no VARCHAR(100),@amount_cent BIGINT,@occurred_at DATETIME2(3),@evidence NVARCHAR(300)=NULL
AS
BEGIN
 SET NOCOUNT ON; SET XACT_ABORT ON;
 BEGIN TRY
  BEGIN TRANSACTION;
  DECLARE @order BIGINT=(SELECT order_id FROM dbo.payment_attempt WHERE id=@payment_id),@status VARCHAR(24),@primary BIT=0,@total BIGINT;
  SELECT @status=status FROM dbo.business_order WITH(UPDLOCK,HOLDLOCK) WHERE id=@order;
  IF @status IS NULL THROW 51001,N'支付记录不存在',1;
  SELECT @total=SUM(CONVERT(BIGINT,quantity)*unit_price_cent) FROM dbo.order_item WHERE order_id=@order;
  IF @total<>@amount_cent OR @occurred_at IS NULL THROW 51002,N'支付金额或发生时间不一致',1;
  IF EXISTS(SELECT 1 FROM dbo.payment_attempt WHERE id=@payment_id AND status='SUCCEEDED')
  BEGIN
   IF NOT EXISTS(SELECT 1 FROM dbo.payment_attempt WHERE id=@payment_id AND channel_trade_no=@channel_trade_no) THROW 51003,N'渠道交易号冲突',1;
   COMMIT; SELECT 'ALREADY_CONFIRMED' outcome; RETURN;
  END;
  IF @status='WAITING_PAYMENT' AND NOT EXISTS(SELECT 1 FROM dbo.payment_attempt WHERE order_id=@order AND is_primary=1) SET @primary=1;
  UPDATE dbo.payment_attempt SET status='SUCCEEDED',channel_trade_no=@channel_trade_no,is_primary=@primary,confirmed_at=@occurred_at,next_query_at=NULL,updated_at=SYSUTCDATETIME() WHERE id=@payment_id;
  INSERT dbo.transaction_entry(entry_no,event_key,entry_type,payment_id,amount_cent,channel_occurred_at,evidence_file_uri)
   SELECT CONCAT('E',REPLACE(NEWID(),'-','')),CONCAT('P:',out_trade_no),'RECEIPT',id,@amount_cent,@occurred_at,@evidence FROM dbo.payment_attempt WHERE id=@payment_id;
  UPDATE dbo.business_order SET status=CASE WHEN @primary=1 THEN 'PAID' ELSE 'PAYMENT_EXCEPTION' END,updated_at=SYSUTCDATETIME() WHERE id=@order;
  COMMIT; SELECT CASE WHEN @primary=1 THEN 'CONFIRMED' ELSE 'EXTRA_SUCCESS' END outcome;
 END TRY BEGIN CATCH IF @@TRANCOUNT>0 ROLLBACK; THROW; END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_approve_refund @refund_id BIGINT,@reviewer BIGINT,@approve BIT,@reason NVARCHAR(1000)
AS
BEGIN
 SET NOCOUNT ON; SET XACT_ABORT ON;
 BEGIN TRY
  BEGIN TRANSACTION;
  DECLARE @payment BIGINT=(SELECT payment_id FROM dbo.refund_request WHERE id=@refund_id),@amount BIGINT,@applicant BIGINT,@status VARCHAR(24),@dummy BIGINT;
  SELECT @dummy=id FROM dbo.payment_attempt WITH(UPDLOCK,HOLDLOCK) WHERE id=@payment;
  SELECT @amount=amount_cent,@applicant=applicant_id,@status=status FROM dbo.refund_request WITH(UPDLOCK,HOLDLOCK) WHERE id=@refund_id;
  IF @status IS NULL OR @status<>'PENDING_REVIEW' THROW 51010,N'退款不在待审批状态',1;
  IF @applicant=@reviewer THROW 51011,N'申请人不能审批自己的退款',1;
  IF @approve=1 AND @amount>dbo.fn_refundable_amount(@payment) THROW 51012,N'可退额度不足，已有成功或预留退款',1;
  UPDATE dbo.refund_request SET status=CASE WHEN @approve=1 THEN 'APPROVED' ELSE 'REJECTED' END,reviewed_by=@reviewer,reviewed_at=SYSUTCDATETIME(),review_reason=@reason,updated_at=SYSUTCDATETIME() WHERE id=@refund_id;
  COMMIT;
 END TRY BEGIN CATCH IF @@TRANCOUNT>0 ROLLBACK; THROW; END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_confirm_refund @refund_id BIGINT,@amount_cent BIGINT,@occurred_at DATETIME2(3),@evidence NVARCHAR(300)=NULL
AS
BEGIN
 SET NOCOUNT ON; SET XACT_ABORT ON;
 BEGIN TRY
  BEGIN TRANSACTION;
  DECLARE @payment BIGINT=(SELECT payment_id FROM dbo.refund_request WHERE id=@refund_id),@dummy BIGINT,@amount BIGINT,@state VARCHAR(24);
  SELECT @dummy=id FROM dbo.payment_attempt WITH(UPDLOCK,HOLDLOCK) WHERE id=@payment;
  SELECT @amount=amount_cent,@state=status FROM dbo.refund_request WITH(UPDLOCK,HOLDLOCK) WHERE id=@refund_id;
  IF @state='SUCCEEDED' BEGIN
   IF @amount<>@amount_cent THROW 51017,N'退款确认金额冲突',1;
   COMMIT; SELECT 'ALREADY_CONFIRMED' outcome; RETURN;
  END;
  IF @state NOT IN ('APPROVED','PROCESSING','UNKNOWN') OR @state IS NULL THROW 51015,N'退款未批准或状态不合法',1;
  IF @amount<>@amount_cent OR @occurred_at IS NULL THROW 51016,N'退款确认金额或时间不一致',1;
  UPDATE dbo.refund_request SET status='SUCCEEDED',succeeded_at=@occurred_at,next_query_at=NULL,updated_at=SYSUTCDATETIME() WHERE id=@refund_id;
  INSERT dbo.transaction_entry(entry_no,event_key,entry_type,refund_id,amount_cent,channel_occurred_at,evidence_file_uri)
   SELECT CONCAT('E',REPLACE(NEWID(),'-','')),CONCAT('R:',out_request_no),'REFUND',id,@amount_cent,@occurred_at,@evidence FROM dbo.refund_request WHERE id=@refund_id;
  COMMIT; SELECT 'CONFIRMED' outcome;
 END TRY BEGIN CATCH IF @@TRANCOUNT>0 ROLLBACK; THROW; END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_run_reconciliation @batch_id BIGINT
AS
BEGIN
 SET NOCOUNT ON; SET XACT_ABORT ON; SET TRANSACTION ISOLATION LEVEL SNAPSHOT;
 BEGIN TRY
  BEGIN TRANSACTION;
  DECLARE @source BIGINT,@merchant BIGINT,@start DATETIME2(3),@end DATETIME2(3),@complete VARCHAR(24),@state VARCHAR(16),@scope VARCHAR(24);
  SELECT @source=COALESCE(statement_source_batch_id,id),@merchant=merchant_id,@start=period_start,@end=period_end,@complete=completeness,@state=status,@scope=scope_type FROM dbo.reconciliation_batch WHERE id=@batch_id;
  IF @state IS NULL OR @state NOT IN ('READY','FAILED') THROW 51020,N'批次已经运行，请创建重跑批次',1;
  IF EXISTS(SELECT 1 FROM dbo.reconciliation_result WHERE batch_id=@batch_id) THROW 51021,N'已有历史结果，不可覆盖',1;
  IF NOT EXISTS(SELECT 1 FROM dbo.reconciliation_batch WHERE id=@source AND scope_type=@scope AND ISNULL(merchant_id,-1)=ISNULL(@merchant,-1)) THROW 51022,N'输入来源范围不一致',1;
  UPDATE dbo.reconciliation_batch SET status='RUNNING',started_at=SYSUTCDATETIME(),observed_at=SYSUTCDATETIME() WHERE id=@batch_id;
  SELECT *,COUNT(*) OVER(PARTITION BY event_key) key_count INTO #channel FROM dbo.v_statement_normalized WHERE import_batch_id=@source;
  SELECT * INTO #local FROM dbo.v_transaction_fact WHERE merchant_id=@merchant AND channel_occurred_at>=@start AND channel_occurred_at<@end
   AND ((SELECT source_type FROM dbo.reconciliation_batch WHERE id=@batch_id)<>'TRADE_QUERY' OR event_key IN(SELECT event_key FROM #channel WHERE event_key IS NOT NULL));
  INSERT dbo.reconciliation_result(batch_id,result_key,source_event_key,statement_line_id,classification,reason_code)
   SELECT @batch_id,CONCAT('Q:',id),COALESCE(event_key,CONCAT('UNRESOLVED:',id)),id,
    CASE WHEN event_key IS NULL THEN 'KEY_UNRESOLVED' ELSE 'BILL_DUPLICATE' END,
    CASE WHEN event_key IS NULL THEN 'MISSING_EVENT_KEY' ELSE 'DUPLICATE_INPUT' END FROM #channel WHERE event_key IS NULL OR key_count>1;
  INSERT dbo.reconciliation_result(batch_id,result_key,source_event_key,entry_id,statement_line_id,classification,reason_code)
   SELECT @batch_id,COALESCE(l.event_key,c.event_key),COALESCE(l.event_key,c.event_key),l.id,c.id,
    CASE WHEN l.id IS NULL THEN 'CHANNEL_ONLY' WHEN l.entry_type='RECEIPT' AND l.is_primary=0 THEN 'EXTRA_SUCCESS'
     WHEN c.id IS NULL AND @complete='UNKNOWN' THEN 'PENDING_CONFIRMATION' WHEN c.id IS NULL THEN 'LOCAL_ONLY'
     WHEN NULLIF(c.channel_trade_no,'') IS NOT NULL AND l.channel_trade_no<>c.channel_trade_no THEN 'KEY_UNRESOLVED'
     WHEN l.amount_cent<>c.amount_cent THEN 'AMOUNT_MISMATCH' ELSE 'MATCHED' END,
    CASE WHEN l.id IS NULL THEN 'PAYMENT_CONFIRMATION' WHEN l.entry_type='RECEIPT' AND l.is_primary=0 THEN 'DUPLICATE_PAYMENT'
     WHEN c.id IS NULL AND @complete='UNKNOWN' THEN 'STATEMENT_INCOMPLETE' WHEN c.id IS NULL THEN 'MISSING_CHANNEL_EVENT'
     WHEN NULLIF(c.channel_trade_no,'') IS NOT NULL AND l.channel_trade_no<>c.channel_trade_no THEN 'CHANNEL_ID_CONFLICT'
     WHEN l.amount_cent<>c.amount_cent THEN 'AMOUNT_DIFFERENCE' ELSE 'IDENTICAL' END
   FROM (SELECT * FROM #local WHERE event_key NOT IN(SELECT event_key FROM #channel WHERE key_count>1 AND event_key IS NOT NULL)) l
   FULL OUTER JOIN (SELECT * FROM #channel WHERE event_key IS NOT NULL AND key_count=1) c ON l.event_key=c.event_key;
  ;WITH issues AS(SELECT *,ROW_NUMBER() OVER(PARTITION BY source_event_key,classification ORDER BY id) issue_number FROM dbo.reconciliation_result WHERE batch_id=@batch_id AND classification<>'MATCHED')
  UPDATE t SET latest_result_id=r.id,status=CASE WHEN t.status='CLOSED' THEN 'OPEN' ELSE t.status END,
   closed_at=CASE WHEN t.status='CLOSED' THEN NULL ELSE t.closed_at END,updated_at=SYSUTCDATETIME()
   FROM dbo.exception_task t JOIN issues r ON t.case_key=CONCAT(ISNULL(CONVERT(VARCHAR,@merchant),'UNASSIGNED'),':',r.source_event_key,':',r.classification) WHERE r.issue_number=1;
  ;WITH issues AS(SELECT *,ROW_NUMBER() OVER(PARTITION BY source_event_key,classification ORDER BY id) issue_number FROM dbo.reconciliation_result WHERE batch_id=@batch_id AND classification<>'MATCHED')
  INSERT dbo.exception_task(case_no,case_key,origin_result_id,latest_result_id,title,description,due_at)
   SELECT CONCAT('RC',REPLACE(NEWID(),'-','')),CONCAT(ISNULL(CONVERT(VARCHAR,@merchant),'UNASSIGNED'),':',r.source_event_key,':',r.classification),r.id,r.id,
    CASE r.classification WHEN 'CHANNEL_ONLY' THEN N'渠道有记录，本地待确认' WHEN 'LOCAL_ONLY' THEN N'本地流水缺少对应账单' WHEN 'AMOUNT_MISMATCH' THEN N'收退款金额不一致' WHEN 'BILL_DUPLICATE' THEN N'账单记录重复' WHEN 'KEY_UNRESOLVED' THEN N'交易标识待核实' WHEN 'PENDING_CONFIRMATION' THEN N'账单待补齐' ELSE N'订单出现额外收款' END,
    CONCAT(N'交易：',r.source_event_key,N'。请核对两侧明细并记录处理结果。'),DATEADD(DAY,2,SYSUTCDATETIME())
   FROM issues r WHERE r.issue_number=1 AND NOT EXISTS(SELECT 1 FROM dbo.exception_task t WHERE t.case_key=CONCAT(ISNULL(CONVERT(VARCHAR,@merchant),'UNASSIGNED'),':',r.source_event_key,':',r.classification));
  UPDATE dbo.reconciliation_batch SET status='COMPLETED',finished_at=SYSUTCDATETIME() WHERE id=@batch_id;
  COMMIT; SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
 END TRY BEGIN CATCH IF @@TRANCOUNT>0 ROLLBACK; SET TRANSACTION ISOLATION LEVEL READ COMMITTED; THROW; END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_review_exception @case_id BIGINT,@reviewer BIGINT,@approve BIT,@resolution_code VARCHAR(40),@reason NVARCHAR(MAX),@evidence_result BIGINT=NULL
AS
BEGIN
 SET NOCOUNT ON; SET XACT_ABORT ON;
 BEGIN TRY
  BEGIN TRANSACTION;
  DECLARE @submitter BIGINT,@event VARCHAR(150),@merchant BIGINT;
  SELECT @submitter=t.submitted_by,@event=r.source_event_key,@merchant=b.merchant_id FROM dbo.exception_task t WITH(UPDLOCK,HOLDLOCK)
   JOIN dbo.reconciliation_result r ON r.id=t.latest_result_id JOIN dbo.reconciliation_batch b ON b.id=r.batch_id WHERE t.id=@case_id AND t.status='PENDING_REVIEW';
  IF @submitter IS NULL THROW 51030,N'工单不在待复核状态',1;
  IF @reviewer=@submitter THROW 51031,N'处理人不能复核自己的工单',1;
  IF NULLIF(LTRIM(RTRIM(@reason)),N'') IS NULL THROW 51032,N'请输入复核意见',1;
  IF @approve=1 AND @resolution_code NOT IN ('RECONCILED','INVALID_INPUT','FALSE_POSITIVE','KNOWN_EXCEPTION') THROW 51033,N'结案类型不合法',1;
  IF @evidence_result IS NOT NULL AND @resolution_code<>'RECONCILED' THROW 51035,N'仅重新匹配结论可替换结果证据',1;
  IF @approve=1 AND @resolution_code='RECONCILED' AND NOT EXISTS(SELECT 1 FROM dbo.reconciliation_result r JOIN dbo.reconciliation_batch b ON b.id=r.batch_id WHERE r.id=@evidence_result AND r.source_event_key=@event AND r.classification='MATCHED' AND ISNULL(b.merchant_id,-1)=ISNULL(@merchant,-1) AND b.status='COMPLETED' AND b.id>(SELECT o.batch_id FROM dbo.reconciliation_result o JOIN dbo.exception_task t ON t.origin_result_id=o.id WHERE t.id=@case_id)) THROW 51034,N'需要同一事件的新批次匹配证据',1;
  UPDATE dbo.exception_task SET status=CASE WHEN @approve=1 THEN 'CLOSED' ELSE 'PROCESSING' END,reviewed_by=@reviewer,
   resolution_code=@resolution_code,resolution_text=CONCAT(COALESCE(resolution_text,N''),CHAR(10),N'复核：',@reason),
   latest_result_id=COALESCE(@evidence_result,latest_result_id),closed_at=CASE WHEN @approve=1 THEN SYSUTCDATETIME() ELSE NULL END,updated_at=SYSUTCDATETIME() WHERE id=@case_id;
  COMMIT;
 END TRY BEGIN CATCH IF @@TRANCOUNT>0 ROLLBACK; THROW; END CATCH;
END;
GO
