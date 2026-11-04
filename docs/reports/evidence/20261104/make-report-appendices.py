import json
from pathlib import Path

root = Path(__file__).resolve().parents[4]
db = json.loads((Path(__file__).parent / 'schema-summary.json').read_text(encoding='utf-8-sig'))['databases'][0]
tables = {t['name']: t for t in db['tables']}
order = ['app_user','merchant','merchant_member','store','business_order','order_item','payment_attempt','payment_notification','refund_request','transaction_entry','reconciliation_batch','statement_line','reconciliation_result','exception_task','audit_log']
names = dict(zip(order, ['用户账号','商户','商户成员','门店','业务订单','订单明细','支付尝试','支付通知','退款申请','资金事实','对账批次与获取任务','原始账单行','对账结果','差错工单','审计日志']))
# Column meanings are written against the SQL definitions and the actual service use.
meanings = {
'app_user': ['账号主键','唯一登录名','密码哈希，不保存明文密码','显示姓名','平台身份，默认MEMBER','账号审核及启停状态','审核该账号的用户','审核完成时间','审核意见','账号创建时间','账号最近更新时间','并发修改版本标记'],
'merchant': ['商户主键','唯一商户编号','正式商户名称','正式经营类别','正式联系信息','商户审核及启停状态','待审修订名称','待审修订经营类别','待审修订联系信息','商户资料审核人','最近审核时间','审核意见','商户创建时间','资料最近更新时间','并发修改版本标记'],
'merchant_member': ['成员关系主键','所属商户','成员对应账号','商户内角色','成员关系是否有效','加入商户时间'],
'store': ['门店主键','所属商户','商户内唯一门店编号','门店名称','门店位置说明','门店启停状态','门店创建时间','门店最近更新时间','并发修改版本标记'],
'business_order': ['订单主键','唯一订单业务编号','收款门店','关联消费者，可不指定','订单创建人','创建操作幂等标识','收款事项标题','订单备注','订单业务状态','订单创建时间','订单最近更新时间','并发修改版本标记'],
'order_item': ['明细行主键','所属订单','订单内唯一行号','成交时的项目名称','项目分类','项目数量，须为正整数','成交单价，单位分，允许零'],
'payment_attempt': ['支付尝试主键','对应订单','预下单操作幂等标识','商户交易号，跨渠道请求追踪','渠道代码，默认ALIPAY','DEMO或ALIPAY_SANDBOX','渠道应用标识','渠道收款方标识','渠道返回的交易号','支付尝试状态','是否为订单主支付','下次自动查询时刻','已执行的结果查询次数','渠道确认的支付发生时刻','支付尝试创建时间','支付尝试最近更新时间','当前可用支付二维码内容','最近渠道或业务结果代码','面向用户的最近结果说明','当前建议动作，如查询或重试','预下单尝试次数','本轮发送的排他租约令牌','发送租约失效时刻'],
'payment_notification': ['通知记录主键','能够识别时关联支付尝试','渠道通知标识，允许缺失','通知原始内容的受控文件位置','通知内容SHA-256摘要','签名校验是否通过','通知处理结果或拒绝原因','系统接收通知的时刻','当前接口请求追踪标识'],
'refund_request': ['退款申请主键','原支付尝试','申请操作幂等标识','渠道退款请求号','申请退款金额，单位分','申请退款的业务原因','申请、发送及确认状态','退款申请人','退款审核人','退款审核时间','审核意见','已执行的退款查询次数','下次自动查询时刻','渠道确认的退款发生时刻','本轮退款发送租约令牌','退款发送租约失效时刻','退款申请创建时间','退款申请最近更新时间','并发修改版本标记'],
'transaction_entry': ['资金事实主键','唯一流水业务编号','唯一事件键：P:交易号或R:退款号','收款或退款方向','收款来源支付；退款时为空','退款来源申请；收款时为空','已确认金额，单位分，始终为正','渠道实际发生时刻','本系统记录资金事实的时刻','确认依据的受控文件位置'],
'reconciliation_batch': ['批次或任务主键','唯一批次业务编号','商户范围或平台未归属范围','所属商户；未归属范围为空','账期起点，包含该时刻','账期终点，不包含该时刻','文件、查询、种子或自动任务来源','重跑时引用的原始输入批次','重跑比较使用的基线批次','账单原件的受控文件位置','原件SHA-256摘要','归属、账期等导入选项的摘要','账单完整性声明','导入或对账批次状态','本次对账观察时刻','本次对账开始时刻','批次或获取任务结束时刻','批次或任务创建人','批次或任务创建时间','环境、应用与账日组合的任务摘要键','自动获取任务状态','自动任务使用的渠道环境','自动任务使用的应用标识','本轮获取已尝试次数，最多4次','补跑代次；申请补跑时递增','当前执行者持有的租约令牌','自动任务执行租约失效时刻','下次自动获取或重试时刻','任务进度、结果或失败说明','已保存原件的格式，如xml、zip、csv','任务结果JSON，含子批次或错误信息','是否作为每日获取的启用配置','当前授权执行者；权限失效可暂停任务'],
'statement_line': ['账单行主键','原始导入批次','原件内行号，同批次唯一','渠道记录的收款或退款类型','商户交易号，可缺失','渠道交易号，可缺失','退款请求号，可缺失','渠道记录金额，单位分','渠道记录的发生时刻','该原始行内容的受控文件位置'],
'reconciliation_result': ['观察结果主键','所属对账观察批次','同批次内唯一结果键','规范事件键或问题行追踪键','命中的本地资金事实，可空','命中的渠道账单行，可空','八种对账分类之一','分类的具体原因代码','该结果被检测的时刻'],
'exception_task': ['差错工单主键','唯一工单业务编号','范围、事件与分类构成的去重键','最初异常结果，不可改写','当前关联的最新观察结果','当前经办人','工单处理阶段','工单标题','异常起因描述','经办分析正文','处理或复核结论说明','结案类型代码','经办证据附件的受控位置','提交分析并申请复核的人员','最近复核人员','要求完成的截止时间','最近结案时间','工单创建时间','工单最近更新时间','并发修改版本标记'],
'audit_log': ['审计记录主键','审计事件类别','操作用户；系统事件可空','事件涉及的商户范围','多态业务对象类型','对应类型的业务对象编号','操作名称或动作代码','操作结果代码','可读事件摘要','变更前字段的JSON快照','变更后字段或结果的JSON快照','审计事件发生时刻','接口请求追踪标识，可空'],
}

def typ(c):
    t = c['type_name'].upper()
    if t in ('VARCHAR','NVARCHAR','CHAR','NCHAR','VARBINARY','BINARY'):
        size = c['max_length']
        return f"{t}({'MAX' if size == -1 else size//2 if t.startswith('N') else size})"
    if t in ('DATETIME2','TIME','DATETIMEOFFSET'): return f"{t}({c['scale']})"
    if t in ('DECIMAL','NUMERIC'): return f"{t}({c['precision']},{c['scale']})"
    if t == 'TIMESTAMP': return 'ROWVERSION'
    return t

def keycols(i):
    return ', '.join(c['name'] + (' DESC' if c['is_descending_key'] else '') for c in sorted(i['columns'],key=lambda c:c['key_ordinal']) if not c['is_included_column'])

out = ['# 附录A 完整字段字典','',
'本附录列出最终数据库的15张表和207个字段。两套运行数据库结构一致。所有表均以 id 为 BIGINT IDENTITY 主键；字符串长度按字符数列示，NVARCHAR(MAX) 为可变长Unicode文本，ROWVERSION 为8字节自动版本标记。金额单位均为分，DATETIME2(3) 按UTC约定保存。', '',
'“非空／可空”对应列的 NULL 属性；PK 表示主键，UQ 表示单列唯一约束，UK 表示组合唯一约束中的一列，FK 表示外键。组合约束须将所列字段一起理解，不能据此认定每列分别唯一。各表下列出完整的唯一约束与外键，全部32个外键的更新和删除动作均为 NO_ACTION。筛选唯一索引另见附录B。', '']
field_count = 0
for n,name in enumerate(order,1):
    t=tables[name]; cols=t['columns']; desc=meanings[name]
    assert len(cols)==len(desc),(name,len(cols),len(desc))
    uq=[i for i in t['indexes'] if i['is_unique_constraint']]
    single={i['columns'][0]['name'] for i in uq if len(i['columns'])==1}
    compound={c['name'] for i in uq if len(i['columns'])>1 for c in i['columns']}
    fks=[f for f in db['foreign_keys'] if f['from_table']==name]
    fkcols={c['from_column'] for f in fks for c in f['columns']}
    out += [f'## A.{n} {names[name]}（{name}）','',f'**表A-{n} {names[name]}字段字典**','', '| 字段 | 类型 | 空值与键 | 含义 |','|---|---|---|---|']
    for c,d in zip(cols,desc):
        tags=['可空' if c['is_nullable'] else '非空']
        if c['is_primary_key']: tags.append('PK')
        if c['name'] in single: tags.append('UQ')
        if c['name'] in compound: tags.append('UK')
        if c['name'] in fkcols: tags.append('FK')
        out.append(f"| {c['name']} | {typ(c)} | {'；'.join(tags)} | {d} |")
        field_count += 1
    out += ['', '主键：id。唯一约束：'+('；'.join('('+keycols(i)+')' for i in uq) if uq else '无额外唯一约束')+'。', '']
    if fks:
        out += ['外键：'+'；'.join(', '.join(c['from_column'] for c in f['columns'])+' → '+f['to_table']+'('+', '.join(c['to_column'] for c in f['columns'])+')' for f in fks)+'。','']
    else: out += ['外键：无。','']
    if name=='reconciliation_batch': out += ['source_type=AUTO_JOB 时，status 保持任务容器的 IMPORTING，执行进度使用 acquisition_state。普通账单批次的生命周期使用 status。acquisition_owner 是授权执行账号，acquisition_token 是工作者租约，两者含义不同。','']
    if name=='audit_log': out += ['object_type 与 object_id 共同定位不同类型的业务对象；object_id 不是指向某一固定表的外键。快照保存当时内容，不代表当前数据行。','']
assert field_count==207
out += ['# 附录B 显式索引与CHECK约束','',
'主键和唯一约束产生34个索引，另有19个显式业务索引，合计53个B树索引。本附录列出这19个显式索引；均为非聚集索引。键列未标注方向时为升序；INCLUDE列仅覆盖查询，不参与唯一性判断。全文索引独立于本表统计。','',
'## B.1 显式业务索引','', '**表B-1 六个筛选唯一索引**','', '| 索引 | 所在表及键列 | WHERE条件 |','|---|---|---|']
explicit=[]
for name in order:
    for i in tables[name]['indexes']:
        if not i['is_primary_key'] and not i['is_unique_constraint']: explicit.append((name,i))
assert len(explicit)==19
for name,i in explicit:
    if i['is_unique']: out.append(f"| {i['name']} | {name}({keycols(i)}) | {i.get('filter_definition','无')} |")
out += ['', '**表B-2 十三个非唯一业务索引**','', '| 索引与所在表 | 键列 | INCLUDE列 | WHERE条件 |','|---|---|---|---|']
for name,i in explicit:
    if not i['is_unique']:
        inc=', '.join(c['name'] for c in i['columns'] if c['is_included_column']) or '无'
        out.append(f"| {i['name']}（{name}） | {keycols(i)} | {inc} | {i.get('filter_definition','无')} |")
out += ['', '定义示例：IX_refund_pending 的键为 (status, next_query_at)，包含 payment_id、amount_cent，允许不同退款记录具有相同的状态和查询时间。UX_payment_primary 则只覆盖 is_primary=1 的记录，约束每个订单至多有一条主支付，不限制订单具有多次普通支付尝试。','',
'## B.2 状态与类型CHECK约束','',
'数据库实际共有27个CHECK约束。以下按业务作用编号B-C01至B-C27，编号用于阅读对应，不代替SQL Server中的约束名称。表达式将同列的多个等值OR合并为等价的IN写法，保留原有允许值及空值语义；行列是否允许NULL仍以附录A为准。','',
'**表B-3 状态与类型约束（18项）**','', '| 编号 | 表与字段 | CHECK表达式 |','|---|---|---|']
enum_specs=[
('app_user','platform_role',['MEMBER','PLATFORM_FINANCE','ADMIN','AUDITOR']),
('app_user','status',['PENDING','ACTIVE','REJECTED','DISABLED']),
('merchant','status',['PENDING','ACTIVE','REJECTED','DISABLED']),
('merchant_member','role_code',['OWNER','CASHIER','FINANCE','REVIEWER']),
('merchant_member','status',['ACTIVE','DISABLED']),
('store','status',['ACTIVE','DISABLED']),
('business_order','status',['DRAFT','WAITING_PAYMENT','PAID','CANCELED','PAYMENT_EXCEPTION']),
('payment_attempt','channel_env',['DEMO','ALIPAY_SANDBOX']),
('payment_attempt','status',['CREATED','PENDING','SUCCEEDED','CLOSED','FAILED','UNKNOWN']),
('refund_request','status',['PENDING_REVIEW','APPROVED','PROCESSING','SUCCEEDED','REJECTED','FAILED','UNKNOWN']),
('transaction_entry','entry_type',['RECEIPT','REFUND']),
('statement_line','event_type',['RECEIPT','REFUND']),
('reconciliation_batch','scope_type',['MERCHANT','PLATFORM_UNASSIGNED']),
('reconciliation_batch','completeness',['UNKNOWN','DECLARED_COMPLETE','PROVIDER_COMPLETE']),
('reconciliation_batch','status',['IMPORTING','READY','RUNNING','COMPLETED','FAILED']),
('reconciliation_batch','source_type',['XLSX','XML','ALIPAY_CSV','ALIPAY_ZIP','TRADE_QUERY','DEMO_SEED','AUTO_JOB']),
('reconciliation_result','classification',['MATCHED','CHANNEL_ONLY','LOCAL_ONLY','AMOUNT_MISMATCH','BILL_DUPLICATE','KEY_UNRESOLVED','PENDING_CONFIRMATION','EXTRA_SUCCESS']),
('exception_task','status',['OPEN','ASSIGNED','PROCESSING','PENDING_REVIEW','CLOSED']),
]
for n,(name,col,values) in enumerate(enum_specs,1):
    literal=', '.join("'"+v+"'" for v in values)
    out.append(f'| B-C{n:02} | {name}.{col} | {col} IN ({literal}) |')
out += ['', '## B.3 数量、金额与时间CHECK约束','', '**表B-4 数值及账期约束（6项）**','', '| 编号 | 所在表 | CHECK表达式及含义 |','|---|---|---|',
'| B-C19 | order_item | quantity > 0；项目数量为正 |',
'| B-C20 | order_item | unit_price_cent >= 0；单价允许零 |',
'| B-C21 | refund_request | amount_cent > 0；退款申请金额为正 |',
'| B-C22 | transaction_entry | amount_cent > 0；记账方向由类型另行表达 |',
'| B-C23 | statement_line | amount_cent > 0；渠道行金额为正 |',
'| B-C24 | reconciliation_batch | period_end > period_start；账期非空且顺序正确 |','',
'金额列CHECK不承担跨行退款总额控制；累计额度由函数和审批事务共同校验。账期使用左闭右开的范围，结束时刻不计入该批次。','',
'## B.4 跨字段CHECK约束','',
'B-C25约束 reconciliation_batch 的范围与商户是否指定相一致。它允许未归属账单进入平台处理范围，避免把缺少商户归属的记录归入任意商户。','',
'```sql',
"CHECK ((scope_type='MERCHANT' AND merchant_id IS NOT NULL)",
"    OR (scope_type='PLATFORM_UNASSIGNED' AND merchant_id IS NULL))",
'```','',
'B-C26约束 transaction_entry 的流水来源互斥。收款必须关联支付，退款必须关联退款申请，不能两者同时存在或同时为空。','',
'```sql',
"CHECK ((entry_type='RECEIPT' AND payment_id IS NOT NULL",
'        AND refund_id IS NULL)',
"    OR (entry_type='REFUND' AND payment_id IS NULL",
'        AND refund_id IS NOT NULL))',
'```','',
'B-C27为 reconciliation_batch 的 CK_batch_acquisition。普通批次不设置获取任务键；自动获取任务需要完整的环境、应用、执行状态及有效次数，保持任务容器与普通导入批次的区别。该约束不强制普通批次的全部 acquisition_* 字段均为空。','',
'```sql',
"CHECK ((source_type<>'AUTO_JOB' AND acquisition_key IS NULL)",
"    OR (source_type='AUTO_JOB'",
"        AND scope_type='PLATFORM_UNASSIGNED'",
'        AND merchant_id IS NULL',
"        AND status='IMPORTING'",
'        AND acquisition_key IS NOT NULL',
'        AND acquisition_env IS NOT NULL',
'        AND acquisition_app IS NOT NULL',
'        AND acquisition_state IS NOT NULL',
"        AND acquisition_state IN (",
"          'QUEUED','FETCHING','VALIDATING','RECONCILING',",
"          'RETRY_WAIT','SUCCEEDED','FAILED','UNCONFIGURED',",
"          'UNSUPPORTED','TEMPLATE_ONLY')",
'        AND acquisition_attempts BETWEEN 0 AND 4',
'        AND acquisition_generation>0))',
'```','',
'acquisition_state=TEMPLATE_ONLY 表示已取得并保存沙箱模板，不等同于真实交易账单核对成功。工单的结案证据、退款申请与审核人分离等规则还依赖存储过程及触发器，未将这些跨行规则虚列为CHECK约束。','']
dest=root/'docs/reports/implementation-assets/drafts/appendices.md'
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text('\n'.join(out),encoding='utf-8')
print(json.dumps({'file':str(dest),'fields':field_count,'explicit_indexes':len(explicit),'checks':27,'characters':sum(len(x) for x in out)},ensure_ascii=False))
