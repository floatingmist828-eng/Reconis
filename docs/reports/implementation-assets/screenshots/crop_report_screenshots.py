"""Verified 2026-11-06 screenshots; crop only, no retouch or resize."""
from pathlib import Path
from PIL import Image
import hashlib, json
HERE=Path(__file__).resolve().parent
CROPS=[
('01-home',(0,0,1425,740),'/welcome','最终公开首页；咖啡店与对账场景属于产品示意，不作为真实经营金额证据。'),
('02-login',None,'/login','完整登录页；输入框为空，无密码或密钥。'),
('03-dashboard',(250,0,1390,455),'/','财务身份、商户54和期间筛选、净收120/收款150/退款30；仅金额摘要。'),
('04-orders',(250,95,1410,750),'/orders','报告样本三笔订单：100/50元已支付及1元已取消；含查询和分页。'),
('05-order-detail',(250,100,1390,1040),'/orders/266','100元订单、收100退30时间线、主支付、可退70元；不包含下方退款表。'),
('06-refunds',(250,95,1410,925),'/refunds','三条申请：1元与70.01元已退回、30元已成功；原UI长原因省略显示。'),
('07-reconciliation',(340,0,1440,630),'/reconciliation','批次127：100/101与50/51两笔差异、30/30退款相符，全部三行。'),
('08-compare',(340,0,1440,620),'/reconciliation','修订输入批次130（BATc11ee3b1171a433eafe67866d7a443e7）对比127：两笔已匹配，一笔无变化。'),
('09-import-preview',(280,195,1160,1076),'/reconciliation','transactions.xml：3原始/3有效/0重复/0错误；未知完整性，2026-10-08至11-06，只预览未导入。'),
('10-case-closed',(250,100,1390,915),'/cases/115','工单关闭状态、关联订单与批次、两侧50元及独立复核记录。'),
('11-case-history',(275,200,945,1140),'/cases/115','四个完整节点：提交、退回、重新提交、复核关闭；页面倒序，保留人员、时间和请求号。'),
('12-evidence-chain',(260,155,960,905),'/cases/115','6版本/1账本事实/4原始行/7文件；订单267、成功支付、无退款和50元收款事实。'),
('13-analytics-merchant',(250,95,1390,1175),'/analytics','筛选、120/150/30与3事件、单日收退款图和完整商户分组。'),
('14-analytics-store',(260,945,1380,1195),'/analytics','组织面包屑、门店44及3事件、收150/退30/净120；筛选见前图。'),
('15-analytics-detail',(260,920,1380,1265),'/analytics','全部范围到商户到门店，退款30/收款50/收款100三条事件及业务日；未含分页。'),
('16-fulltext',(250,95,1390,1218),'/search','修订账单、已解决案例、17条结果及前三项；第三项为工单115。'),
('17-audit',(250,95,1390,1130),'/audit','商户54筛选、224条事件与访问记录；结果类别统计不等于事件总数，不声称显示requestId。'),
('18-category-orders',(250,80,1390,850),'/orders','DEMO_ORD教学订单，展示分类插画与金额、状态层级；独立于150元业务样本。'),
]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    records=[]
    for src,bounds,route,note in CROPS:
        original=HERE/f'{src}.jpg';dest=HERE/f'{src}-report.png'
        with Image.open(original) as im:
            box=bounds or (0,0,*im.size)
            assert 0<=box[0]<box[2]<=im.width and 0<=box[1]<box[3]<=im.height,(src,box,im.size)
            cropped=im.crop(box);cropped.save(dest)
            im.save(HERE/f'{src}.png')
            with Image.open(dest) as check:assert check.tobytes()==cropped.tobytes()
            records.append(dict(output=dest.name,source=original.name,source_size=list(im.size),bounds=list(box),output_size=list(cropped.size),route=route,note=note,source_sha256=sha(original),output_sha256=sha(dest)))
    manifest={'source_origin':'http://127.0.0.1:8080','captured_date':'2026-11-06','environment':'DEMO','release_id':'category-artwork-20261106-r2','operation':'Original browser capture decoded and cropped to PNG; no resizing, retouch or text replacement.','records':records,'pending_retake':[]}
    (HERE/'screenshot-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf8')
    lines=['# 报告截图来源与裁切清单','','工作附件，不属于正式报告正文。18张图均于2026年11月6日从本地最终版本 category-artwork-20261106-r2 采集，业务环境为DEMO。11月4日业务实例保留，截图日期与业务日期分别记录。','','原始浏览器截图保存在同名JPG文件，PNG原图是其解码副本；报告图仅裁切，不缩放、不拼接、不替换金额或文字。坐标为左上包含、右下不包含。公开首页的场景属于示意。','','| 图片 | 页面 | 裁切区域 | 内容与边界 |','|---|---|---|---|']
    for r in records:lines.append(f"| {r['output']} | {r['route']} | {tuple(r['bounds'])} | {r['note']} |")
    lines+=['','全部源图已在浏览器中逐张核对；最终入稿版面另见 evidence/20261106 的逐页检查记录。未执行再次付款、退款、导入或重跑操作。']
    (HERE/'screenshot-manifest.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
    print(f'{len(records)} report crops written')
if __name__=='__main__':main()
