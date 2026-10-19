-- 仅用于 ReconisDemo。密码散列由 initialize.ps1 在首次初始化时注入。
IF DB_NAME()<>'ReconisDemo' THROW 51100,N'教学数据只能导入 ReconisDemo',1;
GO
DECLARE @hash VARCHAR(100)='$(DemoPasswordHash)';
INSERT app_user(username,password_hash,display_name,platform_role,status) VALUES
 ('admin',@hash,N'平台管理员','ADMIN','ACTIVE'),('finance',@hash,N'林清','PLATFORM_FINANCE','ACTIVE'),
 ('auditor',@hash,N'周宁','AUDITOR','ACTIVE'),('owner',@hash,N'陈禾','MEMBER','ACTIVE'),
 ('cashier',@hash,N'许言','MEMBER','ACTIVE'),('reviewer',@hash,N'沈岚','MEMBER','ACTIVE'),
 ('customer',@hash,N'林同学','MEMBER','ACTIVE'),('otherowner',@hash,N'王越','MEMBER','ACTIVE'),
 ('pending',@hash,N'待审核同学','MEMBER','PENDING');
INSERT merchant(merchant_code,name,category_code,contact,status) VALUES
 ('M001',N'栖木咖啡',N'咖啡餐饮',N'校园教学商户 · 图书馆一层','ACTIVE'),
 ('M002',N'青禾文印',N'文印服务',N'校园教学商户 · 综合服务楼','ACTIVE'),
 ('M003',N'四时便利',N'校园零售',N'校园教学商户 · 生活区','ACTIVE'),
 ('M004',N'拾光轻食',N'咖啡餐饮',N'待审核教学资料','PENDING');
INSERT merchant_member(merchant_id,user_id,role_code) SELECT m.id,u.id,CASE u.username WHEN 'owner' THEN 'OWNER' WHEN 'cashier' THEN 'CASHIER' ELSE 'REVIEWER' END FROM merchant m CROSS JOIN app_user u WHERE m.merchant_code='M001' AND u.username IN('owner','cashier','reviewer');
INSERT merchant_member(merchant_id,user_id,role_code) SELECT m.id,u.id,'OWNER' FROM merchant m CROSS JOIN app_user u WHERE m.merchant_code IN('M002','M003') AND u.username='otherowner';
INSERT store(merchant_id,store_code,name,location_text) SELECT id,'S01',CONCAT(name,N' · 主店'),N'校园教学场景' FROM merchant WHERE status='ACTIVE';
INSERT store(merchant_id,store_code,name,location_text) SELECT id,'S02',CONCAT(name,N' · 东区店'),N'东区教学楼' FROM merchant WHERE merchant_code IN('M001','M003');
GO
DECLARE @owner BIGINT=(SELECT id FROM app_user WHERE username='owner'),@consumer BIGINT=(SELECT id FROM app_user WHERE username='customer');
DECLARE @n INT=1,@store BIGINT,@order BIGINT,@payment BIGINT,@amount BIGINT,@at DATETIME2(3),@no VARCHAR(64),@refund BIGINT,@creator BIGINT;
WHILE @n<=180
BEGIN
 SELECT @store=id FROM(SELECT id,ROW_NUMBER() OVER(ORDER BY id) n FROM store)s WHERE n=(@n%5)+1;
 SELECT @creator=u.id FROM store s JOIN merchant m ON m.id=s.merchant_id JOIN app_user u ON u.username=CASE WHEN m.merchant_code='M001' THEN 'owner' ELSE 'otherowner' END WHERE s.id=@store;
 SET @amount=CASE @n%6 WHEN 0 THEN 10000 WHEN 1 THEN 1800 WHEN 2 THEN 2800 WHEN 3 THEN 3500 WHEN 4 THEN 1200 ELSE 5600 END;
 SET @at=DATEADD(MINUTE,(@n*37)%600,DATEADD(DAY,-(@n%28),CONVERT(DATETIME2(3),CONVERT(DATE,DATEADD(HOUR,8,SYSUTCDATETIME())))));
 INSERT business_order(order_no,store_id,consumer_id,created_by,client_request_id,subject,status,created_at) VALUES(CONCAT('DEMO_ORD_',FORMAT(@n,'0000')),@store,@consumer,@creator,CONVERT(VARCHAR(36),NEWID()),CONCAT(N'教学订单 · ',CASE @n%3 WHEN 0 THEN N'校园消费' WHEN 1 THEN N'午间收款' ELSE N'门店服务' END),'WAITING_PAYMENT',@at);
 SET @order=SCOPE_IDENTITY();
 INSERT order_item(order_id,line_no,item_name,category_code,quantity,unit_price_cent) VALUES(@order,1,N'教学收款项目',N'演示',1,@amount);
 SET @no=CONCAT('DEMO_PAY_',FORMAT(@n,'0000'));
 INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status,created_at) VALUES(@order,CONVERT(VARCHAR(36),NEWID()),@no,'DEMO','DEMO_APP','DEMO_SELLER','PENDING',@at);
 SET @payment=SCOPE_IDENTITY();
 EXEC usp_confirm_payment @payment_id=@payment,@channel_trade_no=@no,@amount_cent=@amount,@occurred_at=@at;
 IF @n%9=0
 BEGIN
  INSERT refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,status,applicant_id,reviewed_by,reviewed_at,created_at)
   VALUES(@payment,CONVERT(VARCHAR(36),NEWID()),CONCAT('DEMO_REF_',FORMAT(@n,'0000')),@amount/3,N'教学：部分项目取消','APPROVED',@consumer,@owner,DATEADD(DAY,1,@at),DATEADD(DAY,1,@at));
  SET @refund=SCOPE_IDENTITY();DECLARE @refundAmount BIGINT=@amount/3,@refundAt DATETIME2(3)=DATEADD(DAY,1,@at);
  EXEC usp_confirm_refund @refund_id=@refund,@amount_cent=@refundAmount,@occurred_at=@refundAt;
 END;
 SET @n+=1;
END;
GO
-- 固定跨日例：昨日收100元，今日退款30元，合计净收70元。
DECLARE @store BIGINT=(SELECT TOP 1 id FROM store WHERE merchant_id=(SELECT id FROM merchant WHERE merchant_code='M001') ORDER BY id),@actor BIGINT=(SELECT id FROM app_user WHERE username='owner'),@consumer BIGINT=(SELECT id FROM app_user WHERE username='customer');
DECLARE @at DATETIME2(3)=DATEADD(HOUR,2,DATEADD(DAY,-1,CONVERT(DATETIME2(3),CONVERT(DATE,DATEADD(HOUR,8,SYSUTCDATETIME()))))),@order BIGINT,@payment BIGINT,@refund BIGINT;
INSERT business_order(order_no,store_id,consumer_id,created_by,client_request_id,subject,status,created_at) VALUES('DEMO_CROSS_DAY',@store,@consumer,@actor,CONVERT(VARCHAR(36),NEWID()),N'跨日案例 · 昨日收100今日退30','WAITING_PAYMENT',@at);SET @order=SCOPE_IDENTITY();
INSERT order_item(order_id,line_no,item_name,category_code,quantity,unit_price_cent) VALUES(@order,1,N'跨日教学服务',N'演示',1,10000);
INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status) VALUES(@order,CONVERT(VARCHAR(36),NEWID()),'DEMO_CROSS_PAY','DEMO','DEMO_APP','DEMO_SELLER','PENDING');SET @payment=SCOPE_IDENTITY();
EXEC usp_confirm_payment @payment_id=@payment,@channel_trade_no='DEMO_CROSS_CHANNEL',@amount_cent=10000,@occurred_at=@at;
INSERT refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,status,applicant_id,reviewed_by) VALUES(@payment,CONVERT(VARCHAR(36),NEWID()),'DEMO_CROSS_REF',3000,N'跨日部分退款教学案例','APPROVED',@consumer,@actor);SET @refund=SCOPE_IDENTITY();SET @at=DATEADD(DAY,1,@at);
EXEC usp_confirm_refund @refund_id=@refund,@amount_cent=3000,@occurred_at=@at;
GO
-- 原始教学账单按事实复制后，仅在输入侧构造差错，不改本地交易。
DECLARE @m BIGINT=(SELECT id FROM merchant WHERE merchant_code='M001'),@actor BIGINT=(SELECT id FROM app_user WHERE username='finance'),@batch BIGINT,@start DATETIME2(3)=DATEADD(DAY,-30,SYSUTCDATETIME()),@end DATETIME2(3)=DATEADD(DAY,1,SYSUTCDATETIME());
INSERT reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,completeness,created_by) VALUES('DEMO_BASELINE','MERCHANT',@m,@start,@end,'DEMO_SEED','DECLARED_COMPLETE',@actor);SET @batch=SCOPE_IDENTITY();
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,out_request_no,amount_cent,channel_occurred_at)
 SELECT @batch,ROW_NUMBER() OVER(ORDER BY id),entry_type,out_trade_no,channel_trade_no,out_request_no,CASE WHEN id%17=0 THEN amount_cent+100 ELSE amount_cent END,channel_occurred_at FROM v_transaction_fact WHERE merchant_id=@m AND id%13<>0;
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at) VALUES(@batch,9001,'RECEIPT','DEMO_CHANNEL_ONLY','DEMO_ORPHAN',2500,DATEADD(DAY,-1,SYSUTCDATETIME()));
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at) SELECT TOP 1 @batch,9002,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at FROM statement_line WHERE import_batch_id=@batch AND event_type='RECEIPT';
INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,amount_cent,channel_occurred_at) VALUES(@batch,9003,'REFUND','DEMO_UNRESOLVED',1200,DATEADD(DAY,-1,SYSUTCDATETIME()));
EXEC usp_run_reconciliation @batch_id=@batch;
GO
-- 历史案例具有来源结果、处理和独立复核，明确为教学构造。
DECLARE @handler BIGINT=(SELECT id FROM app_user WHERE username='cashier'),@reviewer BIGINT=(SELECT id FROM app_user WHERE username='reviewer');
UPDATE exception_task SET assignee_id=@handler,status='PROCESSING',analysis_text=N'教学案例：已核对支付号、发生日期、金额及渠道查询记录。',resolution_text=N'教学案例：保留原流水；取得修订账单或主动查询证据后重新核对，异常输入归档备查。',submitted_by=@handler,reviewed_by=@reviewer WHERE id%3=0;
UPDATE exception_task SET status='CLOSED',resolution_code='KNOWN_EXCEPTION',closed_at=SYSUTCDATETIME() WHERE id%3=0;
UPDATE exception_task SET title=N'教学案例 · 通知未达与跨日退款核实',description=CONCAT(description,N' 支付成功但回调未到达，退款发生在次日，需要按交易发生日期和原支付关联核实。') WHERE id%2=0;
GO
