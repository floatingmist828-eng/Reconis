-- Shared catalog for campus fixtures and legacy label refresh.
CREATE TABLE #CampusMerchant (
 code VARCHAR(32) PRIMARY KEY, name NVARCHAR(100), category NVARCHAR(30), contact NVARCHAR(150), status VARCHAR(16)
);
INSERT #CampusMerchant VALUES
 ('M001',N'海棠咖啡店',N'咖啡简餐',N'店务 · 陈禾','ACTIVE'),
 ('M002',N'松风文印社',N'文印服务',N'店务 · 王越','ACTIVE'),
 ('M003',N'四时便利店',N'校园零售',N'店务 · 王越','ACTIVE'),
 ('M004',N'蘅芜轻食',N'轻食简餐',N'店务 · 叶青','PENDING'),
 ('M005',N'老成都砂锅店',N'川味餐饮',N'店务 · 何川','ACTIVE'),
 ('M006',N'白露烘焙坊',N'烘焙甜点',N'店务 · 梁晓','ACTIVE'),
 ('M007',N'青瓦茶铺',N'茶饮',N'店务 · 苏青','ACTIVE'),
 ('M008',N'闻溪书屋',N'图书文创',N'店务 · 程书言','ACTIVE');

CREATE TABLE #CampusStore (
 code VARCHAR(32), store_code VARCHAR(32), name NVARCHAR(100), location NVARCHAR(200), PRIMARY KEY(code,store_code)
);
INSERT #CampusStore VALUES
 ('M001','S01',N'图书馆店',N'图书馆东门一层'),
 ('M001','S02',N'东区店',N'东区教学楼连廊'),
 ('M002','S01',N'教学楼店',N'教学楼 B 座东侧'),
 ('M003','S01',N'生活区店',N'生活服务中心一层'),
 ('M003','S02',N'东区店',N'东区学生公寓南门'),
 ('M005','S01',N'宿舍区店',N'宿舍区西门餐饮街'),
 ('M006','S01',N'宿舍楼店',N'学生公寓三号楼下'),
 ('M007','S01',N'银杏路店',N'银杏路与校园主路交口'),
 ('M008','S01',N'图书馆南店',N'图书馆南侧文创长廊');

CREATE TABLE #CampusOffer (
 code VARCHAR(32), slot INT, amount_cent BIGINT, item_name NVARCHAR(120), PRIMARY KEY(code,slot)
);
INSERT #CampusOffer
SELECT v.code, p.slot, p.amount_cent, p.item_name
FROM (VALUES
 ('M001',N'挂耳咖啡分享盒',N'燕麦拿铁',N'美式与柠檬磅蛋糕',N'手冲咖啡与司康',N'冰美式',N'双人咖啡甜点'),
 ('M002',N'社团招新彩印与装订',N'课程资料胶装',N'实验报告彩印',N'讲义复印与装订',N'简历彩打',N'课程设计图文装订'),
 ('M003',N'班会零食组合',N'饭团与酸奶',N'面包牛奶早餐包',N'洗护补给组合',N'果汁与坚果',N'宿舍生活用品补给'),
 ('M005',N'砂锅小聚套餐',N'菌菇砂锅饭',N'泡椒牛肉砂锅',N'排骨砂锅双拼',N'红糖冰粉与小食',N'双人砂锅套餐'),
 ('M006',N'社团活动点心预订',N'黄油可颂与牛奶',N'咸蛋黄贝果双拼',N'吐司与曲奇组合',N'原味司康',N'可颂与吐司分享盒'),
 ('M007',N'茶会点心与冷泡茶',N'桂花乌龙',N'茉莉奶绿与茶点',N'双杯白桃乌龙',N'青柠绿茶',N'四杯冷泡茶组合'),
 ('M008',N'图书与笔记本套装',N'素页手账本',N'校园插画明信片套装',N'小说与书签',N'金属书签',N'校园文创礼盒')
) v(code, offer0, offer1, offer2, offer3, offer4, offer5)
CROSS APPLY (VALUES
 (0,10000,v.offer0),(1,1800,v.offer1),(2,2800,v.offer2),
 (3,3500,v.offer3),(4,1200,v.offer4),(5,5600,v.offer5)
) p(slot,amount_cent,item_name);

CREATE TABLE #CampusCase (
 classification VARCHAR(32) PRIMARY KEY, title NVARCHAR(200), description NVARCHAR(400)
);
INSERT #CampusCase VALUES
 ('CHANNEL_ONLY',N'渠道到账待认领',N'渠道账单有入账记录，本地未找到对应流水。核对交易号、金额和到账时间。'),
 ('LOCAL_ONLY',N'渠道账单缺少交易',N'本地已确认交易，当前渠道账单未找到对应记录。检查账期并补取原始账单。'),
 ('AMOUNT_MISMATCH',N'账单金额与流水不一致',N'两侧交易号一致，金额不同。核对原订单、退款记录和渠道账单。'),
 ('BILL_DUPLICATE',N'渠道账单重复记录',N'同一交易在渠道账单出现多次。核对原始行并确认重复来源。'),
 ('KEY_UNRESOLVED',N'退款记录待关联原单',N'渠道记录缺少可匹配的标识。根据退款号和到账时间核实原订单。'),
 ('PENDING_CONFIRMATION',N'交易结果待确认',N'本地支付状态尚未确认。根据渠道查询结果核实到账情况。'),
 ('EXTRA_SUCCESS',N'同一订单存在额外收款',N'已确认的订单出现另一笔渠道收款。核实付款人和可退款额度。');
GO
