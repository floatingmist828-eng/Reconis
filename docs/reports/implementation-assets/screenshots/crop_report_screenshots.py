"""Lossless report crops of inspected 2026-11-04 local Reconis screenshots.

No painting, resizing, text replacement, or composition is performed. Originals
are preserved. Pillow crop coordinates are [left, top, right, bottom), in pixels.
"""
from pathlib import Path
from PIL import Image
import hashlib, json

HERE=Path(__file__).resolve().parent
# Output stem, source stem, crop bounds, route, retained evidence and limitations.
CROPS=[
('01-home-report','01-home',(0,0,1384,905),'/welcome','首屏品牌、标题、产品场景及底部功能示例声明；仅作产品介绍，不作真实经营金额证据。'),
('02-login-report','02-login',None,'/login','完整登录页；输入框为空，无密码或密钥。'),
('03-dashboard-report','03-dashboard',(244,0,1360,468),'/','教学环境与平台财务身份、商户和期间筛选、净收120/收款150/退款30及金额口径；仅保留金额摘要，不包含单日趋势图或待办。'),
('04-orders-report','04-orders',(250,100,1356,785),'/orders','查询条件、三行订单、100/50元已支付及1元已取消、分页与明细入口。'),
('05-order-detail-report','05-order-detail',(250,103,1356,1037),'/orders/266','订单100元、已支付/部分退款、订单行、收100退30时间线、主支付及可退70元；不截入不完整的下方退款表。'),
('06-refunds-report','06-refunds',(250,100,1356,924),'/refunds','三条退款申请及分页：1元与70.01元已退回，30元已成功。'),
('07-reconciliation-report','07-reconciliation',(299,0,1399,670),'/reconciliation','完整结果抽屉：批次127、金额不符2/账实相符1、两侧100对101/50对51/30对30、分类原因及三行分页。'),
('08-compare-report','08-compare',(299,0,1399,655),'/reconciliation','完整比较抽屉：当前批次130、基线127、3行事件，两个RESOLVED和一个UNCHANGED，基线与当前分类全部可见。'),
('09-import-preview-report','09-import-preview',(260,194,1140,1064),'/reconciliation','完整预览弹窗：文件transactions.xml、原始3/有效3/重复0/错误0、归属商户、两格式模板与操作；当前为未知/不完整，期间10月6日至11月4日，未再次导入。'),
('10-case-closed-report','10-case-closed',(250,103,1356,913),'/cases/115','已关闭工单、当前处理人、两侧50元、原始金额不符标签、订单与批次关联、复核人/结案类型/时间及一条完整推荐。'),
('10-case-analysis-report','10-case-closed',(260,1047,944,1273),'/cases/115','同一截图左栏原因分析局部；只包含原因分析，处理结论仍在原屏幕下方，不冒称完整结论。'),
('11-case-history-report','11-case-history',(260,106,944,1068),'/cases/115','左栏完整四个节点：提交、退回处理、重新提交、复核关闭（画面倒序）；保留人员、时间、状态变化及请求编号。'),
('12-evidence-chain-report','12-evidence-chain',(260,182,944,936),'/cases/115','差错诊断与证据档案：6个对账版本/1笔账本事实/4原始行/7证据文件；关联订单267、成功支付、无退款、50元收款事实及渠道时间。'),
('13-analytics-merchant-report','13-analytics-merchant',(250,100,1356,1284),'/analytics','期间/商户/维度/截止时点、120/150/30与3个资金事件、收退款图与匹配分布、完整商户分组行。'),
('14-analytics-store-report','14-analytics-store',(260,470,1348,736),'/analytics','组织层级面包屑、门店44分组、3事件及收150/退30/净120；筛选上文见13，当前截图不含顶部筛选。'),
('15-analytics-detail-report','15-analytics-detail',(250,193,1356,738),'/analytics','全部范围→商户→门店面包屑，退款30/收款50/收款100三条明细、事件键、业务日、分页。'),
('16-fulltext-report','16-fulltext',(250,100,1356,1290),'/search','检索词“修订账单”、已解决案例选项、17条结果计数及前三个结果；第三条为本轮工单115，含结论与时间。'),
('17-audit-report','17-audit',(250,100,1356,1055),'/audit','事件分布、人员统计、商户54筛选、183条事件及前五条完整记录；没有可见requestId列。'),
]
PENDING=[]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    records=[]
    for out,src,bounds,route,note in CROPS:
        original=HERE/f'{src}.png'; dest=HERE/f'{out}.png'
        with Image.open(original) as im:
            box=bounds or (0,0,*im.size)
            assert 0<=box[0]<box[2]<=im.width and 0<=box[1]<box[3]<=im.height,(src,box,im.size)
            cropped=im.crop(box)
            cropped.save(dest)
            with Image.open(dest) as check:assert check.tobytes()==cropped.tobytes()
            records.append(dict(output=dest.name,source=original.name,source_size=list(im.size),bounds=list(box),output_size=list(cropped.size),route=route,note=note,source_sha256=sha(original),output_sha256=sha(dest)))
    (HERE/'screenshot-manifest.json').write_text(json.dumps({'source_origin':'http://localhost:8080','captured_date':'2026-11-04','environment':'DEMO','release_id':'homepage-redesign-20261104-r1','operation':'Pillow crop only; lossless, no resize or retouch','records':records,'pending_retake':PENDING},ensure_ascii=False,indent=2),encoding='utf-8')
    lines=['# 报告截图来源与裁切清单','','工作附件，不属于正式报告正文。全部原图由当前本地运行服务 http://localhost:8080 于2026年11月4日采集，发布版本 homepage-redesign-20261104-r1；业务页面为DEMO教学环境。公开首页明确使用示例数据。','','裁切工具为Pillow，保留原始PNG；只删除导航、外围空白或不完整边缘区域，不重绘、不替换文字金额、不拼接页面、不缩放。输出PNG逐像素与对应原图矩形一致。坐标为左上包含、右下不包含的像素范围。完整文件散列保存在 screenshot-manifest.json。','','| 报告文件 | 来源及页面 | 原图尺寸 | 裁切矩形 | 输出尺寸 | 可读内容与边界 |','|---|---|---|---|---|---|']
    for r in records:
        lines.append(f"| {r['output']} | {r['source']}；{r['route']} | {r['source_size'][0]}×{r['source_size'][1]} | {tuple(r['bounds'])} | {r['output_size'][0]}×{r['output_size'][1]} | {r['note']} |")
    lines += ['','## 视觉检查','','已用实际图像查看工具逐张检查来源图，并检查裁切结果。截图保留原生像素，不将长屏缩成小图；版式中应按章节需要使用完整宽度或独立局部。11为工单历史窄栏、12为证据左栏、14为分组表局部，其余裁切说明见表。','', '## 补拍和边界处理','','07、08、12初始来源曾存在关键区域截断，已由当前运行页面重新采集完整全屏并核验后裁切，最终不存在待补拍项目。10提供关闭状态主图和原因分析局部两张图片，均来自同一原屏幕；未拼接像素或补写不可见结论。03只证明金额摘要，09保留未知完整性预览，17不声称显示requestId。']
    (HERE/'screenshot-manifest.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(f'{len(records)} lossless crops created; {len(PENDING)} sources pending retake.')

if __name__=='__main__':main()
