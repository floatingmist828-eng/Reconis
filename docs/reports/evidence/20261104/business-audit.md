# 2026-11-04 业务、架构与高级功能核查

> 工作证据附件，供最终《实践总结报告》编写与交叉检查；不作为报告正文。核查对象为当前工作区代码，不修改业务代码及初期系统设计报告。代码存在本轮之前的未提交前端改动，不能仅以 Git HEAD 代表全部最终实现。数据库实物和本次测试结果以同目录数据库、测试证据附件为准。

## 1. 证据使用口径

- 当前实现证据：源码、依赖清单、SQL 脚本、配置与路由。以下定位采用仓库相对路径；行号为本次核查时的位置。
- 当前运行证据：2026-11-04 的数据库检查、测试日志与浏览器截图，应由本轮核验记录补入。本文不把历史通过数当成本次通过数。
- 历史运行证据：`artifacts/validation/alipay-sandbox-verification-20261022.json` 等明确记录时间的文件，只能证明该日期对应版本的联调结果。
- 规划证据：`docs/design.md` 是 2026-10-19 初期方案；其 A/B/C 分工是计划，不等同实际个人贡献。`docs/reports/system-design.md` 已包含多项较后期物理设计，不能把它已经写出的内容再称为“最终才新增”。
- 教学与沙箱隔离：DEMO 是教学模拟渠道，ALIPAY_SANDBOX 是支付宝沙箱；都不是生产支付宝收款系统。商户是系统内的业务隔离主体，并非多个真实支付宝商户入驻。

## 2. 实际技术架构与版本

### 2.1 架构关系

浏览器 Vue 单页应用经同源 `/api/v1` 请求 Spring Boot 模块化单体服务。身份、商户、订单、支付、对账、工单和分析是同一应用内的模块，通过 MyBatis 访问 SQL Server。数据库负责关系约束、关键业务存储过程、审计触发器、外模式和中文全文索引。支付适配层按环境调用教学模拟实现或支付宝沙箱 SDK。文件层将账单原件、渠道响应、证据附件和导出文件保存到环境独立目录，数据库保存文件标识、散列及业务关联。定时任务与 Web 服务处于同一应用进程，不存在 Redis、消息队列、独立微服务或专用 OLAP 服务。

开发时 Vite 可代理后端；发布脚本将前端产物纳入后端发布包，运行一个 Java 应用即可提供页面和 API。公开首页为 `/welcome`，登录页为 `/login`，登录后根路径为工作台。路由与静态路径兜底分别见 `frontend/src/router.ts`、`backend/src/main/java/cn/reconis/common/SpaController.java`。

### 2.2 依赖清单

| 层次 | 已核实版本 | 证据 |
|---|---|---|
| Java 编译目标 | 21 | `backend/pom.xml` 的 `java.version` |
| 本机 Java 运行时 | 24.0.2 | 本轮 `java -version`；不要写“运行时 JDK 21” |
| Spring Boot | 4.1.1 | `backend/pom.xml` 父 POM |
| MyBatis Spring Boot Starter | 4.0.0 | 同上 |
| SQL Server JDBC | 12.10.2.jre11 | 同上；驱动包后缀不是本机 Java 版本 |
| Alipay SDK | 4.40.272.ALL | 同上 |
| Apache POI / PDFBox / JFreeChart | 5.4.1 / 3.0.5 / 1.5.6 | 同上 |
| Jackson Databind | 2.20.0 | 同上 |
| Vue / Vue Router / Pinia | 3.5.22 / 4.6.3 / 3.0.3 | `frontend/package.json` |
| Element Plus / 图标 | 2.11.5 / 2.3.2 | 同上 |
| ECharts / QRCode | 6.0.0 / 1.5.4 | 同上 |
| Vite / TypeScript / vue-tsc | 7.1.12 / 5.9.3 / 3.1.1 | 同上 |
| Playwright | 1.64.1 | 同上 |
| Node / pnpm | 本机 Node 24.11.0；项目 pnpm 11.25.0 | `node --version`、packageManager；Node 要求 `>=24 <25` |

SQL Server 的实际版本、排序规则、库名、对象数量应引用本轮数据库核查，而不是旧报告。

### 2.3 部署与环境配置

`backend/src/main/resources/application.yml`：端口缺省 8080，可由 PORT 覆盖；会话 60 分钟；Hikari 最大连接 5、最小空闲 1、取连接超时 10 秒；JDBC 登录 8 秒、socket 45 秒、query 30 秒、cancel 5 秒；Multipart 单文件 20 MB、请求 22 MB；MyBatis 不使用二级缓存，本地缓存仅 STATEMENT。上述是配置，不是压测指标。

`common/FileStore.java:16` 限定环境、库与目录的组合：DEMO 对应 ReconisDemo 和 `demo` 目录；沙箱对应 ReconisSandbox 和 `sandbox` 目录。子目录为 imports/evidence/exports/channel。文件名由系统随机生成，路径规范化后须位于该环境根目录，防止请求任意本机文件。

本地配置允许 HTTP，Cookie secure 缺省 false；SQL Server 开启加密但信任本地证书。不能将其描述为已经完成生产 HTTPS、可信数据库证书或公网部署。密钥从本地配置/环境读取；报告和截图均不应包含其值。

## 3. 参与角色与授权模型

### 3.1 两层角色

平台角色有 MEMBER、PLATFORM_FINANCE、ADMIN、AUDITOR；商户成员角色有 OWNER、CASHIER、FINANCE、REVIEWER。消费者是具有本人订单访问关系的 MEMBER，并非第五种平台角色。一个账户可以有不同商户成员关系，权限取当前有效关系。

| 身份 | 主要实际能力 | 后端边界 |
|---|---|---|
| 普通消费者 | 看本人订单、发起付款、对成功支付申请退款 | `Access.ordersScope` 增加 consumer_id=当前用户；不是任意商户交易查询 |
| OWNER | 商户资料/门店/成员管理、开单、退款审核、差错分派、处理和复核等 | 有效商户关系，另受申请人不能审批、处理人不能自复核约束 |
| CASHIER | 开单、维护允许修改的订单、付款入口、退款申请、受派工单处理 | 不具备退款审批或工单复核入口权限 |
| FINANCE | 所属商户财务、退款审核、账单导入/核对、工单分派处理、分析导出 | 受商户范围限制；新建订单接口只接受 OWNER/CASHIER，不能笼统写 FINANCE 可办理全部交易 |
| REVIEWER | 所属商户数据查看、工单独立复核 | 不等于“后端所有接口都只读”，见下述准确性提醒 |
| PLATFORM_FINANCE | 跨商户财务，未知归属账单和差错处理，自动账单任务 | `Access.merchant` 对平台财务允许跨商户办理；仍检查业务状态和独立性 |
| ADMIN | 注册用户审核、角色/状态维护、商户审核、访问/操作审计 | 不默认拥有财务操作权；分析统计主动拒绝 ADMIN |
| AUDITOR | 跨商户只读查看及审计查询 | 各写入口显式禁止或经 `Access.merchant` 拒绝 |

准确性提醒：`payment/PaymentController.java:32` 的预下单只要求可读该订单并排除 ADMIN/AUDITOR，没有进一步排除 REVIEWER；同步支付入口也主要基于可读订单。因此最终报告不能写“复核员在任何 API 下完全无法触发渠道请求”。应将权限表写成具体业务操作，而不是将 UI 菜单当成完整权限证明。

### 3.2 认证与访问安全

- `identity/IdentityController.java:32`：注册名 3–32 位字母数字下划线，密码 10–64 字符，BCrypt 保存；注册为 PENDING，管理员审核到 ACTIVE 后才能登录。
- `identity/SecurityConfig.java`：BCrypt 成本 11；服务端 HttpSession 登录；CSRF Cookie 及请求令牌校验，唯一排除支付宝异步通知接口；API 未登录/无权时返回 JSON 401/403。
- `IdentityController.java:50`：登录时核验账户状态，更新会话标识防止固定会话攻击，记录 AUTH 成功或失败；退出使会话失效。管理员不能在审核接口修改自己身份/停用自己。
- `common/RequestAuditFilter.java`：每次 API 请求重新查询用户当前角色、状态；停用立即使当前会话失效，不仅依赖登录瞬间的状态。每次请求建立请求编号，响应 X-Request-Id，最后记录 ACCESS 的方法、路径、结果、人员、商户与请求号。
- `identity/Access.java:50`：平台财务/审计可跨商户查询，普通用户以有效 merchant_member 子查询限制数据。列表、详情、关联、推荐和导出均应引用后端实际范围校验，而非仅称“按钮隐藏”。
- `common/SessionContextPlugin.java`：在实际 MyBatis 连接设置 user_id、role、merchant_id、request_id，SQL 视图/触发器可用这些会话上下文；执行后清空，避免连接池上下文串用。
- `common/Api.java:15`：Long/BigInteger 输出字符串、ROWVERSION 输出十六进制，避免 JavaScript 大整数精度损失；错误统一含 requestId，不向用户回传 SQL 连接信息/内部堆栈。
- 当前审计写入失败会记录应用警告；数据库不可用时不应声称“任何请求都保证完成数据库审计”。

## 4. 订单、支付与退款的实际流程

### 4.1 订单生命周期

`order/OrderController.java:94` 创建在事务中完成：检查商户和门店有效、角色、可选消费者账户、明细与金额，使用 clientRequestId(UUID) 识别重复请求，写订单和订单行。`PATCH :198` 只修改 DRAFT 且匹配 ROWVERSION，订单及行一起更新。`DELETE :222` 只删除草稿及其明细，不提供删除资金事实的功能。`:232` 提交至 WAITING_PAYMENT；`:243` 可取消草稿或无活跃支付尝试的待付款订单。

应付金额来自订单行价格、数量；支付发起后有冻结规则。收款成功不能依靠浏览器点击按钮直接写成“已付款”，需可信的教学或沙箱查询结果经确认过程写入资金事实。

### 4.2 预下单与结果恢复

入口为 `POST /api/v1/orders/{id}/payments`，核心 `payment/PaymentService.java:24`：

1. 短事务锁定订单 `WITH(UPDLOCK,HOLDLOCK)`，查 UUID 是否已使用；同请求同业务复用，冲突请求拒绝。
2. 订单须为 WAITING_PAYMENT；同订单已有非 CLOSED/FAILED 尝试时复用该尝试，避免刷新产生新支付号。
3. 写入支付尝试和稳定 out_trade_no，提交数据库事务。
4. 已发送过或不再是 CREATED 时先查询原交易。只有取得租约的执行者可以请求渠道；租约 90 秒、随机派发令牌、发送次数持久化。
5. 网络调用在数据库事务外，避免等待支付宝时持有订单/额度锁。
6. 返回二维码、友好错误、原始渠道证据并更新尝试；更新限定当前令牌和非终态，防止迟到的预下单响应覆盖已确认成功。
7. 超时或结果未知保留 UNKNOWN 和原交易号，提示查询，不能把它当作肯定失败后重新收款。

`PaymentService.java:185` 主动查询：成功金额必须与应付一致，才调用 `usp_confirm_payment`；金额不符生成查询差异证据并保留待核查状态。一次订单发生第二笔真实成功收款时保留事实并产生额外收款差错，不用主支付唯一约束把真实第二笔收款抹掉。终态更新后清除过期二维码。`recordQueryDifference:244` 形成 TRADE_QUERY 来源批次、账单证据及对账结果。

`PaymentService.java:429` 每 15 秒扫描到期 CREATED/PENDING/UNKNOWN 支付和需恢复退款，每批 TOP 20；查询重试间隔逐级为 15/60/300 秒。该机制能补偿通知丢失，但不意味着外部渠道故障期间立即完成支付确认。

### 4.3 异步通知可信性

`PaymentController.java:206` 对支付宝通知执行参数数/长度/单值约束，要求 RSA2、通知类型与状态合法；SDK 验签时使用参数副本。验签通过后才关联本地支付，继续核对 appId、sellerId、环境、交易号、金额。支付终态通过主动查询确认，不仅凭回调文本改账；同交易已确认成功的重复通知可直接幂等接受。

通知原件、SHA256、验签结果、处理结果和请求号保存。只有已接受且留证成功返回纯文本 `success`，其余返回 `failure` 供渠道重试。无效签名通知不会依据伪造订单号附着到正常订单证据上。

`payment/CallbackEndpoint.java` 仅检查 HTTPS 443、固定 `/api/v1/callbacks/alipay` 路径和非本机/非私网等格式条件，源码明确表示该校验不证明公网可达。真实签名回调仍需公网 HTTPS 入口；本次不能把伪造回调被拒绝当作真实异步通知联调成功。

### 4.4 退款审批、额度与恢复

入口 `POST /api/v1/payments/{id}/refunds`：原支付须成功；本人消费者或所属商户 OWNER/CASHIER/FINANCE 可以申请。amountCent 为正整数，UUID 同支付/金额/原因时复用，参数冲突拒绝。申请进入 PENDING_REVIEW，并保存稳定 out_request_no。

`PaymentController.java:137` 只有 OWNER/FINANCE 或平台财务调用审批存储过程。审批过程校验申请人和审批人分离、有效业务状态及可退额度，额度在渠道调用前预留；审批通过再由服务执行渠道退款。具体 SQL 锁顺序和预留状态集合见数据库证据。

`PaymentService.java:318` 使用 90 秒租约/令牌恢复退款；首次 APPROVED 派发，PROCESSING/UNKNOWN 优先查原退款，只有 NOT_FOUND/NOT_EXECUTED 才按原请求号再发。成功由 `usp_confirm_refund` 写独立 REFUND 资金事实。结果未知不提前释放预留额度，不改新请求号反复退款。网络仍在数据库事务外。

收款金额与退款金额分别统计；跨日退款按退款实际发生日计入，而不是倒改原收款日。订单是否全额退款是事实计算出的状态，不是删除收款记录。

## 5. 账单导入、对账与历史比较

### 5.1 导入校验与原件留存

`reconciliation/ReconciliationController.java` 提供 `/reconciliation/imports`、`/merchants/imports`，默认 preview=true。账单支持系统 XLSX/XML 和支付宝原生 CSV/ZIP；商户支持 XLSX/XML。

`reconciliation/ImportService.java:41`：XLSX 模板表头必须完全匹配、最多 10,000 行、拒绝公式单元格；XML 关闭 DTD/外部实体/XInclude，根节点、版本、环境与允许的行节点严格校验。未知 XML 内容不能当作“空完整账单”。逐行检查事件类型、金额整数、时区、交易标识、账期、环境与商户归属，输出行号及错误。

`:146` 账单导入为异常回滚事务。预览给出有效数、错误、重复、未归属和商户分区，并展示有限样例；真实导入保存原件 URI、SHA256、校验结果和范围参数。重复业务键保留为原始异常输入，不在导入阶段静默去重。

`:299` 区分文件字节身份与解释参数身份：同一文件在不同期间、完整性、路由方式下不等价。用文件 hash 加 options hash 构成导入幂等标识，`:329` `sp_getapplock` 串行保护重复导入；相同条件复用批次。

完整性只允许 UNKNOWN/DECLARED_COMPLETE。完整声明还需校验无错、归属可判断，才能用于认定本地有而渠道无；空账单必须明确声明完整。未知商户归属进入平台隔离批次，不能随便归给某商户；确认归属会重新依据保存原件产生可追溯处理。不能声称“任何上传文件均可证明完整账期”。

### 5.2 对账与重跑

`POST /reconciliation/batches/{id}/run` 调用 `usp_run_reconciliation`。过程在一致读取边界内保存输入范围、观察时点及逐笔分类；先检测缺键和重复异常，再对可比较集合做 FULL OUTER JOIN，不用普通 INNER JOIN 丢失单边记录。非匹配结果产生或关联差错工单，避免每次重跑重复派单。分类和事务细节由数据库审计补入。

`ReconciliationController.java:87` 重跑创建新批次，引用原始输入批次、基线批次、文件及声明范围，不覆盖旧结果。`:178` 比较要求相同商户范围且均可访问，对历史结果按稳定事件键和重复序号 FULL OUTER JOIN，输出 UNCHANGED/NEW/RESOLVED/CHANGED。一个差错被人工结案不等于历史对账分类被改成 MATCHED。

### 5.3 自动账单任务

`reconciliation/AutoReconciliationService.java:38` 只有 PLATFORM_FINANCE 能创建任务，1–31 个已结束业务日，记录环境/应用/日期的幂等键。任务持久保存到 source_type=AUTO_JOB 的批次行，业务结果为其子批次；不是只存在内存的定时线程状态。

启用每日自动获取后，上海时间 10:00 获取前一天账单，并有有限补缺。执行器每 10 秒调度，领取 10 分钟租约，最多 4 次执行，重试间隔 5/30/120 分钟；重试复用原件和已成功子批次。任务执行前复查创建者仍为有效平台财务，权限撤销时停止。虚拟线程承载受时限约束的下载，防止阻塞支付恢复调度。

`reconciliation/BillDownloader.java` 对渠道下载地址采用允许域/路径、HTTPS 443、公共 DNS 地址、无重定向、禁代理等限制；连接 5 秒、读取 10 秒、整体 30 秒、最多 20 MB；已知沙箱 HTTP 地址只升级成相同签名参数的 HTTPS 请求，非任意 HTTP 下载。

`AutoReconciliationService.java:253` 对非 DEMO 下载先解析，然后结束为 TEMPLATE_ONLY，并明确写“模板不代表实际交易，未生成对账差错”。DEMO 才执行预览→无错误导入→分商户核对→生成差错的完整教学流程。此分支是数据真实性保护，不是缺陷掩饰；报告必须同时写出自动获取能力和真实业务账单尚未验证的边界。

## 6. 差错协作、独立复核和证据

### 6.1 状态变化与并发检查

`casework/CaseController.java:183`：OWNER/FINANCE 或平台财务分派，只能选择有效账户和允许处理角色；未知归属差错只能由平台财务处理。工单主线：OPEN → ASSIGNED → PROCESSING → PENDING_REVIEW → CLOSED，复核退回进入 PROCESSING。

`:214` 保存必须为当前受派人、允许角色、ASSIGNED/PROCESSING 状态，并匹配 ROWVERSION。`:277` 提交要求分析和结论非空、当前受派人、PROCESSING 状态及最新版本。表单提交旧版本返回 409，避免两窗口覆盖。

`:294` 复核要求 OWNER/REVIEWER 或平台财务，调用 `usp_review_exception`。独立性由数据库比较 reviewer 与 submitted_by，不仅是前端隐藏，也不是只比较当前 assignee。重新匹配结案须引用符合事件和权限范围的 MATCHED 结果，归属确认须引用有效归属批次；不能任意填写“已解决”就改变客观核对事实。结案代码为 RECONCILED / INVALID_INPUT / FALSE_POSITIVE / KNOWN_EXCEPTION / OWNERSHIP_CONFIRMED，具体检查条件见数据库证据。

### 6.2 附件与档案

`CaseController.java:238` 允许当前处理人在 ASSIGNED/PROCESSING 上传非空 PDF/PNG/JPG/TXT/XLSX/XML；20 MB 上限来自全局 Multipart 配置。生成随机文件名，侧车元数据保存 caseId、原名、URI、SHA256，再按受派人/状态/版本更新工单；更新失败清除本次新文件及侧车文件。这里是**扩展名白名单**，不是内容病毒扫描或严格 MIME 鉴别，不能拔高描述。

`casework/CaseEvidenceService.java` 将当前工单、审计前后记录、初始/最新/复核结果、关联批次、账单行、交易事实、订单明细、支付退款与可授权原件汇为档案；每个关联与最终下载都再次校验范围。SHA256 用于确认已记录原件未变，历史缺失散列会标记证据缺口而不伪装为已验证。共用账单原件可能包含其他商户，因此商户用户不会获得全部共享原件；平台范围与商户范围不同。

档案查询每类上限 500 条、单文件 20 MB、总归档 80 MB，超限/缺失会在清单中说明。不能声称“任意规模完整历史全部打包、绝不遗漏”。导出审计使用 DOSSIER_EXPORT。

### 6.3 数据关联路径

`CaseController.java:77` 详情联接：exception_task.latest_result_id → reconciliation_result → reconciliation_batch；结果的 entry_id → v_transaction_fact → 订单/门店/商户/支付；statement_line_id → 渠道账单行。另提供历史审计、同事件重新匹配证据和归属证据。档案进一步展开 order_item、payment_attempt、refund_request。

可在报告用具体实例叙述：由一条金额差异打开差错，看到本地收款额和渠道账单额、所属门店商户、原订单及付款；再追踪处理人提交、复核结论与引用批次。这样证明的是跨实体关联，而不只是“页面存在链接”。

## 7. 高级要求逐项落实

| 要求 | 当前实现与操作路径 | 建议报告证据及边界 |
|---|---|---|
| Excel/XML 导入导出 | 商户、账单模板与预览导入；交易和对账结果导出 | 展示一份有效预览与错误行反馈，再给导入结果；两种格式都已实现，课程的“或”不意味着只做一种 |
| 高级用户审核 | 管理员审核注册/商户，财务退款审批，工单独立复核 | 区分资料审核和业务审批；提供申请/审批身份与状态变化 |
| 自动采集 | 支付/退款主动查询、自动账单任务 | DEMO 完整自动核对；沙箱模板获取和完整性已历史验证，不冒充真实账单 |
| 全文检索 | 工单标题、描述、分析、结论的 SQL Server 中文 FREETEXTTABLE，LANGUAGE 2052 | 有关键词、有排名、有授权范围；检索未就绪返回 503 状态提示，不能称 LIKE 模糊匹配 |
| 记录比较 | 基线/当前批次逐项比较 | 展示 NEW/RESOLVED/CHANGED 等实际结果，解释重跑保留历史 |
| 案例推荐 | 已结案有结论案例，按分类、原因、全文排名排序 TOP 5 | 规则解释和相关案例；不是机器学习或大模型推荐 |
| 审批/分派管理 | 退款审批、工单派单处理复核闭环 | 身份分离、版本冲突、证据引用均有实际服务端约束 |
| 多粒度钻取 | 商户→门店→流水；年→月→日→流水 | 继承期间、金额、商户、来源与 asOf；用父子汇总和最终明细金额验证，不只截图层级按钮 |
| 数据关联 | 差错→对账结果→账单/流水→支付退款→订单→门店→商户；工作台和统计联接 | 选择一个真实样例串联显示，说明如何由问题定位业务主体 |
| 访问/操作日志查询统计 | ACCESS/AUTH/业务审计，按角色人员商户结果时间过滤及聚合 | 管理员/审计查看；展示请求编号及一次操作数据库记录 |

### 7.1 全文、预热与推荐的技术依据

`casework/CaseController.java:49` 全文搜索使用 `FREETEXTTABLE(exception_task,(title,description,analysis_text,resolution_text),...,LANGUAGE 2052)`，先应用访问范围，再返回相关项。

`casework/FullTextReadiness.java` 应用就绪后由独立守护调度线程用同一中文分词器进行 20 秒超时预热；未就绪每 30 秒重试，状态 WARMING/UNAVAILABLE/READY 带检查时间、耗时。查询异常可转为 UNAVAILABLE 并重新预热，避免用户请求一直等待初始化；资源不足时仍可能超时，不能把预热写成无条件解决所有性能问题。

`CaseController.java:118` 推荐候选必须是用户可见、已 CLOSED、有结论且非当前工单，满足同类或文本相关。评分为：

```sql
0.50 * CASE WHEN classification=@classification THEN 1 ELSE 0 END
+ 0.20 * CASE WHEN reason_code=@reason THEN 1 ELSE 0 END
+ COALESCE(0.30 * text_rank / NULLIF(MAX(text_rank) OVER(), 0.0), 0)
```

这是可解释启发式排序，0.50/0.20/0.30 是实现设定的权重，不是训练结论或“推荐准确率”。

### 7.2 钻取、统一口径与关联统计

`analytics/AnalyticsService.java:23` 统一过滤器：以上海业务日转换 UTC 半开区间，channel_occurred_at 决定发生日，recorded_at≤asOf 决定该截面已获知事实。商户、门店、金额、维度白名单，门店所属关系校验。收入、退款分开求和，净收款=收入−退款。

`:120` 支持 merchant/store/year/month/day SQL 分组；汇总、分组、趋势、明细和交易 PDF/Word 报告都沿用统一过滤器及数据截止时间。它是关系数据库的多维聚合与钻取，不是 SQL Server Analysis Services 立方体。

`frontend/src/analyticsFilters.ts:22` 克隆父过滤器，并把年/月/日范围与父期间求交集，避免从一周钻到一个月时突然扩大范围：

```ts
const next = { ...parent };
next.start = start > parent.start ? start : parent.start;
next.end = end < parent.end ? end : parent.end;
if (next.start > next.end) throw new Error("该分组已不在当前统计范围内，请刷新分析");
```

年度→月度→日度通过维度改变，商户→门店通过商户编号筛选，最细一级查询资金流水，金额与 asOf 不丢失。整数金额和计数在前端保留字符串/BigInt，百分比零分母显示“—”而非误导的 0%。

对账统计在 `AnalyticsService.java:262` 以账期/事件最新结果去重，避免重复重跑放大差异数量；工单历史截面通过审计 after JSON 重建，缺少历史时显式 HISTORY_UNAVAILABLE。风险统计在 `RiskAnalyticsService.java` 分别聚合事件和工单后关联，避免一对多联接导致金额倍增；有差异率、待处理金额、账龄、超时、有效结案中位数/P95、门店排序等，但属于描述性运营指标，不是经过验证的自动反欺诈模型。

### 7.3 导出内容与授权

`analytics/ExportController.java:69` 四种格式 xlsx/xml/pdf/docx；PDF/Word **只支持交易统计**，商户和对账结果支持 Excel/XML，不能写“所有页面均支持四格式”。交易报告包含环境、期间、权限范围、金额范围、asOf、维度、口径、汇总、每 12 组一幅图和完整分组表。

`analytics/ReportService.java:72` DOCX 使用 POI，PDF 使用 PDFBox 与中文字体，JFreeChart 产生报告图表；Word 表头重复和页脚页码有代码实现。文件内容是否全部清晰仍须实际打开验证，不能只凭代码断言版式完善。

导出保存创建者、角色与商户范围，下载时重新验证当前账号与关系，避免已撤销成员继续下载旧导出；路径按 UUID 与允许扩展名解析，不接受任意路径。

## 8. 事务、幂等、并发、审计可引用的说明

| 场景 | 实际机制 | 为什么需要 |
|---|---|---|
| 创建/编辑订单 | Spring 事务、UUID、草稿状态、ROWVERSION | 订单头与行不可半写入，重复点击不产生新订单，陈旧编辑不能覆盖 |
| 发起付款 | 订单 UPDLOCK/HOLDLOCK、稳定支付号、短事务 | 同一待付订单竞态时复用尝试，不持锁等待渠道 |
| 渠道派发恢复 | 租约+随机令牌+状态条件、查询原请求 | 并发恢复和迟到响应不会重复派发或把成功降级 |
| 资金事实确认 | 存储过程事务、业务唯一键、成功源标识 | 重复通知/查询只产生一条对应资金事实 |
| 退款额度 | 先锁原支付、审批预留、成功确认重查 | 并发审批共享可退额度；未知状态不能提前释放 |
| 导入 | 文件hash+参数hash、事务型 applock、原始行保留 | 相同解释复用导入；错误/重复仍可追溯 |
| 对账 | 一致读取时点、独立新批次、稳定事件关联 | 可复现结果；重跑不覆盖历史、不重复派单 |
| 工单处理 | 受派人、状态、ROWVERSION 同时入 UPDATE 条件 | 改派、复核与上传同时发生时旧请求失败而非覆盖 |
| 独立复核 | 存储过程检查人员分离及引用证据 | 处理人与审核人不同，结论有事实依据 |
| 审计 | requestId、连接会话上下文、触发器 before/after、ACCESS/AUTH | 关联一次请求、操作者和数据变化；历史状态可追溯 |

需要避免的表述：HTTP 重试天然“恰好一次”、数据库与支付宝的分布式强事务、任何故障都不会产生中间状态、原文件 SHA 能证明业务内容真实。实际做法是本地事务与幂等约束、持久中间状态、查询补偿和留证。

## 9. 可追溯的历史支付宝联调

直接核读 `artifacts/validation/alipay-sandbox-verification-20261022.json`，时间为 **2026-10-22 11:46:40.2979639 +08:00**：

| 项目 | 历史记录 |
|---|---|
| 环境 | ALIPAY_SANDBOX |
| 支付 | paymentId=1、orderId=1、amountCent=1、SUCCEEDED |
| 退款 | refundId=1、amountCent=1、SUCCEEDED，idempotencyVerified=true |
| 资金事实 | RECEIPT 1 条共 1 分；REFUND 1 条共 1 分；净额=0，可退额=0，FULLY_REFUNDED |
| 账单任务 | jobId=1，1017 字节 ZIP，散列一致，TEMPLATE_ONLY |
| 模板边界 | templateStatementLines=0、templateReconciliationResults=0、未建业务批次、未建差错 |
| 实际业务账单 | realBusinessBillReconciliationVerified=false |
| 真实异步通知 | realAsyncNotificationVerified=false，configured=false |
| 实际支付确认方式 | ACTIVE_QUERY |

`alipay-sandbox-final-sql-check.json` 在 2026-10-22 11:45:51 再次记录模板账单行、结果、业务批次均为 0。以上可作为注明日期的历史联调依据。该 JSON 内 138 后端/5 前端测试为当时数量，不能计入本轮回归。旧 PID、端口、JAR hash 也不能直接当本次运行版本。

本次新测试若只针对 DEMO，测试章应分为“本次教学环境回归”和“已有沙箱联调记录”，分别给日期与来源。真实沙箱支付是支付宝测试环境支付，不称“生产实付成功”。

## 10. 设计调整与问题解决的真实材料

### 10.1 规划与最终实现的差异应如何写

`docs/design.md` 2026-10-19 的最初方案已规划 Java/Spring/Vue/SQL Server、15 张表、多角色、完整对账、退款、全文/比较/推荐、双格式导入导出和 PDF/Word，不能把这些说成后来才临时加进来的功能。可写成“从方案到实现，补充了以下具体约束与验证”，而不是虚构大规模技术选型替换：

- 从“重复请求幂等”细化为 UUID/渠道业务号/唯一资金源键、短事务锁、派发租约、迟到响应令牌防护和未知结果查询补偿。
- 从“自动采集”细化为持久账单队列、租约、有限重试、已完成子批次复用、创建者权限复查；同时因实际沙箱仅返回模板而明确 TEMPLATE_ONLY，不强行进入真实对账。
- 从“统计钻取”细化为发生时间与获知时间分离、asOf 截面、父子筛选交集、重跑去重、事件与工单独立聚合和历史缺口标注。
- 从“证据留存”细化为原件散列、范围再鉴权、共享原件隔离、档案清单、历史缺口和导出上限说明。
- 从“可运行页面”发展为公开产品首页、角色工作台、移动布局与统一交互；界面变更不改变核心数据模型及业务边界。
- 初期方案中“自动确认”和“独立复核”在最终实现中保留客观差错与人工结论两条线：工单闭合不篡改历史核对分类。

已有 `docs/reports/system-design.md` 也写出了上述部分细节，最终总结应保持其原文件不变，仅解释实现时落实与验证情况。

### 10.2 有证据的问题—定位—处理—效果

| 问题 | 可引用的实现或记录 | 实际解决与效果边界 |
|---|---|---|
| 预下单超时后用户重试造成重复请求或成功状态被迟到响应覆盖 | `PaymentService` 的 lease/token、precreate_count、原交易查询；`docs/implementation-status.md:25` | UNKNOWN 保留交易身份，先查询，令牌限定更新；由针对性测试验证，不能泛化为外部系统永不重复收款 |
| SQL Server 中文全文首次初始化或资源压力导致超时 | `FullTextReadiness`；已有全量测试与资源记录 | 异步预热、就绪状态与失败反馈，避免把初始化等待放进用户流程；内存压力仍需如实记录 |
| 旧沙箱账单下载被误当真实经营账单的风险 | `AutoReconciliationService:253`、2026-10-22 JSON | 下载与SHA校验保留，TEMPLATE_ONLY 终止业务导入，0差错；真实账单待渠道条件 |
| 旧筛选钻取扩大原日期或统计联接重复计数 | `analyticsFilters.ts`、`AnalyticsService`、`RiskAnalyticsService` | 子范围求交、共同asOf，资金/差错/工单分别聚合；新测试给具体守恒结果 |
| 两窗口改派/上传/保存同时操作覆盖新状态 | `CaseController` 条件UPDATE+ROWVERSION；实施记录:27 | 不符合新版本返回409，失败附件清理；独立复核不自审 |
| 账号停用后旧会话仍可访问、导出授权已变化 | `RequestAuditFilter` 与 `ExportController` | 请求及下载重新核验当前身份，变更及时生效 |
| Windows PowerShell 5.1 的配置编码/端口易误操作 | 历史沙箱 JSON environmentFixes 与启动脚本 | UTF-8 BOM、显式-Port和密钥路径预检；属历史本地环境问题，勿写为数据库算法创新 |
| 多个候选JAR造成运行与源码不一致 | 实施记录:29、独立发布目录和校验清单 | 发布快照、选择版本、hash与可回退；本次需再核验当前运行包 |

这些材料不证明某位学生具体完成了哪项工作；个人贡献需本人确认。

### 10.3 已知限制与可改进点

1. 无生产支付渠道、无真实多支付宝商户入驻、无钱包/充值/清算/提现/银行结算。
2. 真实签名公网回调未完成联调；现有历史沙箱支付主要通过主动查询确认。
3. 沙箱自动账单得到模板，真实交易账单自动对账尚无证据；DEMO 全流程不得替代此证据。
4. 当前本机 HTTP、证书信任、单应用进程、有限数据库连接是课程本地部署配置，不是生产高可用架构。
5. 自动任务使用数据库持久租约，但未证明多实例生产部署、灾备恢复目标或大规模压力性能。
6. 全文中文分词依赖 SQL Server 组件及资源，案例推荐为启发式；无语义模型训练或推荐效果测评。
7. 证据扩展名限制及SHA不等于恶意文件扫描或内容真实性鉴定；档案存在明确大小/行数上限。
8. 工单历史截面只在审计链完整时可准确重建，历史缺口显式标注；不虚构缺失状态。
9. REVIEWER 预下单等接口的精细能力应按实际说明，不能报告为绝对只读。

## 11. 本报告所需运行截图与操作建议

以下是取证清单，不是已经完成的截图声明：

| 画面 | 应保留的可读信息 | 证明点 |
|---|---|---|
| 首页/登录 | 产品名称、DEMO环境、登录输入区，避免账号密码 | 当前版本与进入路径 |
| 工作台 | 当前角色、真实统计范围、待处理入口 | 多角色入口及摘要 |
| 订单详情 | 订单/支付/退款状态与1条具体资金记录 | 状态不是纯前端文字 |
| 退款审核 | 申请人、审批状态、金额、独立审批结果 | 额度与独立性 |
| 导入预览 | 文件名、期间、完整声明、错误行/重复/归属 | 入库前校验 |
| 对账批次 | 输入来源、统计分类、实际差异行 | 双边核对结果 |
| 重跑比较 | 同事件 NEW/RESOLVED/CHANGED 的实际项 | 历史版本和比较 |
| 工单处理/复核 | 分派人、处理结论、引用证据、复核记录 | 流程闭合与审计 |
| 分析钻取 | 同一筛选范围下商户→门店→明细或年→月→日 | 粒度变化和金额守恒 |
| 知识检索 | 非空检索词、已结案相关案例、推荐原因 | 中文全文与推荐 |
| 审计 | actor、动作、结果、请求号与时间 | 访问和操作可追踪 |

截图使用当前运行包、同一演示数据，正文描述与画面保持一致；勿将过小全屏页面塞入报告。资金指标注意区分“分”和“元”。

## 12. 缺少的真实成员资料

`docs/reports/cover.json` 当前为：school/college/class_name/teacher 均空，members=[]，date=2026-10-26。没有可据以填写的真实学校、学院、班级、指导教师和三位成员信息。

`docs/design.md:691` 只有计划：A负责身份/商户/门店/成员/订单付款页；B负责支付通知/查询/退款/流水/沙箱；C负责账单对账/工单/分析/全文推荐/报告审计。最终总结必须询问三位真实姓名/学号及实际负责模块、共同工作，不能把该 A/B/C 计划直接签上姓名，也不能用 Git 作者自动认定实际贡献、工时或比例。

Git 历史可证明演进节点（不是个人分工）：2026-10-19 首次提交；2026-10-20 初版系统；2026-10-22 支付恢复、分析和多角色验收；2026-10-26 交付和构建准备；2026-11-02 角色工作台/移动界面/交付资料；2026-11-04 工作区公开首页及视觉升级。最终开发日期与报告签署日期应由组员确定。

## 13. 编写最终稿时的禁止误写清单

- 不把 Java 编译目标21写成当前运行时21；不混用驱动 jre11 后缀。
- 不把普通消费者写成单独数据库平台角色；不把复核员笼统写成全部 API 只读。
- 不把“页面隐藏按钮”当作后端权限机制；不声称审计在数据库故障时必然持久化成功。
- 不把 SHA256 写成证明账单内容来源真实，只能证明保存后字节一致；渠道真实性另由验签/可信查询处理。
- 不把教学模拟成功当成支付宝沙箱，更不当成生产支付。
- 不把沙箱模板下载当成真实账单对账；不把伪造回调拒绝当成真实签名回调通过。
- 不把所有业务报表都写成支持 PDF/Word；当前这两种仅用于交易统计。
- 不写专用 OLAP 系统、AI 推荐、自动反欺诈模型、消息队列/Redis/微服务等源码不存在的技术。
- 不把已实现功能、历史测试、本次实测三者混为一谈；跳过项不能计入通过。
- 不从计划分工或 Git 署名编造个人贡献，不更改初期设计报告来制造“前后完全一致”。
