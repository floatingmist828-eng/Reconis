# Reconis 系统实现总结报告

本稿说明已经运行的工程与实现方式。完整执行记录见implementation-status.md，官方沙箱联调待配置应用密钥。

## 1 实现环境

JDK21、Spring Boot4.1.1、MyBatis Starter4.0.0、Microsoft JDBC12.10.2、SQL Server2022 Developer和全文组件；Vue3.5.22、TypeScript5.9.3、Vite7.1.12、Element Plus2.11.5、ECharts6.0.0。Maven3.9.11和pnpm11.25.0固定依赖。支付宝SDK4.40.272.ALL；POI5.4.1、PDFBox3.0.5、JFreeChart1.5.6。

## 2 功能结构

```mermaid
flowchart TB
 R[Reconis] --> I[身份 账号审核 商户 门店 成员]
 R --> T[订单 支付 通知 查询 退款]
 R --> C[账单 逐笔对账 历史比较]
 R --> W[派单 分析 证据 提交 复核]
 R --> A[全文 推荐 钻取 关联 报告]
 R --> S[日志 权限 环境分离]
```

前端包含登录、工作台、商户、订单及详情、退款、对账、工单及详情、案例检索、分析、审计、账号审核和接入状态14个业务页面。列表提供筛选与分页，订单和工单联动到来源详情。

## 3 表、约束与索引

001_schema.sql建立15表，002建立过滤唯一和查询索引。订单按门店/创建时间、支付退款按状态/下次查询、流水按实际发生时间、结果按批次/分类、工单按处理人/状态/期限、日志按商户和人员/时间建立索引。

transaction_entry的收款与退款FK恰好一个非空；各源过滤唯一。payment_attempt仅对is_primary=1过滤唯一，不拒绝保存额外成功。statement_line保留重复原行，result保存每次观察的两侧FK。order_item不保存金额小计，business_order不保存可漂移合计。

## 4 外模式与安全实现

v_consumer_order、v_merchant_transaction、v_reconciliation_summary、v_case_search、v_audit_summary分别服务本人订单、商户资金、对账、案例和审计。Access在接口内对详情、推荐、统计和写操作做行范围检查；RequestAuditFilter逐请求重新读取账号状态和平台角色，商户成员关系实时查询。

SessionContextPlugin拦截MyBatis Executor实际连接，设置user_id、role、merchant_id和request_id，执行后清理。禁用MyBatis二级缓存，一级缓存按语句，防止缓存跨范围复用。文件侧车保存创建者和范围，下载复核当前角色和商户资格。XML禁用外部实体，Excel按文本输出标识，密码/密钥不写日志。

## 5 数据库服务器端程序

| 对象 | 实际入口与职责 |
| --- | --- |
| usp_confirm_payment | 查询或可信通知恢复，锁订单后确认支付并写唯一收款流水 |
| usp_approve_refund | 退款审批，锁原支付、校验独立审批与余额，批准状态占额度 |
| usp_confirm_refund | 退款渠道确认，幂等状态更新和唯一退款流水 |
| usp_run_reconciliation | 对账/重跑接口，SNAPSHOT读取、分类、固化结果、复用工单 |
| usp_review_exception | 独立复核接口，校验人员、结论和匹配证据，关闭或退回 |
| fn_refundable_amount | 详情、退款列表及审批过程中的剩余可退计算 |
| fn_case_overdue_days | 工单详情和处理效率统计中的超期天数 |
| trg_refund_audit / trg_case_audit | inserted/deleted集合审计，和业务事务共同提交 |
| trg_merchant_review_audit / trg_user_review_audit | 资料审核与权限变更快照，排除密码 |
| 事实保护与订单明细冻结触发器 | 阻止修改流水、原始账单、历史结果和已发起支付的明细 |

过程自管事务，Java不套外层事务；普通资料和草稿用Spring事务。外部退款调用不放入金额锁事务，UNKNOWN保留预留，使用原请求号查询恢复。

## 6 主要模块

PaymentGateway封装DEMO和官方沙箱，DEMO渠道记录单独持久化；PaymentService完成请求幂等、查询和定时恢复。签名回调校验应用、收款方和金额，并通过官方查询确认；浏览器跳转不会改变支付结果。

ImportService统一XLSX/XML模型，校验环境、商户、金额和带时区时间，预览有效/重复/错误行。原件SHA256用于重导复用，错误清单与原件归档。对账优先识别重复/缺键，再FULL OUTER JOIN，原件不完整时本地单边标为待确认。

工单按稳定case_key复用，保留起因和最新证据；分派处理、上传附件、提交和独立复核。FREETEXTTABLE检索中文长文本，推荐仅含可见已结案案例，按差错类型、业务环节与归一文本分数排序并返回理由。

AnalyticsService在流水层先聚合，再关联商户门店，避免通知和工单导致金额重复。商户/门店、年/月/日均可钻取到事实明细。PDF与Word使用同一ReportData，JFreeChart从服务端数据生成图，POI/PDFBox生成文件，下载入口检查权限。

## 7 运行实例与证据

本机已安装SQL Server2022 Developer和全文组件，15表、5过程、2函数、8触发器实际创建成功。前后端构建及单包启动通过，docs/assets包含真实工作台、对账双栏和手机订单画面。

实际DEMO联调中，100元收款重复通知后只有1条流水；30元退款后剩余70元，两笔60元审批并发时一笔通过、一笔409。跨日例分别得到10000分与−3000分。84条标准账单在原运行和重跑中分别保存84条结果，比较均为UNCHANGED，重导返回原批次。XML预览84条有效、0错误。另一商户读取订单返回404。工单由不同处理人与复核员使用新匹配证据关闭；全文检索命中3条，推荐返回2条。四种格式已生成并下载，PDF已检查中文与图表。官方沙箱仍需独立联调证据。

## 8 源程序与交付

后端按identity、merchant、order、payment、reconciliation、casework、analytics、common分包。所有SQL参数通过MyBatis绑定，动态排序来自服务端白名单；不存在接收用户SQL的接口。前端使用路由懒加载、Pinia会话、共用API和状态标签。

scripts提供工具准备、单包构建、管理员SQL安装、建库、启动、CHECKSUM备份和Git源码打包。DEMO与沙箱分数据库/目录，私有配置、原件、日志和工具均忽略。实际数据库备份在建库后生成。

## 9 实现体会

本项目将“业务订单”“渠道观察”“已确认资金事实”和“历史核对结果”分开，避免通过覆盖金额消除差错。事务只能原子保护本地数据，网络结果需以稳定请求号和查询恢复衔接。规范化模型与不可变来源让跨日统计、历史比较和审计能够共用同一事实。
