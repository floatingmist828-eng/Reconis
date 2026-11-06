"""Assemble the final report source from reviewed chapter fragments."""
from pathlib import Path
import re

B=Path(__file__).resolve().parent
D=B/'drafts'
opening,closing=(D/'opening-closing.md').read_text(encoding='utf-8-sig').split('<!-- CLOSING -->')
db=(D/'database-chapters.md').read_text(encoding='utf-8-sig')
for n,name,title in [('4-1','er-overview','最终实体关系总览'),('4-2','er-identity','用户、商户、成员与门店关系'),('4-3','er-funds','订单、支付、通知与资金关系'),('4-4','er-reconciliation','账单、核对结果与工单关系')]:
    db=db.replace(f'[图{n} {name}]',f'![图{n} {title}](implementation-assets/{name}.png)')
biz=(D/'business-chapters.md').read_text(encoding='utf-8-sig')
# The business fragment uses one additional heading level.
biz=re.sub(r'^(#{2,4}) ',lambda m:m[1][1:]+' ',biz,flags=re.M)
biz=re.sub(r'图 (\d+-\d+)',r'图\1',biz)
# Images supply their own captions; remove repeated plain lines.
biz=re.sub(r'(!\[[^\n]+\]\([^\n]+\))\n\n图[^\n]+\n',r'\1\n',biz)
test=(D/'test-chapter.md').read_text(encoding='utf-8-sig').replace('# 第9章 测试与结果分析','# 9 测试与结果分析')
test=re.sub(r'本轮任务103也有明确边界：.*?在以上范围内，各项测试均达到预期。.*?后续验证入口，避免以局部成功替代完整渠道或长期运行结论。',
    '任务103为00:09已有成功任务，17:58触发仅复用，未新增渠道下载。真实公网回调和业务日账单仍待验证，其他运行限制见第11.2节。',test,flags=re.S)

def before(text,heading,extra):
    if heading not in text: raise ValueError(heading)
    return text.replace(heading,extra+'\n\n'+heading,1)

biz=before(biz,'## 6.2', '''图6-5为当前订单列表按本轮唯一前缀筛选的结果，包含两笔已支付订单和一笔取消验证订单。图6-6展开订单266：应收100元，已支付且部分退款，时间线分别保存100元收款与30元退款，剩余可退70元。

![图6-5 验证订单列表及状态](implementation-assets/screenshots/04-orders-report.png)

![图6-6 订单266的资金状态与时间线](implementation-assets/screenshots/05-order-detail-report.png)''')
biz=before(biz,'## 6.4', '''图6-7同时保留成功退款与两项拒绝场景的最终状态。30元申请成功；70.01元超额申请和1元自审验证申请在测试后的清理流程中退回。截图中的“已退回”是保存后的业务状态，拒绝发生时的HTTP结果另见表9-3，不能从最终列表反推每一次接口响应。

![图6-7 部分退款成功与异常申请记录](implementation-assets/screenshots/06-refunds-report.png)''')
biz=before(biz,'## 6.5', '''图6-8使用本轮交易导出的XML进行预览，3行全部有效、无重复键与错误行；该截图保留“未知/不完整”声明，只说明记录格式与归属校验通过，并未再次导入。图6-9为已声明完整的差异批次127：100/101元和50/51元各形成金额差异，30元退款匹配。图6-10以127为基线比较修订输入批次130，两笔收款显示RESOLVED，退款显示UNCHANGED。这组变化证明修订输入产生新结果，原批次并未被覆盖。

![图6-8 教学账单导入预览与校验](implementation-assets/screenshots/09-import-preview-report.png)

![图6-9 原批次127的两项金额差异](implementation-assets/screenshots/07-reconciliation-report.png)

![图6-10 修订批次130与基线127的逐项比较](implementation-assets/screenshots/08-compare-report.png)''')
# Place the closure evidence before the following chapter.
biz=before(biz,'# 7 ', '''图6-11显示工单115的结案状态、最新核对与复核人员；原始差异为50/51元，详情当前显示的50/50元来自最新匹配结果，二者不是同一个观察时点。图6-12记录复核退回、再次提交和独立结案的状态变化。图6-13沿同一工单展开订单267、成功支付和50元资金事实，说明问题能够追溯到具体业务来源。

![图6-11 工单115的结案状态、最新核对与独立复核](implementation-assets/screenshots/10-case-closed-report.png)

![图6-12 退回、重提与结案的审核历史](implementation-assets/screenshots/11-case-history-report.png)

![图6-13 工单关联的订单、支付和资金事实](implementation-assets/screenshots/12-evidence-chain-report.png)''')
biz=before(biz,'## 7.2', '''图7-6检索“修订账单”，当前返回17项可见的已解决案例，其中包含本轮工单115。返回数量属于截图时的数据状态，不是固定演示常量。

![图7-6 中文全文检索及本轮结案记录](implementation-assets/screenshots/16-fulltext-report.png)''')
biz=before(biz,'## 7.3', '''图7-3至图7-5展示同一统计范围的商户、门店和最终流水。三个资金事件分别为100元收款、50元收款和30元退款，净额始终为120元；面包屑保留钻取路径。

![图7-3 商户范围的交易统计](implementation-assets/screenshots/13-analytics-merchant-report.png)

![图7-4 门店层的同口径汇总](implementation-assets/screenshots/14-analytics-store-report.png)

![图7-5 最细粒度的三笔资金事件](implementation-assets/screenshots/15-analytics-detail-report.png)''')
biz=before(biz,'## 8.2','''图8-1为当前登录入口。用户可通过“注册账号”进入申请表单，提交后仍须经管理员审核方可登录。

![图8-1 登录与账号申请入口](implementation-assets/screenshots/02-login-report.png)
''')
opening=before(opening,'订单、退款和商户等条目采用统一的图文结构','''图2-2为平台财务工作台。选定本轮商户后，摘要显示150元收款、30元退款与120元净收款，角色入口与当前业务范围同时可见。

![图2-2 平台财务工作台与限定商户摘要](implementation-assets/screenshots/03-dashboard-report.png)''')
biz=biz.replace('文件存放在环境独立目录，','''图8-3展示管理员的访问与操作审计。查询条件和动作结果支持进一步追踪，请求编号保存在审计明细中。

![图8-3 访问与操作审计查询](implementation-assets/screenshots/17-audit-report.png)

文件存放在环境独立目录，''',1)
appendix=(D/'appendices.md').read_text(encoding='utf-8-sig')
appendix+='''

# 附录C 运行、复现与证据索引

## C.1 启动与验证入口

已配置本地数据库和环境文件后，在项目根目录运行下列命令。密钥、数据库密码和教学账号凭据由本机私有配置读取，不随报告公开。默认8080为DEMO，沙箱应使用独立端口及数据库。

```powershell
& './scripts/start.ps1' -Environment DEMO -Port 8080
```

数据库与业务验证入口包括 scripts/test-database.ps1 -AllTests、scripts/verify-business-flow.ps1、scripts/verify-browser.ps1，以及 scripts/verify-performance.ps1 -Sizes 10000,100000。完整参数、运行目录和日志索引见同日期的test-audit.md。性能脚本创建隔离临时数据库；不要将实验建数命令直接用于保留业务数据的数据库。

## C.2 本次运行版本标识

最终发布标识：category-artwork-20261106-r2。构建快照位于项目目录下的 artifacts/build/category-artwork-20261106-r2，运行包位于 artifacts/releases/category-artwork-20261106-r2/reconis-server.jar。11月4日集中测试使用的 homepage-redesign-20261104-r1 版本仍按原日期保留在测试证据中。

源码快照SHA256：

```text
DCBDDAD4FB91482D80DBE57DE84A08047217051EB8D6C650989899DEDA5198DB
```

运行JAR SHA256：

```text
2168A76A9B642ACB323021E50046003FDDC6BBEFD815992E2A7A8491BD75BEDA
```

## C.3 证据文件与报告源文件

表C-1给出结果的追溯位置。证据附件用于复查测试与数据库实物，不替代正文中的设计分析。报告单独保存在课程目录的Reconis课程报告文件夹中。可编辑正文为implementation-report.md，结构图的SVG、PNG及生成脚本位于implementation-assets，最终Word和PDF位于final。下表中的evidence与implementation-assets路径相对于报告目录，artifacts路径相对于项目目录。

表C-1 证据索引

| 文件或目录 | 内容 |
| --- | --- |
| evidence/20261104/schema-summary.json | 两库字段、键、约束、索引和模块定义快照 |
| 同目录 direct-object-counts.json | 直接查询系统目录得到的对象数量 |
| 同目录 database-checks.json | 六类业务一致性只读检查 |
| 同目录 business-records-current.json | 本轮订单、退款、批次及工单关联记录 |
| 同目录 test-audit.md、test-summary.json | 本轮测试方法、结果与边界 |
| 同目录 performance-summary.csv | 两种规模的性能测量及采集口径 |
| artifacts/report-validation-20261104 | 后端、前端、HTTP、浏览器本轮原始日志 |
| artifacts/validation/performance-20261104-180224 | 隔离规模实验及实际执行计划 |
| artifacts/validation/alipay-sandbox-verification-20261022.json | 明确日期的沙箱收退款历史联调记录 |
| implementation-assets/screenshots | 2026-11-06最终运行版截图及原始裁切记录 |
| artifacts/releases/category-artwork-20261106-r2/manifest.json | 最终版本、源文件与运行包散列及构建测试结果 |
| artifacts/category-artwork-build-r2.log、category-artwork-browser-r2.log | 11月6日构建、前端与浏览器回归日志 |
| artifacts/category-artwork-20261106/visual-check.json | 三种宽度的实际页面复核记录 |
| evidence/20261106/test-summary.json | 最终界面版本测试汇总、原始文件来源与SHA256 |
| 同目录 release-manifest.json、build-r2.log、browser-r2.log | 发布清单、构建日志与浏览器日志的逐字节副本 |
| 同目录 browser-results.json、visual-check.json | 浏览器逐例结果与实际页面验收记录的逐字节副本 |
'''
result='\n\n'.join([opening,db,biz,test,closing,appendix])
result=result.replace('执行前重新核验创建者权限','执行前重新核验当前授权执行者的权限')
result=result.replace('implementation-assets/figures/','implementation-assets/')
result=re.sub(r'^\*\*(表[^\n]+)\*\*$',r'\1',result,flags=re.M)
# References in chapter 4 explain rowversion and isolation; link them to bibliography.
result=result.replace('并非业务发生时刻。','并非业务发生时刻[3]。',1)
result=result.replace('恢复 READ COMMITTED。','恢复 READ COMMITTED[4]。',1)
result=result.replace('支付宝测试接口，','支付宝测试接口[5]，',1)
result=result.replace('详细机制见第6—9章。','详细机制见第6—9章[1][2]。',1)
result=result.replace('第9.1至9.4节记录2026年11月4日的','本章结果依据本轮原始测试日志[6]。第9.1至9.4节记录2026年11月4日的',1)
result=result.replace('这是已完成的历史联调，','这是已完成的历史联调[7]，',1)
result=result.replace('查询条件、动作结果和请求编号支持进一步追踪。','查询条件和动作结果支持进一步追踪，请求编号保存在审计明细中。')
result=result.replace('| 身份 | 主要授权范围 | 关键限制 |','表8-1 角色授权与操作限制\n\n| 身份 | 主要授权范围 | 关键限制 |')
result=result.replace('系统现有分类如下。','分类及判断边界见表6-1。\n\n表6-1 对账分类与判断边界')
result=result.replace('表9-4计时从客户端执行至读完结果。','表9-4计时从客户端执行至读完结果，包含实际执行计划和STATISTICS IO/TIME的采集开销，不是单独SQL算子的耗时。')
result=result.replace('完整的 207 个字段、类型、空值性、默认值与约束见附录字段字典','完整的 207 个字段、类型、空值性及键约束见附录字段字典；默认表达式保存在数据库元数据附件')
# Number figures by first physical appearance, then update every cross-reference.
figure_ids=re.findall(r'^!\[图\s*(\d+-\d+)',result,re.M)
counts={}; mapping={}
for old in figure_ids:
    chapter=old.split('-')[0]; counts[chapter]=counts.get(chapter,0)+1
    mapping[old]=chapter+'-'+str(counts[chapter])
result=re.sub(r'图\s*(\d+-\d+)(?!\d)',lambda m:'图'+mapping.get(m[1],m[1]),result)
(B.parent/'implementation-report.md').write_text(result,encoding='utf-8')
print('Assembled',len(result),'characters')
