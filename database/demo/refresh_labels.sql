-- One-time label corrections for known ReconisDemo fixtures. All business identifiers stay intact.
IF DB_NAME()<>'ReconisDemo' THROW 51101,N'此操作仅适用于 ReconisDemo',1;
SET XACT_ABORT ON;
BEGIN TRY
 BEGIN TRANSACTION;
 IF EXISTS(SELECT 1 FROM sys.triggers WHERE is_disabled=1
  AND name IN('trg_order_item_freeze','trg_refund_audit','trg_case_audit'))
  THROW 51102,N'订单、退款或工单的完整性触发器未启用',1;

 DECLARE @legacy TABLE (code VARCHAR(32), actual_code VARCHAR(32), old_name NVARCHAR(100));
 INSERT @legacy VALUES
  ('M001','M001',N'栖木咖啡'),
  ('M002','M002',N'青禾文印'),
  ('M003','M003',N'四时便利'),
  ('M004','M004',N'拾光轻食'),
  ('M005','Mb3c05e3918e8485',N'V20261102121732c529eb 校园验证商户更新'),
  ('M006','Mb48f4da35d0346e',N'V202611021226132e11a7 校园验证商户更新'),
  ('M007','V202611021226132e11a7_BULK',N'V202611021226132e11a7 批量导入商户');

 UPDATE m SET name=c.name,category_code=c.category,contact=c.contact,
  review_reason=CASE WHEN m.review_reason=N'验证变更审核' THEN N'门店资料已核实' ELSE m.review_reason END,
  updated_at=SYSUTCDATETIME()
 FROM merchant m JOIN @legacy l ON l.actual_code=m.merchant_code AND l.old_name=m.name
 JOIN #CampusMerchant c ON c.code=l.code
 WHERE NOT EXISTS(SELECT 1 FROM merchant existing WHERE existing.id<>m.id AND existing.name=c.name);

 INSERT merchant(merchant_code,name,category_code,contact,status)
 SELECT c.code,c.name,c.category,c.contact,c.status FROM #CampusMerchant c
 WHERE c.code IN ('M005','M006','M007','M008')
 AND NOT EXISTS(SELECT 1 FROM merchant m WHERE m.merchant_code=c.code OR m.name=c.name);

 DECLARE @oldStore TABLE (code VARCHAR(32), actual_code VARCHAR(32), store_code VARCHAR(32), actual_store VARCHAR(32), old_name NVARCHAR(100));
 INSERT @oldStore VALUES
  ('M001','M001','S01','S01',N'栖木咖啡 · 主店'),
  ('M001','M001','S02','S02',N'栖木咖啡 · 东区店'),
  ('M002','M002','S01','S01',N'青禾文印 · 主店'),
  ('M003','M003','S01','S01',N'四时便利 · 主店'),
  ('M003','M003','S02','S02',N'四时便利 · 东区店'),
  ('M005','Mb3c05e3918e8485','S01','S32c529eb',N'验证一店更新'),
  ('M006','Mb48f4da35d0346e','S01','S132e11a7',N'验证一店更新');

 UPDATE s SET name=c.name,location_text=c.location,updated_at=SYSUTCDATETIME()
 FROM store s JOIN merchant m ON m.id=s.merchant_id
 JOIN @oldStore l ON l.actual_code=m.merchant_code AND l.actual_store=s.store_code AND l.old_name=s.name
 JOIN #CampusStore c ON c.code=l.code AND c.store_code=l.store_code;

 INSERT store(merchant_id,store_code,name,location_text)
 SELECT m.id,s.store_code,s.name,s.location
 FROM #CampusStore s JOIN #CampusMerchant c ON c.code=s.code
 LEFT JOIN @legacy l ON l.code=c.code
 JOIN merchant m ON m.name=c.name AND (m.merchant_code=c.code OR m.merchant_code=l.actual_code)
 WHERE c.code IN ('M005','M006','M007','M008')
 AND NOT EXISTS(SELECT 1 FROM store existing WHERE existing.merchant_id=m.id);

 UPDATE app_user SET display_name=CASE username WHEN 'customer' THEN N'林知夏' ELSE N'叶青' END
 WHERE (username='customer' AND display_name=N'林同学') OR (username='pending' AND display_name=N'待审核同学');

 DECLARE @people TABLE (suffix VARCHAR(16), u1 VARCHAR(64), n1 NVARCHAR(80), u2 VARCHAR(64), n2 NVARCHAR(80));
 INSERT @people VALUES
  ('owner','hechuan',N'何川','liangxiao',N'梁晓'),
  ('handler','luwen',N'陆闻','sutang',N'苏棠'),
  ('reviewer','zhouxuan',N'周璇','xuyin',N'许音'),
  ('customer','tangxia',N'唐夏','shenyou',N'沈攸'),
  ('outsider','gujian',N'顾简','luoyan',N'罗妍'),
  ('finance','songlan',N'宋岚','chenlu',N'陈露'),
  ('auditor','heyu',N'何予','jixuan',N'纪萱');
 UPDATE u SET username=v.username,display_name=v.display_name
 FROM app_user u CROSS JOIN @people p
 CROSS APPLY (VALUES
  (CONCAT('V20261102121732c529eb_',p.suffix),CONCAT(N'验证',p.suffix,'V20261102121732c529eb'),p.u1,p.n1),
  (CONCAT('V202611021226132e11a7_',p.suffix),CONCAT(N'验证',p.suffix,'V202611021226132e11a7'),p.u2,p.n2)
 ) v(old_username,old_name,username,display_name)
 WHERE u.username=v.old_username AND u.display_name=v.old_name AND u.status='DISABLED'
 AND NOT EXISTS(SELECT 1 FROM app_user existing WHERE existing.username=v.username);

 UPDATE app_user SET review_reason=N'账号已停用，历史业务记录保留'
 WHERE status='DISABLED' AND review_reason IN(
  N'V20261102121732c529eb 自动化验证结束，停用本次验证账号并保留业务与审计历史',
  N'V202611021226132e11a7 自动化验证结束，停用本次验证账号并保留业务与审计历史');
 UPDATE o SET remark=CASE m.merchant_code
   WHEN 'Mb3c05e3918e8485' THEN N'宿舍区店到店取餐' ELSE N'宿舍楼店到店自取' END
 FROM business_order o JOIN store s ON s.id=o.store_id JOIN merchant m ON m.id=s.merchant_id
 WHERE (m.merchant_code='Mb3c05e3918e8485' AND o.remark=N'V20261102121732c529eb')
    OR (m.merchant_code='Mb48f4da35d0346e' AND o.remark=N'V202611021226132e11a7');

 DECLARE @orderLabels TABLE (order_id BIGINT PRIMARY KEY, subject NVARCHAR(160), category NVARCHAR(30));
 INSERT @orderLabels
 SELECT o.id,p.item_name,m.category_code
 FROM business_order o JOIN order_item i ON i.order_id=o.id AND i.line_no=1
 JOIN store s ON s.id=o.store_id JOIN merchant m ON m.id=s.merchant_id
 JOIN #CampusOffer p ON p.code=m.merchant_code AND p.amount_cent=i.unit_price_cent
 WHERE o.order_no LIKE 'DEMO[_]ORD[_]%' AND o.subject LIKE N'教学订单 · %' AND i.item_name=N'教学收款项目' AND i.quantity=1;
 INSERT @orderLabels
 SELECT o.id,v.subject,v.category FROM business_order o JOIN store s ON s.id=o.store_id JOIN merchant m ON m.id=s.merchant_id
 JOIN (VALUES
  ('M001',N'跨日案例 · 昨日收100今日退30',N'读书会咖啡与茶点预订',N'咖啡简餐'),
  ('Mb3c05e3918e8485',N'V20261102121732c529eb NOTIFY 消费',N'砂锅小聚套餐',N'川味餐饮'),
  ('Mb3c05e3918e8485',N'V20261102121732c529eb LOST 消费',N'双份排骨砂锅',N'川味餐饮'),
  ('Mb3c05e3918e8485',N'V20261102121732c529eb cancel',N'米饭加购',N'川味餐饮'),
  ('Mb48f4da35d0346e',N'V202611021226132e11a7 NOTIFY 消费',N'社团活动点心预订',N'烘焙甜点'),
  ('Mb48f4da35d0346e',N'V202611021226132e11a7 LOST 消费',N'可颂与吐司分享盒',N'烘焙甜点'),
  ('Mb48f4da35d0346e',N'V202611021226132e11a7 cancel',N'纸袋加购',N'烘焙甜点')
 ) v(code,old_subject,subject,category) ON v.code=m.merchant_code AND v.old_subject=o.subject;
 UPDATE o SET subject=l.subject,updated_at=SYSUTCDATETIME()
 FROM business_order o JOIN @orderLabels l ON l.order_id=o.id;

 -- Metadata corrections take place under transactional schema locks. Enable the guards before commit.
 IF EXISTS(SELECT 1 FROM @orderLabels)
 BEGIN
  DISABLE TRIGGER dbo.trg_order_item_freeze ON dbo.order_item;
  UPDATE i SET item_name=l.subject,category_code=l.category
  FROM order_item i JOIN @orderLabels l ON l.order_id=i.order_id
  WHERE i.line_no=1 AND i.item_name IN(N'教学收款项目',N'跨日教学服务',N'校园验证服务',N'验证草稿');
  ENABLE TRIGGER dbo.trg_order_item_freeze ON dbo.order_item;
 END;

 IF EXISTS(SELECT 1 FROM refund_request WHERE reason LIKE N'教学：%' OR reason=N'跨日部分退款教学案例'
  OR reason=N'DEMO并发额度核对' OR reason LIKE N'V20261102121732c529eb %' OR reason LIKE N'V202611021226132e11a7 %')
 BEGIN
  DISABLE TRIGGER dbo.trg_refund_audit ON dbo.refund_request;
  UPDATE refund_request SET
   reason=CASE
    WHEN out_request_no='DEMO_CROSS_REF' THEN N'活动人数减少，退回部分茶点款'
    WHEN reason LIKE N'%超额退款验证' THEN N'申请退回剩余消费款'
    WHEN reason LIKE N'%验证申请审批分离' THEN N'重复提交退款申请'
    WHEN reason=N'DEMO并发额度核对' THEN N'申请退回未消费的预订款'
    ELSE N'取消部分商品'
   END,
   review_reason=CASE review_reason
    WHEN N'验证后拒绝超额申请' THEN N'申请金额超过剩余可退金额'
    WHEN N'独立人员终止验证申请' THEN N'同一订单已有退款记录'
    WHEN N'核实取消部分服务' THEN N'已核实取消项目与退款金额'
    WHEN N'额度核对' THEN N'已核实可退余额'
    ELSE review_reason
   END
  WHERE (out_request_no LIKE 'DEMO[_]REF[_]%' AND reason=N'教学：部分项目取消')
     OR (out_request_no='DEMO_CROSS_REF' AND reason=N'跨日部分退款教学案例')
     OR reason=N'DEMO并发额度核对'
     OR reason IN(N'V20261102121732c529eb 部分退款',N'V20261102121732c529eb 超额退款验证',N'V20261102121732c529eb 验证申请审批分离',
                  N'V202611021226132e11a7 部分退款',N'V202611021226132e11a7 超额退款验证',N'V202611021226132e11a7 验证申请审批分离');
  ENABLE TRIGGER dbo.trg_refund_audit ON dbo.refund_request;
 END;

 UPDATE t SET title=c.title,description=c.description
 FROM exception_task t JOIN reconciliation_result r ON r.id=t.origin_result_id
 JOIN reconciliation_batch b ON b.id=r.batch_id
 LEFT JOIN merchant m ON m.id=b.merchant_id
 JOIN #CampusCase c ON c.classification=r.classification
 WHERE (b.source_type='DEMO_SEED' OR m.merchant_code IN('Mb3c05e3918e8485','Mb48f4da35d0346e')
   OR r.source_event_key='P:V202611021226132e11a7_UNKNOWN')
 AND (t.title LIKE N'教学案例%' OR t.description LIKE N'交易：%。请核对两侧明细并记录处理结果。%');

 IF EXISTS(SELECT 1 FROM exception_task WHERE analysis_text LIKE N'教学案例：%'
    OR analysis_text=N'V202611021226132e11a7 核对账单与订单支付退款关联，渠道输入金额错误。')
 BEGIN
  DISABLE TRIGGER dbo.trg_case_audit ON dbo.exception_task;
  UPDATE exception_task SET
   analysis_text=CASE
    WHEN analysis_text=N'教学案例：已核对支付号、发生日期、金额及渠道查询记录。'
     THEN N'已按交易号核对原订单、到账时间及渠道账单。差异行已登记在对应批次。'
    ELSE N'已核对账单、订单及退款记录，差异来自渠道账单金额。'
   END,
   resolution_text=CASE
    WHEN resolution_text=N'教学案例：保留原流水；取得修订账单或主动查询证据后重新核对，异常输入归档备查。'
     THEN N'保留原始流水和渠道账单，差异纳入当期核对记录。收到更正账单后按原交易号复核。'
    ELSE resolution_text
   END
  WHERE analysis_text IN(N'教学案例：已核对支付号、发生日期、金额及渠道查询记录。',
                         N'V202611021226132e11a7 核对账单与订单支付退款关联，渠道输入金额错误。');
  ENABLE TRIGGER dbo.trg_case_audit ON dbo.exception_task;
 END;

 IF EXISTS(SELECT 1 FROM @orderLabels)
 INSERT audit_log(event_category,action,result,summary)
 VALUES('DB_CHANGE','CAMPUS_LABEL_REFRESH','SUCCESS',N'更新预置门店与消费资料的占位文案，保留交易标识、金额及状态。');

 COMMIT TRANSACTION;
END TRY
BEGIN CATCH
 IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
 THROW;
END CATCH;
GO
