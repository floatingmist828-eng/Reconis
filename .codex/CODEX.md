# Reconis 项目记忆

更新：2026-10-20

## 当前执行结果

- 已自行安装SQL Server2022 Developer+FULLTEXT，实例RECONIS运行；ReconisDemo初始化成功，15表/5过程/2函数/8触发器实查一致。
- 实际DEMO闭环已通过：通知丢失恢复和重复幂等、部分退款、并发额度一通过一409、84条对账重跑留史、独立复核、全文3条、推荐2条、四格式导出、跨商户404、跨日10000/-3000分。
- Runtime读取FULLTEXTCATALOGPROPERTY需全文目录VIEW DEFINITION；已在004授予并在查询中COALESCE为未知值，不能让NULL-only映射导致空行NPE。
- 手机Grid用1fr时子项min-width:auto可撑宽订单表格；detail-stack加min-width:0后390视口无横向溢出。
- 已有本地真实数据库备份artifacts/ReconisDemo.bak，COPY_ONLY+CHECKSUM+RESTORE VERIFYONLY通过；文件目录另备份，private凭据不打包。
- 当前待办仅官方支付宝沙箱真实联调凭据、课程最终排版与PPT；本轮完成源码和运行交付并推送GitHub。

- 用户已授权完整搭建并最终推送现有GitHub仓库；禁止MVP，集中做真实完整业务，不堆测试与gate。
- 页面文案须真实、简短，严禁防御性说明与机械AI口吻；环境只用清晰标签，技术和证据边界放运行文档。视觉为深绿侧栏、浅色工作区、青绿强调。
- 根目录设计为基线，代码在re。SQL Server2022 Developer+全文，15关系、5业务过程、2函数；JDK21/Boot4.1.1/MyBatis4、Vue3/TS/ElementPlus/ECharts。
- 金额分BIGINT，API金额/ID字符串；页面输入人民币元，BigInt精确转换。UTC存储，上海业务日。
- 已确认流水、原始账单、历史结果不可改；退款审批锁原支付防超额，网络调用在本地短事务外；结果未知保留预留。
- 事实先聚合再关联；重跑复用原输入、固化新结果，同事件同类差错只一工单；重复输入的多条结果要用ROW_NUMBER选择单个工单起因。
- MyBatis在实际Executor连接设置/清理身份和授权商户上下文，禁二级缓存，一级按语句。权限按当前账号及有效成员查询，文件下载复核当前权限。
- DEMO/ALIPAY_SANDBOX分库和目录；不提交private、data、.tools、target、dist、密钥、日志、备份。
- 本机最初没有Java/Maven/SQLServer，便携JDK/Maven已下载，前后端已成功构建。用户后续授权所需软件自行安装，已尝试管理员SQL安装。
- 旧aka.ms/sqlserver2022developer下载器过期；改用Microsoft官方ISO直链（见install-sqlserver.ps1），安装前校验ISO内setup.exe微软签名。
- PowerShell传Maven -D参数要整段加引号，否则.repo.local等可能被拆成生命周期参数。
- 本仓库原README是UTF16，改成UTF8后才可apply_patch。源码统一UTF8。
- 课程报告源稿与演示讲稿在docs/reports；真实SQL运行、沙箱和最终PPT/截图状态须以docs/implementation-status.md更新，不夸大源码构建证据。
