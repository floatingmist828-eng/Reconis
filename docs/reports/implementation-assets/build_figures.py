"""Build report-only figures as SVG and 300 dpi PNG using the bundled Python + Pillow.

Run from any directory. ER fields and explicit FK connections are checked against
../evidence/20261104/schema-summary.json. No system-design assets are modified.
All coordinates and font sizes are in printer points; the figure width is 6.3 in.
"""
from pathlib import Path
import json, math, html
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
SCHEMA = json.loads((OUT.parent / 'evidence/20261104/schema-summary.json').read_text(encoding='utf-8-sig'))['databases'][0]
TABLES = {t['name']: t for t in SCHEMA['tables']}
FKS = {(f['from_table'], c['from_column'], f['to_table']) for f in SCHEMA['foreign_keys'] for c in f['columns']}
W, DPI, SCALE = 453.6, 300, 300 / 72
INK, MUTED, BLUE, LINE, PALE, WHITE = '#202b38', '#526174', '#1d4876', '#aebbc9', '#f2f5f8', '#ffffff'
FONT = Path('C:/Windows/Fonts/msyh.ttc')
BOLD = Path('C:/Windows/Fonts/msyhbd.ttc')

class Figure:
    def __init__(self, name, height):
        self.name, self.h = name, height
        self.im = Image.new('RGB', (round(W*SCALE), round(height*SCALE)), WHITE)
        self.d = ImageDraw.Draw(self.im)
        self.svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="6.3in" height="{height/72:.5f}in" viewBox="0 0 {W} {height}">', f'<rect width="{W}" height="{height}" fill="white"/>']
        self.boxes = {}

    def rect(self, x, y, w, h, fill=WHITE, stroke=LINE, radius=3, sw=.8):
        self.d.rounded_rectangle(tuple(v*SCALE for v in (x,y,x+w,y+h)), radius=radius*SCALE, fill=fill, outline=stroke, width=max(1,round(sw*SCALE)))
        self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')

    def text(self, x, y, text, size=9.4, bold=False, color=INK, anchor='left'):
        # y is the visual center of a single text line.
        font = ImageFont.truetype(str(BOLD if bold else FONT), round(size*SCALE))
        box = self.d.textbbox((0,0), text, font=font)
        width, height = box[2]-box[0], box[3]-box[1]
        px = x*SCALE - (width/2 if anchor=='middle' else width if anchor=='right' else 0)
        py = y*SCALE-height/2-box[1]
        self.d.text((px,py), text, font=font, fill=color)
        svg_anchor={'left':'start','middle':'middle','right':'end'}[anchor]
        self.svg.append(f'<text x="{x}" y="{y}" dominant-baseline="central" text-anchor="{svg_anchor}" font-family="Microsoft YaHei, Noto Sans CJK SC, sans-serif" font-size="{size}" font-weight="{700 if bold else 400}" fill="{color}">{html.escape(text)}</text>')

    def lines(self, x, y, lines, size=9.4, gap=14, **kw):
        for i, line in enumerate(lines): self.text(x,y+i*gap,line,size=size,**kw)

    def path(self, points, arrow=True, color=BLUE, width=.9, dashed=False):
        pts=[(x*SCALE,y*SCALE) for x,y in points]
        if dashed:
            for (x1,y1),(x2,y2) in zip(pts,pts[1:]):
                dist=math.hypot(x2-x1,y2-y1)
                for k in range(0,math.ceil(dist),round(6*SCALE)):
                    a,b=k/dist,min(k+3*SCALE,dist)/dist
                    self.d.line([(x1+(x2-x1)*a,y1+(y2-y1)*a),(x1+(x2-x1)*b,y1+(y2-y1)*b)], fill=color,width=max(1,round(width*SCALE)))
        else: self.d.line(pts,fill=color,width=max(1,round(width*SCALE)),joint='curve')
        dash=' stroke-dasharray="3 3"' if dashed else ''
        self.svg.append(f'<polyline points="{" ".join(f"{x},{y}" for x,y in points)}" fill="none" stroke="{color}" stroke-width="{width}"{dash}/>')
        if arrow:
            (x0,y0),(x,y)=points[-2:]; a=math.atan2(y-y0,x-x0)
            p=[(x,y),(x-5*math.cos(a-.46),y-5*math.sin(a-.46)),(x-5*math.cos(a+.46),y-5*math.sin(a+.46))]
            self.d.polygon([(u*SCALE,v*SCALE) for u,v in p],fill=color)
            self.svg.append(f'<polygon points="{" ".join(f"{u},{v}" for u,v in p)}" fill="{color}"/>')

    def label(self,x,y,text,size=8.8,color=MUTED):
        font=ImageFont.truetype(str(FONT),round(size*SCALE))
        tw=self.d.textlength(text,font=font)/SCALE
        self.rect(x-tw/2-3,y-6.4,tw+6,12.8,fill=WHITE,stroke=WHITE,radius=0)
        self.text(x,y,text,size=size,color=color,anchor='middle')

    def box(self,key,x,y,w,h,title,body=(),accent=False,size=9.4):
        self.rect(x,y,w,h,fill=PALE if accent else WHITE,stroke=BLUE if accent else LINE)
        self.text(x+10,y+16,title,size=10,bold=True,color=BLUE if accent else INK)
        self.lines(x+10,y+35,body,size=size,gap=14,color=MUTED)
        self.boxes[key]=(x,y,w,h)

    def er(self,table,x,y,w,cn,fields,extra=()):
        for c in fields:
            assert c in {a['name'] for a in TABLES[table]['columns']}, (table,c)
        rows=[]
        for c in fields:
            col=next(a for a in TABLES[table]['columns'] if a['name']==c)
            tag='PK' if col['is_primary_key'] else 'FK' if any(t==table and fc==c for t,fc,_ in FKS) else '  '
            if table=='exception_task' and c=='origin_result_id':
                assert any(i['is_unique'] and [k['name'] for k in i['columns'] if not k['is_included_column']]==[c] for i in TABLES[table]['indexes'])
                tag='FK/UQ'
            rows.append(f'{tag}  {c}'+(' ?' if col['is_nullable'] and tag=='FK' else ''))
        h=44+len(rows)*13+len(extra)*13+7
        self.rect(x,y,w,h,stroke=BLUE)
        self.rect(x+.5,y+.5,w-1,38,fill=PALE,stroke=PALE,radius=2)
        self.text(x+9,y+12,cn,size=10,bold=True,color=BLUE)
        self.text(x+9,y+27,table,size=9.3,bold=True)
        self.lines(x+9,y+48,rows,size=9.1,gap=13)
        self.lines(x+9,y+48+len(rows)*13,extra,size=8.8,gap=13,color=MUTED)
        self.boxes[table]=(x,y,w,h)

    def fk(self,parent,child,col,points,label=None,at=None):
        assert (child,col,parent) in FKS, (parent,child,col)
        self.path(points)
        if label and at: self.label(*at,label)

    def note(self,y,lines): self.lines(10,y,lines,size=9,gap=14,color=MUTED)
    def save(self):
        self.svg.append('</svg>')
        (OUT/f'{self.name}.svg').write_text('\n'.join(self.svg),encoding='utf-8')
        self.im.save(OUT/f'{self.name}.png',dpi=(DPI,DPI))
        return {'name':self.name,'width_inches':6.3,'height_inches':round(self.h/72,3),'dpi':DPI,'width_px':self.im.width,'height_px':self.im.height}

def architecture():
    f=Figure('architecture',380)
    f.box('browser',10,12,433,56,'浏览器 · Vue 3 单页应用',['公开首页 / 登录 / 角色工作台 / 业务页面 / 分析图表'],True)
    f.path([(226,68),(226,94)]); f.label(226,81,'同源 HTTP · Session / CSRF')
    f.box('app',10,96,433,96,'Spring Boot 模块化单体',['身份与权限     商户与订单     支付与退款','导入与对账     差错与证据     分析与报告','MyBatis 数据访问   ·   定时查询与账单任务'],True)
    f.path([(90,192),(90,222)]); f.path([(226,192),(226,222)]); f.path([(365,192),(365,222)])
    f.box('db',10,224,137,99,'SQL Server 2022',['15 张关系表','过程 / 函数 / 触发器','视图 / 索引 / 中文全文'],size=9.1)
    f.box('file',158,224,137,99,'环境文件目录',['原账单 / 渠道响应','证据附件 / 导出文件','SHA256 + 范围授权'],size=9.1)
    f.box('gateway',306,224,137,99,'渠道适配',['DEMO 教学模拟','支付宝沙箱 SDK','主动查询 / 退款'],size=9.1)
    f.note(345,['DEMO 与沙箱使用独立数据库和文件目录。','真实公网签名回调尚未联调；沙箱账单模板不进入业务对账。'])
    return f

def function_map():
    f=Figure('function-map',387)
    f.box('root',109,10,236,48,'Reconis 校园支付对账',['交易事实、逐笔核对与差错协作'],True)
    xs=[10,232]; ys=[88,181,274]
    vals=[('身份与基础资料',['注册与审核 · 登录与权限','商户 · 门店 · 成员管理']),('收款与退款',['订单与明细 · 收款确认','独立退款审批 · 查询恢复']),('账单与对账',['导入校验 · 自动账单任务','逐笔差异 · 重跑与历史比较']),('差错与证据',['分派 · 处理 · 独立复核','原件校验 · 档案与留痕']),('经营分析',['商户/门店 · 年/月/日钻取','关联明细 · 风险指标 · 导出']),('知识与审计',['中文全文 · 相似案例','访问/操作日志 · 查询统计'])]
    f.path([(226,58),(226,359)],arrow=False,color=LINE)
    for i,(title,body) in enumerate(vals):
        x,y=xs[i%2],ys[i//2]
        f.path([(226,y+37),(x+211 if i%2==0 else x,y+37)])
        f.box(str(i),x,y,211,74,title,body,size=9.2)
    f.note(370,['功能按业务职责划分；各入口继续受角色与商户范围限制。'])
    return f

def er_overview():
    f=Figure('er-overview',542)
    titles=[('身份与组织',10),('交易与资金',158),('对账与协作',306)]
    for title,x in titles:f.text(x+69,14,title,bold=True,color=BLUE,anchor='middle')
    names=[
        [('merchant','商户'),('store','门店'),('app_user','用户'),('merchant_member','成员关系'),('audit_log','审计日志')],
        [('business_order','订单'),('payment_attempt','支付尝试'),('refund_request','退款申请'),('transaction_entry','资金事实'),('order_item','订单明细'),('payment_notification','支付通知')],
        [('reconciliation_batch','对账批次 / 任务'),('statement_line','账单原始行'),('reconciliation_result','对账结果'),('exception_task','差错工单')]]
    for ci,col in enumerate(names):
        for ri,(t,cn) in enumerate(col):
            assert t in TABLES
            x,y=10+148*ci,32+77*ri
            f.rect(x,y,137,51,fill=PALE if ri==0 else WHITE)
            f.text(x+8,y+15,cn,10,True)
            f.text(x+8,y+34,t,8.8,color=MUTED)
    for x,ys,labels in [(78,[(83,109),(237,263)],['1:N','1:N']), (226,[(83,109),(160,186),(237,263)],['1:N','1:N','0..1:0..1']), (374,[(83,109),(160,186),(237,263)],['1:N','0..1:N','1:N'])]:
        for (a,b),lab in zip(ys,labels): f.path([(x,a),(x,b)]); f.label(x+24,(a+b)/2,lab,size=8.5)
    f.path([(147,134),(152,134),(152,57),(158,57)]); f.label(152,96,'1:N',8.2)
    f.note(500,['展示 15 张真实表及核心关系；箭头由被引用表指向引用表。','总览省略人员、审计、自引用等连线；详细外键与可空性见分域图和附录。'])
    return f

def er_identity():
    f=Figure('er-identity',510)
    f.er('app_user',10,10,195,'用户',['id','username','platform_role','status','reviewed_by'])
    f.er('merchant',249,10,195,'商户',['id','merchant_code','name','status','reviewed_by'])
    f.er('merchant_member',10,191,195,'商户成员',['id','merchant_id','user_id','role_code','status'],['UQ (merchant_id, user_id)'])
    f.er('store',249,191,195,'门店',['id','merchant_id','store_code','name','status'])
    f.er('audit_log',113,369,228,'审计日志',['id','actor_user_id','scope_merchant_id'],['操作前后快照 · 请求编号'])
    f.fk('app_user','merchant_member','user_id',[(107,126),(107,191)],'1:N',(128,158))
    f.fk('merchant','store','merchant_id',[(346,126),(346,191)],'1:N',(370,158))
    f.fk('merchant','merchant_member','merchant_id',[(249,83),(227,83),(227,222),(205,222)],'1:N',(227,157))
    f.fk('app_user','merchant','reviewed_by',[(205,41),(249,41)],'0..1:N',(227,28))
    f.fk('app_user','audit_log','actor_user_id',[(10,75),(3,75),(3,403),(113,403)])
    f.fk('merchant','audit_log','scope_merchant_id',[(444,75),(450,75),(450,403),(341,403)])
    f.label(65,390,'0..1:N'); f.label(394,390,'0..1:N')
    f.note(489,['PK 主键；FK 外键；? 可空。用户 reviewed_by 为自引用审核人。'])
    return f

def er_funds():
    f=Figure('er-funds',620)
    f.er('business_order',10,10,195,'业务订单',['id','store_id','consumer_id','created_by','client_request_id','status'])
    f.er('order_item',249,10,195,'成交明细',['id','order_id','line_no','quantity','unit_price_cent'],['UQ (order_id, line_no)'])
    f.er('payment_attempt',10,188,195,'支付尝试',['id','order_id','out_trade_no','client_request_id','status','is_primary'])
    f.er('payment_notification',249,188,195,'通知观察记录',['id','payment_id','notify_id','signature_valid','handling_result'])
    f.er('refund_request',10,364,195,'退款申请',['id','payment_id','out_request_no','amount_cent','applicant_id','reviewed_by','status'])
    f.er('transaction_entry',249,364,195,'不可变资金事实',['id','payment_id','refund_id','entry_type','amount_cent','channel_occurred_at','recorded_at'])
    f.fk('business_order','order_item','order_id',[(205,72),(249,72)],'1:N',(227,59))
    f.fk('business_order','payment_attempt','order_id',[(108,139),(108,188)],'1:N',(130,165))
    f.fk('payment_attempt','payment_notification','payment_id',[(205,244),(249,244)],'0..1:N',(227,230))
    f.fk('payment_attempt','refund_request','payment_id',[(108,317),(108,364)],'1:N',(131,342))
    f.fk('refund_request','transaction_entry','refund_id',[(205,443),(249,443)],'0..1:0..1',(227,428))
    f.fk('payment_attempt','transaction_entry','payment_id',[(205,300),(227,300),(227,346),(346,346),(346,364)],'0..1:0..1',(290,346))
    f.note(538,['资金事实仅有一个非空来源：RECEIPT → payment_id，REFUND → refund_id。','筛选唯一索引保证一笔支付或退款最多对应一条资金事实。','订单 store_id → store；consumer_id / created_by → app_user。','退款 applicant_id / reviewed_by → app_user；? 表示该外键可空。','所有连线均为实际外键；每订单最多一个 is_primary=1 的支付。'])
    return f

def er_reconciliation():
    f=Figure('er-reconciliation',602)
    f.er('reconciliation_batch',10,10,195,'批次与自动任务',['id','merchant_id','statement_source_batch_id','baseline_batch_id','created_by','acquisition_owner'])
    f.er('statement_line',249,10,195,'原始账单行',['id','import_batch_id','line_no','event_type','amount_cent'],['UQ (import_batch_id, line_no)'])
    f.er('reconciliation_result',10,221,195,'逐笔对账结果',['id','batch_id','entry_id','statement_line_id','source_event_key','classification'],['UQ (batch_id, result_key)'])
    f.er('exception_task',249,221,195,'差错工单',['id','origin_result_id','latest_result_id','assignee_id','submitted_by','reviewed_by','status'])
    f.fk('reconciliation_batch','statement_line','import_batch_id',[(205,60),(249,60)],'1:N',(227,47))
    f.fk('reconciliation_batch','reconciliation_result','batch_id',[(107,139),(107,221)],'1:N',(130,182))
    f.fk('statement_line','reconciliation_result','statement_line_id',[(346,139),(346,178),(173,178),(173,221)],'0..1:N',(259,178))
    f.fk('reconciliation_result','exception_task','origin_result_id',[(205,268),(249,268)],'1:0..1',(227,255))
    f.fk('reconciliation_result','exception_task','latest_result_id',[(205,330),(249,330)],'1:N',(227,317))
    f.box('fact',10,424,195,58,'transaction_entry',['entry_id 引用唯一资金事实'],True)
    f.fk('transaction_entry','reconciliation_result','entry_id',[(107,424),(107,363)],'0..1:N',(132,400))
    f.box('actor',249,424,195,58,'app_user / merchant',['人员与业务归属引用'],True)
    f.note(510,['批次的 statement_source_batch_id 与 baseline_batch_id 为可空自引用。','merchant_id → merchant；created_by / acquisition_owner → app_user。','工单 assignee_id / submitted_by / reviewed_by → app_user，均可空。','PK 主键；FK 外键；? 可空。结案不修改旧对账分类。','自动任务复用 reconciliation_batch，数据库不存在独立 auto_job 表。'])
    return f

def payment_flow():
    f=Figure('payment-flow',521)
    f.box('a',10,10,265,61,'提交待付款订单',['UUID 幂等 · 锁定订单 · 复用支付号'],True)
    f.box('b',10,101,265,61,'领取派发租约，调用渠道',['90 秒租约与令牌；网络在数据库事务外'])
    f.box('c',10,192,265,61,'付款结果确认',['用户主动查询 / 定时补查 / 合法通知触发'])
    f.box('d',10,283,265,61,'核对金额与渠道交易身份',['金额一致且成功 → 调用支付确认过程'])
    f.box('e',10,374,265,76,'本地事务确认成功',['更新支付与订单 · 写唯一收款事实','已确认结果重入不重复入账'],True)
    for a,b in [(71,101),(162,192),(253,283),(344,374)]:f.path([(142,a),(142,b)])
    f.box('unknown',302,101,141,75,'结果未知',['保留原交易号','不认定失败','优先查原交易'],size=9.1)
    f.path([(275,130),(302,130)]); f.label(289,116,'超时',8.6)
    f.path([(373,176),(373,218),(275,218)])
    f.box('diff',302,283,141,75,'金额 / 额外收款差异',['金额不符：保存证据','额外成功：仍保留流水','两者进入差错处理'],size=9)
    f.path([(275,313),(302,313)])
    f.note(474,['未知结果 ≠ 明确失败；重试始终保留原支付身份。','回调必须验签、核对主体与金额；真实公网通知尚待联调。'])
    return f

def refund_flow():
    f=Figure('refund-flow',533)
    f.box('a',10,10,265,61,'申请退款 · PENDING_REVIEW',['成功支付 · 正整数金额 · UUID 幂等'],True)
    f.box('b',10,104,265,76,'独立审批与额度预留',['审批人 ≠ 申请人；锁定原支付再锁申请','已成功 + 预留 + 本次金额 ≤ 原支付'])
    f.box('c',10,216,265,61,'APPROVED → PROCESSING',['领取租约后调用渠道；不持数据库锁'])
    f.box('d',10,313,265,61,'成功确认 · SUCCEEDED',['事务内写唯一 REFUND 事实'])
    f.box('e',10,410,265,61,'更新可退额与统计',['退款按实际发生日统计，不删除原收款'],True)
    for a,b in [(71,104),(180,216),(277,313),(374,410)]:f.path([(142,a),(142,b)])
    f.box('reject',302,104,141,76,'审批未通过',['本人审批 / 超额','状态不允许','拒绝，且不调用渠道'],size=9.1)
    f.path([(275,142),(302,142)])
    f.box('unknown',302,216,141,103,'UNKNOWN / 恢复',['保留预留额度','查询原退款号','仅未执行时重发','请求号与金额不变'],size=9.1)
    f.path([(275,248),(302,248)])
    f.path([(373,319),(373,345),(275,345)])
    f.note(501,['计入额度的状态：APPROVED、PROCESSING、UNKNOWN、SUCCEEDED。'])
    return f

def reconciliation_flow():
    f=Figure('reconciliation-flow',578)
    f.box('a',10,10,265,74,'上传或自动获取账单',['XLSX / XML / 渠道 CSV / ZIP','保留原件 · SHA256 · 期间与完整性'],True)
    f.box('b',10,116,265,74,'预览与校验',['字段 / 金额 / 环境 / 时间 / 商户','保留重复行；未知归属交平台识别'])
    f.box('c',10,222,265,74,'导入输入集',['文件 + 解释参数幂等 · 事务锁','原始账单行不可变；重复上传复用'])
    f.box('d',10,328,265,74,'一致时点逐笔核对',['缺键 / 重复先分类，再 FULL OUTER JOIN','完整账期才能判定 LOCAL_ONLY'])
    f.box('e',10,434,265,74,'保存结果与差错关联',['8 类核对结果；差错进入工单队列','重跑生成新批次，保留基线和历史'],True)
    for a,b in [(84,116),(190,222),(296,328),(402,434)]:f.path([(142,a),(142,b)])
    f.box('template',302,10,141,89,'沙箱模板边界',['保存并校验原件','TEMPLATE_ONLY','不导入业务账单','不生成差错'],size=9)
    f.path([(275,47),(302,47)])
    f.box('error',302,116,141,74,'校验未通过',['返回具体错误行','不伪造完整输入','自动任务停止导入'],size=9)
    f.path([(275,151),(302,151)])
    f.box('compare',302,434,141,74,'历史比较',['NEW / RESOLVED','CHANGED','UNCHANGED'],size=9.1)
    f.path([(275,471),(302,471)])
    f.note(540,['完整性 UNKNOWN 且本地有、渠道缺行：PENDING_CONFIRMATION。','人工结案与客观核对结果分别保存，不能用结案掩盖历史差异。'])
    return f

def case_state():
    f=Figure('case-state',438)
    states=[(10,'OPEN · 待分派','差错结果建立或关联工单'),(90,'ASSIGNED · 已分派','有效处理人接收任务'),(170,'PROCESSING · 处理中','分析、结论与证据留存'),(250,'PENDING_REVIEW · 待复核','当前处理人提交完整结论'),(330,'CLOSED · 已结案','独立复核通过且证据满足结案类型')]
    for i,(y,title,desc) in enumerate(states):
        f.box(str(i),25,y,290,54,title,[desc],i in (0,4),size=9.1)
        if i: f.path([(170,states[i-1][0]+54),(170,y)])
    f.path([(315,277),(413,277),(413,197),(315,197)])
    f.label(374,232,'退回处理',9.3)
    f.path([(315,197),(355,197),(355,117),(315,117)])
    f.label(376,157,'重新分派',9.3)
    f.note(408,['修改须匹配 ROWVERSION；复核人不得等于 submitted_by。'])
    return f

def analytics_lineage():
    f=Figure('analytics-lineage',472)
    f.box('a',10,10,433,61,'共同口径：权限范围 + 业务期间 + 金额范围 + asOf',['发生日：channel_occurred_at；该时点已知事实：recorded_at ≤ asOf'],True,size=9.1)
    f.box('b',10,104,204,61,'组织层级钻取',['商户 → 门店 → 资金流水'])
    f.box('c',239,104,204,61,'时间层级钻取',['年 → 月 → 日 → 资金流水'])
    f.path([(112,71),(112,104)]);f.path([(341,71),(341,104)])
    f.path([(112,165),(112,195),(226,195),(226,221)]);f.path([(341,165),(341,195),(226,195)])
    f.box('fact',10,222,433,62,'同一组已确认资金事实',['收款与退款分别求和；净收款 = 收款 − 退款'],True)
    f.box('assoc',10,320,433,62,'关联追溯',['工单 → 对账结果 → 账单行 / 流水 → 支付退款 → 订单 → 门店 / 商户'])
    f.path([(226,284),(226,320)])
    f.note(412,['下钻继承全部筛选；时间子范围与父范围求交，不能放大查询范围。','汇总、图表、明细与导出沿用同一口径；重跑结果先去重再聚合。','经营聚合、工单状态与风险统计分别取数，避免一对多联接重复计数。'])
    return f

def two_clock_timeline():
    f=Figure('two-clock-timeline',330)
    f.text(10,17,'说明性时间示例 · 金额与日期用于解释统计口径',9,color=MUTED)
    f.text(10,57,'真实发生',10,True,color=BLUE); f.text(10,155,'系统获知',10,True,color=BLUE)
    f.path([(82,78),(435,78)],arrow=True,color=LINE)
    f.path([(82,176),(435,176)],arrow=True,color=LINE)
    for x in [125,325]:
        f.path([(x,82),(x,90)],arrow=False,dashed=True,color=LINE)
        f.path([(x,132),(x,172)],arrow=False,dashed=True,color=LINE)
    for x in [220,410]:
        f.path([(x,78),(x,172)],arrow=False,dashed=True,color=LINE)
    f.rect(121,74,8,8,BLUE,BLUE,4); f.rect(321,74,8,8,BLUE,BLUE,4)
    f.rect(216,172,8,8,BLUE,BLUE,4); f.rect(406,172,8,8,BLUE,BLUE,4)
    f.lines(125,102,['D 日 23:58','收款 100.00 元'],size=9.2,gap=14,anchor='middle')
    f.lines(325,102,['D+1 日 09:00','退款 30.00 元'],size=9.2,gap=14,anchor='middle')
    f.lines(220,201,['D+1 日 00:03','收款查询恢复'],size=9.2,gap=14,anchor='middle')
    f.lines(410,201,['09:01','退款确认'],size=9.2,gap=14,anchor='middle')
    f.rect(10,245,433,66,fill=PALE)
    f.lines(20,261,['完整获知后：D 日净额 +100.00 元；D+1 日净额 −30.00 元。','若 asOf 早于获知时间，该截面不包含尚未入账的事实。','收付款发生日与系统入账时刻分开保存，迟到确认不改变实际发生日。'],size=9.2,gap=18)
    return f

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    fs=[architecture,function_map,er_overview,er_identity,er_funds,er_reconciliation,payment_flow,refund_flow,reconciliation_flow,case_state,analytics_lineage,two_clock_timeline]
    manifest=[factory().save() for factory in fs]
    (OUT/'figures-manifest.json').write_text(json.dumps({'schema_captured_at': '2026-11-04T17:55:41.2063506+08:00','font':'Microsoft YaHei','figures':manifest},ensure_ascii=False,indent=2),encoding='utf-8')
    # Contact sheets only aid review; individual 300 dpi files are the report assets.
    for start in range(0,len(manifest),4):
        sheet=Image.new('RGB',(1200,1700),'#e6eaf0');d=ImageDraw.Draw(sheet)
        for j,m in enumerate(manifest[start:start+4]):
            im=Image.open(OUT/f"{m['name']}.png");im.thumbnail((580,790))
            x=(j%2)*600+(600-im.width)//2;y=(j//2)*850+35
            sheet.paste(im,(x,y));d.text(((j%2)*600+12,(j//2)*850+10),m['name'],fill='black')
        sheet.save(OUT/f'qa-contact-{start//4+1}.png')
    print(json.dumps(manifest,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
