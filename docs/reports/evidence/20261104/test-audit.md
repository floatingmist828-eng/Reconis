# 2026 年 11 月 4 日最终报告测试证据

本文是报告编写用的核验记录，不是提交正文。测试统计均来自本轮执行；测试数量按各层分别列示，不将一个业务场景内的多个断言混算成单元测试，也不把页面访问次数算成 Playwright 测试条数。

## 1 环境与证据边界

- 实测时间：2026-11-04 17:55:46 至 18:04:10（北京时间）；随后健康复核仍为 UP。
- 当前 HTTP 服务：http://127.0.0.1:8080，DEMO 环境，发布版本 `homepage-redesign-20261104-r1`。
- 运行服务返回的构建源清单 SHA256：`5193EE8C398CC34EBF89FE66FDDDC249FA622280ABEC3C6BC0A6A290F64A97E6`。
- 业务导出校验所用 JAR：`artifacts/releases/homepage-redesign-20261104-r1/reconis-server.jar`；SHA256 为 `603F804D48B741D4A516FC50531B98FCC386C69EC706B239A2B0C234322A12F4`。本轮没有构建新版本、重启、迁移业务数据库或修改默认版本。
- Windows 11 家庭版中文版，系统构建 26200；物理内存约 15.78 GiB，本轮启动时空闲约 1.98 GiB；PowerShell 7.6.5，Node.js 24.11.0，运行 Java 24.0.2。后端源配置目标为 Java 21；不要把运行 JDK 与编译目标混写。
- SQL Server 16.0.4305.5；性能实验记录的服务器内存上限为 2048 MB，隔离实验库 MAXDOP=2。
- 前端测试工具为 Node 内置测试运行器和 Playwright 1.64.1 的 Chromium；本轮没有测试 Firefox、Safari 或真实移动设备。
- 后端测试运行发布快照目录的源码，临时登录只拥有建立测试库的权限。各数据库用例在独立临时库执行；结束自动清理，不对业务库迁移。
- HTTP 业务和浏览器测试先确认接入环境是 DEMO，再创建唯一前缀账户及账务数据；本轮 7 个业务测试账户和 8 个浏览器测试账户均已停用，账务事实、账单、证据和审计记录保留。没有真实支付宝资金消费。

环境原始记录：`artifacts/report-validation-20261104/environment.json`。

## 2 本轮结果汇总

| 测试层 | 本轮规模 | 通过 | 失败或错误 | 跳过 | 可追溯结果 |
|---|---:|---:|---:|---:|---|
| 前端逻辑单测 | 5 项 | 5 | 0 | 0 | frontend-tests.log |
| 后端 JUnit 全量 | 190 项 | 190 | 0 | 0 | backend-all-tests.log、backend-test-results.json |
| 其中数据库测试 | 72 项，包含于 190 项 | 72 | 0 | 0 | 名称以 DatabaseTest 结尾的 9 个套件 |
| 实际 HTTP 业务流程 | 26 组场景 | 26 | 0 | 0 | business/V20261104095803c0447e.json |
| Playwright 浏览器 | 11 项 | 11 | 0 | 0 | browser-results.json、browser-all.log |
| 其中页面访问检查 | 83 次，包含于浏览器用例 | 83 | 0 | 不适用 | browser-checks.json：1440px 73 次、390px 10 次 |
| 隔离库规模实验 | 2 个规模，每档 13 个测量 | 26 个测量及数据断言均通过 | 0 | 0 | performance-results.json、performance-summary.csv |

以上相对日志名均位于 `artifacts/report-validation-20261104/`，CSV 位于本目录。浏览器 11 项由首页/会话 6 项和多角色业务 5 项组成。历史构建中曾跳过的 72 项数据库测试，本轮已实际执行通过，不能继续沿用“118 通过、72 跳过”的旧统计，也不能把历史沙箱测试数量叠加进本表。

### 实际运行命令

```powershell
# frontend 目录
node --test tests/*.test.mjs

# 项目根目录，针对当前发布源码快照
./scripts/test-database.ps1 -AllTests `
  -BackendDirectory 'D:/数据库/Reconis/artifacts/build/homepage-redesign-20261104-r1/backend'

$env:RECONIS_VERIFY_JAVA=(Get-Command java).Source
./scripts/verify-business-flow.ps1 -BaseUrl 'http://127.0.0.1:8080' `
  -ReleaseId 'homepage-redesign-20261104-r1' `
  -OutputDirectory 'D:/数据库/Reconis/artifacts/report-validation-20261104/business'

./scripts/verify-browser.ps1 -BaseUrl 'http://127.0.0.1:8080'
./scripts/verify-performance.ps1 -Sizes 10000,100000
```

数据库回归 Maven 耗时 90 秒，完成时间 17:57:22。HTTP 流程为 17:58:03.229–17:58:29.186，约 25.96 秒。浏览器流程为 17:58:55.940 起，177.246 秒完成。上述耗时是测试流程耗时，不是单个接口的平均响应或并发吞吐率。

## 3 前端逻辑验证

5 项分别验证：年/月钻取保留已有周范围与原父级；跨年月份钻取采用交集并处理闰年二月；商户/门店钻取保持账期、金额筛选和统计截止时间；旧分组不能扩大查询范围；风险百分比区分零分母并保持大整数精度。结果均通过。

浏览器另外验证未登录根路由进入公开首页、开始使用跳登录、保护路由保留目的地、已登录进入工作台、显式退出不被滞后 401 覆盖、跨标签页会话恢复和真正过期提示。320px 下以 Enter/Space 操作交互步骤；1440/1280/390px 下关闭动画，四步骤内容完整、无横向溢出，分析三级钻取框高度变化不超过 2px。

## 4 后端测试分布及关键反例

| 领域或套件 | 项数 | 本轮结论 |
|---|---:|---|
| AnalyticsDatabaseTest | 12 | 截止时间历史重建、维度/金额筛选、同名门店区分、精确金额与有效差错口径通过 |
| AnalyticsServiceTest、ReportServiceTest、RiskAnalyticsServiceTest | 9 | 查询过滤、文档导出和风险查询逻辑通过 |
| CaseControllerTest、CaseEvidenceServiceTest、FullTextReadinessTest | 21 | 工单权限、证据服务和全文就绪处理通过 |
| CaseEvidenceDatabaseTest、CaseWorkflowDatabaseTest | 14 | 证据完整性、跨商户防护、复核和数据库保护通过 |
| ApiErrorsTest、DbDatabaseTest、HealthControllerTest | 8 | 错误响应、事务、数据库上下文及健康信息通过 |
| IdentityAuditTest | 4 | 身份行为审计通过 |
| CallbackEndpointTest | 28 | 通知入口字段、签名/错误分支与响应处理通过 |
| FinancialConcurrencyDatabaseTest | 5 | 并发确认、退款预占与事务故障恢复通过 |
| PaymentCallbackDatabaseTest、PaymentCreationDatabaseTest、PaymentIntegrationDatabaseTest | 27 | 实库回调、支付创建一致性与支付处理通过 |
| PaymentConfigurationTest、PaymentGatewayTest、PaymentRecoveryTest | 22 | 配置校验、渠道映射及结果不确定恢复通过 |
| AlipayBillParserTest | 8 | 支付宝账单解析边界通过 |
| AutoReconciliationDatabaseTest、AutoReconciliationTest | 18 | 任务领取、幂等、重试、权限与部分失败恢复通过 |
| ImportServiceTest、ReconciliationControllerTest | 14 | 导入归属、预览、完整性与重新核对通过 |
| 合计 | 190 | 无失败、无跳过 |

关键反例的实际证据如下，完整方法名和耗时见 `backend-test-results.json`：

| 条件 | 预期 | 本轮实际结果与定位 |
|---|---|---|
| 回调与主动查询同时确认同笔支付 | 只产生一条收款事实 | `simultaneousCallbacksAndQueriesCreateOneReceipt` 通过 |
| 两个线程同时审批退款 | 预占之和不得超过已支付金额 | `simultaneousApprovalsCannotReserveMoreThanPaid` 通过 |
| 不同连接重复确认退款 | 只产生一次退款事实 | `duplicateRefundConfirmationIsIdempotentAcrossConnections` 通过 |
| 账务写入失败 | 支付及订单状态一起回滚 | `failedLedgerWriteRollsBackPaymentAndOrder` 通过 |
| 持有事务的进程异常退出 | 开放事务回滚，恢复后可继续处理 | `processDeathRollsBackOpenTransactionAndAllowsRecovery` 通过 |
| 复核使用旧 rowversion | 不能批准已被更新的处理内容 | `staleReviewCannotApproveChangedWork` 通过 |
| 外商户匹配记录作为结案证据 | 拒绝跨归属证据 | `crossMerchantProofCannotCloseCaseOrBeWrittenDirectly` 通过 |
| 同一差错复发后沿用旧证据 | 旧证据失效，新观察可作为证据 | `recurrenceInvalidatesOldEvidenceButAcceptsFreshObservation` 通过 |
| 提交或结案后直接改写内容 | 数据库层阻止改写 | `submittedAndClosedContentIsProtectedByTheDatabase` 通过 |
| 金额汇总超越 JavaScript 安全整数及 BIGINT 聚合边界 | 精度保持，不静默截断 | `riskAmountsRemainExactBeyondJavascriptAndBigintAggregateLimits` 通过 |
| 同一事件多次观察、多张工单 | 不重复累计风险金额 | `duplicateObservationsAndMultipleCasesNeverMultiplyRiskMoney` 通过 |
| 较新的部分账单覆盖渠道完整账单 | 不允许把低完整性输入当有效基线 | `newerPartialOrDeclaredBillCannotReplaceProviderCompleteBaseline` 通过 |
| 多任务工作线程同时领取 | 同一任务只能被领取一次 | `concurrentWorkersClaimExactlyOnce` 通过 |
| 工作租约过期或进程中断 | 恢复租约，第四次中断停止继续重试 | `expiredWorkerLeaseIsRecoveredAndFourthInterruptedAttemptStops` 通过 |
| 自动账单原件被改动 | 拒绝下载或重导入 | `tamperedOriginalIsRejectedRatherThanDownloadedOrReimported` 通过 |

后端测试中的渠道应答或签名数据是测试夹具，不等于支付宝公网完成了真实回调。

## 5 实际 HTTP 业务结果

运行号 `V20261104095803c0447e`。所有样本均为本轮 DEMO 自动创建的教学数据。

| 场景 | 输入/动作与预期 | 实际结果 |
|---|---|---|
| 匿名与角色边界 | 匿名读取订单；普通成员访问用户管理 | 401/403 与 403；未取得业务数据 |
| 注册审核 | 未审核账户尝试登录，管理员审核后再登录 | 前者 403，后者成功；权限与平台角色吻合 |
| 商户与门店 | 资料审核后重复旧版本审核；收银员创建门店 | 陈旧审核与无候选重复审核 409；越权创建 403 |
| 收款 | 两个订单分别 100.00 和 50.00 元 | 两笔支付成功，重复通知与查询不重复记账 |
| 通知丢失 | 第二笔模拟丢失通知，主动查询渠道 | 确认支付并补齐唯一收款事实 |
| 伪造通知 | 无效 RSA2 签名的外部 HTTP 回调 | 返回纯文本 `failure`，账务与支付状态不变；未把不可信通知关联到订单 |
| 部分退款 | 第一笔退款 30.00 元；同幂等键重复申请/重复同步 | 同一退款对象，唯一退款事实；可退 70.00 元 |
| 超额与自审 | 再申请 70.01 元并批准；申请人审批自己的退款 | 两者 409；消费者越权审核 403 |
| 金额及关联钻取 | 商户、门店、年、月、日切换，固定账期/asOf | 收款 150.00、退款 30.00、净收款 120.00；三条资金事实关联原订单，分页无重复 |
| 导出 | XLSX/XML/PDF/DOCX 与原文件下载 | 文件有效、内容/哈希与权限检查通过；PDF可抽取中文与图表相关内容；不是只检查扩展名 |
| 导入预览 | 三条标准记录预览，再导入及重复导入 | 有效行 3、错误 0；复用同批次；执行对账得到 3 条 MATCHED |
| Excel 回流 | 将导出的 Excel 重新导入 | 三条事实与金额、支付/退款关联均保留，核对一致 |
| 金额差异 | 两条渠道收款各增加 1.00 元，退款不变 | 2 条 AMOUNT_MISMATCH，自动产生 2 张差错工单 |
| 完整空账单 | 完整声明下渠道零条记录，本地三条仍存在 | 三条 LOCAL_ONLY，不误判空文件为无业务 |
| 归属不明 | 平台导入未知商户渠道记录 | 进入 PLATFORM_UNASSIGNED；普通商户访问403/404，财务可预览识别 |
| 修订与批次比较 | 新账单修正两笔金额，比较旧差异批次 | 2 条 RESOLVED；旧批次和历史工单保留，产生新匹配证据 |
| 分派和提交 | 用旧版本再次分派；保存分析、说明、附件并提交 | 旧版本409；进入 PENDING_REVIEW；待复核禁止修改 |
| 独立复核 | 经办人尝试自审；复核退回后再次提交并批准 | 自审403；退回 PROCESSING；新提交最终 CLOSED，原分类仍 AMOUNT_MISMATCH |
| 全文与推荐 | 查询“金额”，检索本轮刚生成工单 | engine=`SQL_SERVER_FREETEXTTABLE`，命中新工单；同类已关闭工单被推荐且score>0 |
| 历史与风险统计 | 保留旧asOf、看修订后有效事件 | 旧时点没有后来工单；修订后有效差错事件为0，历史工单仍可追溯，净额仍120.00 |
| 证据档案 | 下载ZIP、核验清单与每一纳入原件哈希 | 所有已纳入文件哈希吻合，版本/流程记录完整，外商户访问被拒绝 |
| 自动任务 | 财务重复触发同账期任务；非授权人触发/读取 | 复用任务103，SUCCEEDED；财务可验证原件SHA，商户/审计触发403，商户读原任务403 |
| 审计和退出 | 查询作用域审计；普通商户读全局审计；退出后me | 包含ACCESS/DB_CHANGE及失败AUTH；越权403，退出后401/403 |

### 截图可用的记录定位

- 商户54、门店44；订单266（100元）与267（50元）；退款67（30元）。
- 账单批次125为初始一致，127为金额差异，130为修订一致；工单115为复核结案，114为推荐场景另一张工单。
- 实库回查证实：工单115保留原差异结果371（AMOUNT_MISMATCH），最新观察381为MATCHED，状态为CLOSED、结案原因RECONCILED；经办/提交人286与复核人287不同。退款67申请人288与审核人285不同。只读查询原件见 `business-records-current.json`。
- 上述记录账号已停用，但财务或审计在其正常权限范围内仍能查阅。截图若出现技术测试前缀，应说明这是自动化验证数据；不要把它伪装成真实商户经营数据。
- 本轮“全文检索与历史推荐”整组耗时 550ms。该值包括就绪检查、查询、索引可见性断言和推荐请求，不是单次全文 SQL 耗时，更不是普遍响应上限。

### 自动任务的实测限制

数据库只读查询确认任务103于北京时间 2026-11-04 00:09:05 创建，约4.4秒后成功，attempts=1、generation=1、daily=false。本轮17:58重复调用复用了这条已成功任务，没有重新下载渠道账单。因此本轮 HTTP 测试证明的是任务幂等、状态可查、原件哈希和权限隔离；不能表述成“本轮新完成一次自动下载”。任务创建、领取、失败恢复等另有本轮10项数据库测试的独立夹具证据。详见本目录 `auto-job-103.json`。

## 6 浏览器业务验证

本次使用八种身份视角：管理员、平台财务、审计、商户负责人、收银经办、复核人、消费者、无关成员。这不代表数据库存在八种平台角色枚举；会员角色和商户成员关系共同决定授权。

五组页面业务用例覆盖：

1. 逐页检查权限、操作按钮和空状态，拒绝无关成员查到本商户订单；共73次桌面访问。
2. 在页面中建立订单、消费者DEMO支付、申请退款和独立审批，读取持久化结果确认成功。
3. 页面分派、上传证据、保存分析、提交、退回、补充、再次提交、独立复核、结案、ZIP档案下载；关闭后的输入禁用。
4. 注册/管理员审核/停用立即失效，消费者390px下10个页面无横向溢出。支付失败说明的显示使用只读响应注入，仅验证页面呈现，不冒充真实渠道失败交易。
5. 页面上传XML并预览校验、导入、执行对账、展开差异、比较批次、商户→门店→交易钻取、打开风险明细、导出四种格式、调用原生全文检索。

原始运行目录 `artifacts/validation/browser-UI1793786350525193e1` 包含本轮下载件和步骤截图；其检查摘要已复制到本轮独立目录。所有4个业务完成标志 paymentFlow、caseFlow、registrationAndMobile、advancedBrowserFlow 均为 PASSED，8个临时账号清理状态均为 DISABLED。

## 7 规模与查询实验

### 方法

执行现有 `verify-performance.ps1 -Sizes 10000,100000`。新建独立临时库，应用001–004及006–010全部迁移，使用2个商户、10个门店，先生成1万、再累计至10万笔支付流水；每档均将2%的渠道收款设置为金额不符。两商户各占半数。单笔金额按确定性表达式生成，汇总再用逐笔独立计算校验。数据只用于验证，没有业务库写入。

每个操作顺序执行，采集 `STATISTICS IO/TIME` 与实际执行计划，读完结果后结束客户端计时。没有清空SQL Server缓存；“首查”只表示当前档位首次执行该关键词查询，不是冷缓存。本实验是单次有限规模观察，没有重复多轮、并发压力或P95统计，不能写吞吐率或服务等级承诺。两档均通过全部数据断言，临时库清理成功。

### 数量与金额正确性

| 全库流水规模 | 独立计算收款总额（分） | 每商户结果数 | 每商户金额差错数 | 全文金额差错工单命中总数 |
|---:|---:|---:|---:|---:|
| 10,000 | 50,005,100 | 5,000 | 100 | 200 |
| 100,000 | 500,501,000 | 50,000 | 1,000 | 2,000 |

各商户其余结果均为匹配，按门店聚合的数量与金额均等于对应逐笔结果；权限过滤后的全文结果未出现外商户工单。注意“10万流水”不等于“10万全文案例”，此时全文案例量为2,000。

### 耗时结果

单位为毫秒，保留三位小数；对账为各商户独立执行，不能把其中一行称为全库10万笔对账总耗时。

| 操作 | 10,000流水档 | 100,000流水档 |
|---|---:|---:|
| 生成本档新增数据 | 4076.743 | 34305.745 |
| 商户1对账（各档5,000/50,000笔） | 2397.977 | 15574.102 |
| 商户2对账（各档5,000/50,000笔） | 2385.127 | 20226.653 |
| 商户1按门店汇总 | 233.658 | 843.120 |
| 商户2按门店汇总 | 95.818 | 507.310 |
| 商户1分页 OFFSET100 / 20条 | 142.116 | 145.344 |
| 商户2分页 OFFSET100 / 20条 | 81.736 | 16.681 |
| 中文全文首查 TOP30 | 134.226 | 180.994 |
| 同条件重复全文 TOP30 | 28.641 | 115.042 |
| 另一商户全文 TOP30 | 25.118 | 107.285 |

数据扩大后，批量核对写入与约束检查仍有明显成本，5万笔单商户核对约15.6–20.2秒，不适合描述成瞬时完成。查询耗时不能仅按行数线性外推，第二商户分页较快可能受缓存与编译复用影响；本实验没有把这种差异作为优化收益。

### 执行计划与 IO 解释

原始计划位于 `artifacts/validation/performance-20261104-180224/`，是含 RunTimeCountersPerThread 的实际执行计划。结构摘要见本目录 `performance-plan-summary.json`，不是只看估计代价。

- 10万档按门店汇总实际使用 `IX_order_store_time`、`IX_payment_order` 等索引，同时出现扫描、Hash Match和聚合。大范围汇总出现扫描是优化器选择，不能声称所有查询都 Index Seek。
- 10万档商户1汇总：客户端843.120ms；SQL消息中解析编译337ms、执行501ms；business_order逻辑读480、payment_attempt931、transaction_entry1319。客户端时间与服务器执行时间有区别。
- 分页计划使用 `IX_entry_time`、Nested Loops、Top及主键查找；商户1分页对transaction_entry逻辑读11,370，对business_order与payment_attempt分别11,312，说明返回20行也可能付出较多关联过滤成本。可在后续研究更适合权限过滤和排序的访问路径或游标分页，不把“返回少量行”当作“读取少量页”。
- 中文全文计划包含 Table-valued function、关联与排序，商户1首查客户端180.994ms、服务器解析编译74ms、执行103ms。全文命中后仍需关联批次、结果和商户权限，并非只有索引查找。
- 未进行删除009索引的同期A/B实验，不报告“新增索引提速N倍”。

## 8 支付宝证据分层与未验证项

本轮所有持久化支付/退款业务流程是DEMO，真实服务器HTTP请求不等于真实支付渠道资金交易。沙箱情况采用明确日期的历史证据另列：

`artifacts/validation/alipay-sandbox-verification-20261022.json` 记录 2026-10-22 11:46:40+08:00：沙箱付款1分、退款1分均SUCCEEDED，收款/退款事实各1条，净额0、可退0，退款幂等true；确认渠道为ACTIVE_QUERY。该记录可作为历史联调证明，不算作11月4日本次新交易。

同一历史记录中账单ZIP为1017字节、哈希校验通过，但内容是TEMPLATE_ONLY，0账单行、0对账结果，不生成业务批次或差错工单。`realBusinessBillReconciliationVerified=false`。公网异步回调 `configured=false`、`realAsyncNotificationVerified=false`。不得写“已验证支付宝实际交易日账单自动对账”或“公网回调已联通”。

尚未由本次实验覆盖：生产支付宝资金交易、真实公网异步通知闭环、真实业务支付宝日账单完整对账、多用户长期压力稳定性、长时任务连续运行、真实手机浏览器及跨浏览器兼容。本轮测试没有发现新的失败，但通过有限测试不等于排除全部缺陷。

## 9 附件定位

- `artifacts/report-validation-20261104/environment.json`：本轮环境与健康。
- 同目录 `frontend-tests.log`、`backend-all-tests.log`、`backend-test-results.json`：新执行日志与190项逐例结果。
- 同目录 `business-flow.log`、`business/V20261104095803c0447e.json`：26组真实DEMO HTTP流程及记录ID。
- 同目录 `browser-all.log`、`browser-results.json`、`browser-checks.json`：11项页面自动化与83次访问明细。
- 同目录 `performance.log`、`performance-results.json`：规模实验原始读数及SQL消息。
- 本目录 `performance-summary.csv`、`performance-plan-summary.json`：绘图和查询分析用结构化附件。
- 本目录 `test-summary.json`：各层测试统计与18:12健康复核；该复核仍为UP且发布标识不变。
- 数据库代理的 `auto-job-103.json`、`database-checks.json`、`query-plan-summary.json`：当前业务库只读查询。后者中的专门点查计划是估计计划，与本轮规模实验实际计划需要区分。
