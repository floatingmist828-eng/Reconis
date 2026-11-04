-- 仅用于 ReconisDemo。密码散列由 initialize.ps1 在首次初始化时注入。
IF DB_NAME()<>'ReconisDemo' THROW 51100,N'校园预置数据只能导入 ReconisDemo',1;
GO
DECLARE @hash VARCHAR(100)='$(DemoPasswordHash)';
INSERT app_user(username,password_hash,display_name,platform_role,status) VALUES
 ('admin',@hash,N'平台管理员','ADMIN','ACTIVE'),('finance',@hash,N'林清','PLATFORM_FINANCE','ACTIVE'),
 ('auditor',@hash,N'周宁','AUDITOR','ACTIVE'),('owner',@hash,N'陈禾','MEMBER','ACTIVE'),
 ('cashier',@hash,N'许言','MEMBER','ACTIVE'),('reviewer',@hash,N'沈岚','MEMBER','ACTIVE'),
 ('customer',@hash,N'林知夏','MEMBER','ACTIVE'),('otherowner',@hash,N'王越','MEMBER','ACTIVE'),
 ('pending',@hash,N'叶青','MEMBER','PENDING');
INSERT merchant(merchant_code,name,category_code,contact,status)
 SELECT code,name,category,contact,status FROM #CampusMerchant;
INSERT merchant_member(merchant_id,user_id,role_code) SELECT m.id,u.id,CASE u.username WHEN 'owner' THEN 'OWNER' WHEN 'cashier' THEN 'CASHIER' ELSE 'REVIEWER' END FROM merchant m CROSS JOIN app_user u WHERE m.merchant_code='M001' AND u.username IN('owner','cashier','reviewer');
INSERT merchant_member(merchant_id,user_id,role_code)
 SELECT m.id,u.id,'OWNER' FROM merchant m CROSS JOIN app_user u
 WHERE m.merchant_code<>'M001' AND m.status='ACTIVE' AND u.username='otherowner';
INSERT store(merchant_id,store_code,name,location_text)
 SELECT m.id,s.store_code,s.name,s.location FROM #CampusStore s JOIN merchant m ON m.merchant_code=s.code;
GO
DECLARE @consumer BIGINT=(SELECT id FROM app_user WHERE username='customer');
DECLARE @n INT=1,@store BIGINT,@order BIGINT,@payment BIGINT,@amount BIGINT,@at DATETIME2(3),
 @no VARCHAR(64),@refund BIGINT,@creator BIGINT,@item NVARCHAR(120),@category NVARCHAR(30),
 @code VARCHAR(32),@stamp CHAR(8),@stores INT=(SELECT COUNT(*) FROM store);
DECLARE @midnight DATETIME2(3)=DATEADD(HOUR,-8,CONVERT(DATETIME2(3),CONVERT(DATE,DATEADD(HOUR,8,SYSUTCDATETIME()))));
WHILE @n<=180
BEGIN
 SELECT @store=id FROM(SELECT id,ROW_NUMBER() OVER(ORDER BY id) n FROM store)s WHERE n=(@n-1)%@stores+1;
 SELECT @creator=u.id,@code=m.merchant_code,@category=m.category_code
 FROM store s JOIN merchant m ON m.id=s.merchant_id
 JOIN app_user u ON u.username=CASE WHEN m.merchant_code='M001' THEN 'owner' ELSE 'otherowner' END WHERE s.id=@store;
 SELECT @amount=amount_cent,@item=item_name FROM #CampusOffer WHERE code=@code AND slot=((@n-1)/@stores)%6;
 SET @at=DATEADD(MINUTE,540+(@n*67)%660,DATEADD(DAY,-1-(@n%28),@midnight));
 SET @stamp=CONVERT(CHAR(8),DATEADD(HOUR,8,@at),112);
 INSERT business_order(order_no,store_id,consumer_id,created_by,client_request_id,subject,status,created_at)
  VALUES(CONCAT('ORD',@stamp,FORMAT(@n,'0000')),@store,@consumer,@creator,CONVERT(VARCHAR(36),NEWID()),@item,'WAITING_PAYMENT',@at);
 SET @order=SCOPE_IDENTITY();
 INSERT order_item(order_id,line_no,item_name,category_code,quantity,unit_price_cent)
  VALUES(@order,1,@item,@category,1,@amount);
 SET @no=CONCAT('PAY',@stamp,FORMAT(@n,'0000'));
 INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status,created_at) VALUES(@order,CONVERT(VARCHAR(36),NEWID()),@no,'DEMO','DEMO_APP','DEMO_SELLER','PENDING',@at);
 SET @payment=SCOPE_IDENTITY();
 EXEC usp_confirm_payment @payment_id=@payment,@channel_trade_no=@no,@amount_cent=@amount,@occurred_at=@at;
 IF @n%11=0
 BEGIN
  DECLARE @refundAmount BIGINT=@amount/300*100,@refundAt DATETIME2(3)=DATEADD(DAY,1,@at);
  INSERT refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,status,applicant_id,reviewed_by,reviewed_at,created_at)
   VALUES(@payment,CONVERT(VARCHAR(36),NEWID()),CONCAT('REF',@stamp,FORMAT(@n,'0000')),@refundAmount,
     CASE @code WHEN 'M002' THEN N'取消部分装订' WHEN 'M006' THEN N'减少预订份数' ELSE N'取消部分商品' END,
     'APPROVED',@consumer,@creator,@refundAt,@refundAt);
  SET @refund=SCOPE_IDENTITY();
  EXEC usp_confirm_refund @refund_id=@refund,@amount_cent=@refundAmount,@occurred_at=@refundAt;
 END;
 SET @n+=1;
END;
GO
-- 固定跨日例：昨日收100元，今日退款30元，合计净收70元。
DECLARE @store BIGINT=(SELECT s.id FROM store s JOIN merchant m ON m.id=s.merchant_id WHERE m.merchant_code='M001' AND s.store_code='S01'),@actor BIGINT=(SELECT id FROM app_user WHERE username='owner'),@consumer BIGINT=(SELECT id FROM app_user WHERE username='customer');
DECLARE @refundAt DATETIME2(3)=SYSUTCDATETIME(),@at DATETIME2(3),@order BIGINT,@payment BIGINT,@refund BIGINT;
SET @at=DATEADD(DAY,-1,@refundAt);
DECLARE @stamp CHAR(8)=CONVERT(CHAR(8),DATEADD(HOUR,8,@at),112);
DECLARE @trade VARCHAR(64)=CONCAT('PAY',@stamp,'9001');
INSERT business_order(order_no,store_id,consumer_id,created_by,client_request_id,subject,status,created_at)
 VALUES(CONCAT('ORD',@stamp,'9001'),@store,@consumer,@actor,CONVERT(VARCHAR(36),NEWID()),N'读书会咖啡与茶点预订','WAITING_PAYMENT',@at);
SET @order=SCOPE_IDENTITY();
INSERT order_item(order_id,line_no,item_name,category_code,quantity,unit_price_cent)
 VALUES(@order,1,N'读书会咖啡与茶点组合',N'咖啡简餐',1,10000);
INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status,created_at)
 VALUES(@order,CONVERT(VARCHAR(36),NEWID()),@trade,'DEMO','DEMO_APP','DEMO_SELLER','PENDING',@at);
SET @payment=SCOPE_IDENTITY();
EXEC usp_confirm_payment @payment_id=@payment,@channel_trade_no=@trade,@amount_cent=10000,@occurred_at=@at;
INSERT refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,status,applicant_id,reviewed_by,reviewed_at,created_at)
 VALUES(@payment,CONVERT(VARCHAR(36),NEWID()),CONCAT('REF',CONVERT(CHAR(8),DATEADD(HOUR,8,@refundAt),112),'9001'),
  3000,N'活动人数减少，退回部分茶点款','APPROVED',@consumer,@actor,@refundAt,@refundAt);
SET @refund=SCOPE_IDENTITY();
EXEC usp_confirm_refund @refund_id=@refund,@amount_cent=3000,@occurred_at=@refundAt;
GO
-- 生成包含缺行、重复行与金额差异的渠道账单。
DECLARE @m BIGINT=(SELECT id FROM merchant WHERE merchant_code='M001'),@actor BIGINT=(SELECT id FROM app_user WHERE username='finance'),@batch BIGINT,@start DATETIME2(3)=DATEADD(DAY,-30,SYSUTCDATETIME()),@end DATETIME2(3)=DATEADD(DAY,1,SYSUTCDATETIME());
INSERT reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,completeness,created_by) VALUES(CONCAT('BAT',CONVERT(CHAR(8),DATEADD(HOUR,8,SYSUTCDATETIME()),112),'0001'),'MERCHANT',@m,@start,@end,'DEMO_SEED','DECLARED_COMPLETE',@actor);SET @batch=SCOPE_IDENTITY();
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,out_request_no,amount_cent,channel_occurred_at)
 SELECT @batch,ROW_NUMBER() OVER(ORDER BY id),entry_type,out_trade_no,channel_trade_no,out_request_no,CASE WHEN id%17=0 THEN amount_cent+100 ELSE amount_cent END,channel_occurred_at FROM v_transaction_fact WHERE merchant_id=@m AND id%13<>0;
DECLARE @statementDate CHAR(8)=CONVERT(CHAR(8),DATEADD(HOUR,-16,SYSUTCDATETIME()),112);
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at)
 VALUES(@batch,9001,'RECEIPT',CONCAT('PAY',@statementDate,'9901'),CONCAT('CH',@statementDate,'9901'),2500,DATEADD(DAY,-1,SYSUTCDATETIME()));
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at) SELECT TOP 1 @batch,9002,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at FROM statement_line WHERE import_batch_id=@batch AND event_type='RECEIPT';
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,amount_cent,channel_occurred_at) VALUES(@batch,9003,'REFUND',CONCAT('PAY',@statementDate,'9902'),1200,DATEADD(DAY,-1,SYSUTCDATETIME()));
EXEC usp_run_reconciliation @batch_id=@batch;
GO
-- 留存已核对的差错，供后续检索与复核。
DECLARE @handler BIGINT=(SELECT id FROM app_user WHERE username='cashier'),@reviewer BIGINT=(SELECT id FROM app_user WHERE username='reviewer');
UPDATE t SET title=c.title,description=c.description
 FROM exception_task t JOIN reconciliation_result r ON r.id=t.origin_result_id
 JOIN #CampusCase c ON c.classification=r.classification;
UPDATE exception_task SET assignee_id=@handler,status='PROCESSING',
 analysis_text=N'已按交易号核对原订单、到账时间及渠道账单。差异行已登记在对应批次。',
 resolution_text=N'保留原始流水和渠道账单，差异纳入当期核对记录。收到更正账单后按原交易号复核。',
 submitted_by=@handler,reviewed_by=@reviewer WHERE id%3=0;
UPDATE exception_task SET status='CLOSED',resolution_code='KNOWN_EXCEPTION',closed_at=SYSUTCDATETIME() WHERE id%3=0;
GO
