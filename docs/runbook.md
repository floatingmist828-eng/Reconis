# Reconis 运行说明

## 环境与启动

Windows10/11、PowerShell7.4+、Node.js24 LTS、SQL Server2022 Developer（引擎和全文）。不使用Express LocalDB代替全文环境。默认数据库只监听本机1433，不开放防火墙。

1. 仓库根目录运行 `scripts/bootstrap.ps1`、`scripts/build.ps1`，生成包含前端的JAR。工具位于忽略的.tools，Maven Wrapper固定3.9.11，依赖首次下载需网络。
2. 管理员PowerShell运行 `scripts/install-sqlserver.ps1`，校验Microsoft签名，下载官方ISO，创建RECONIS实例并安装FULLTEXT，启用混合认证，仅启用127.0.0.1:1433。不会修改已有MySQL。
3. 安装时获得数据库管理员身份的Windows账号运行 `scripts/initialize.ps1`，创建ReconisDemo、启用SNAPSHOT，执行001–004及教学种子。密码经stdin生成BCrypt散列，不作为shell参数输出。
4. `scripts/start.ps1`，打开 `http://localhost:8080`。初始账号与随机密码在 `private/demo-accounts.txt`，不能提交或截图。

已有 SQL Server 可运行 `scripts/initialize.ps1 -Server '服务器,端口'`，脚本据此生成对应 JDBC 地址。使用命名实例时，运行 `scripts/initialize.ps1 -Server '服务器\实例' -JdbcServer '服务器:TCP端口'`，明确应用使用的 TCP 端口。DEMO 数据库名为 ReconisDemo，文件目录为 data/demo；沙箱使用独立数据库和目录。

## 开发与部署

后端在backend加载 `. ..\private\DEMO.env.ps1` 后运行 `mvnw.cmd spring-boot:run`。前端在frontend运行 `pnpm dev`，Vite将/api代理到8080。

`scripts/build.ps1 -DatabaseTests` 在独立目录保存源码快照，构建前端和后端，并运行真实 SQL Server 集成测试。发布包存放于 `artifacts/releases/<ReleaseId>`，附带程序包与源文件 SHA256 清单；构建不会覆盖正在运行的 JAR。使用 `-NoSelect` 可先构建验收，再执行 `scripts/select-release.ps1 -ReleaseId <ReleaseId>` 切换默认版本；已有上一版时可用 `-Rollback` 回退。切换默认版本后须重启进程，健康接口中的 `releaseId` 和 `buildSourceSha256` 用于核对实际运行版本。

若传递依赖的仓库配置使构建反复访问 snapshot/milestone 地址，可加 `-MavenSettings backend/.mvn/central-settings.xml`，仅从官方 Maven Central 取得相同的固定发行依赖。本次源码副本的验证使用该配置；未改变 pom 或依赖版本。

DEMO 使用 `scripts/start.ps1 -Environment DEMO -Port 8080`，沙箱使用 `scripts/start.ps1 -Environment ALIPAY_SANDBOX -Port 8081`；两者默认读取同一个发布版本，数据库和文件目录分别隔离。`-CheckOnly` 可检查程序包散列与数据库连接。显式 `-JarFile`、`-ReleaseId` 和本地配置中的 `RECONIS_JAR_FILE` 优先于默认版本，统一部署时应移除过期覆盖配置。

单包使用同源 Cookie Session 和 CSRF；HTTPS 部署设置 `COOKIE_SECURE=true`。局域网使用时只对需要的客户端开放应用端口，数据库保持本机访问。

## 支付宝沙箱

运行 `initialize.ps1 -Environment ALIPAY_SANDBOX`，建立独立ReconisSandbox，初始管理员密码在private/sandbox-admin.txt；不导入教学交易。private/ALIPAY_SANDBOX.env.ps1追加ALIPAY_APP_ID、ALIPAY_SELLER_ID、ALIPAY_PRIVATE_KEY_FILE、ALIPAY_PUBLIC_KEY_FILE、ALIPAY_NOTIFY_URL。

私钥是测试应用私钥，公钥是支付宝公钥，支持PEM或纯Base64文件。使用 `start.ps1 -Environment ALIPAY_SANDBOX -Port 8081`。通知地址使用标准 HTTPS 443 入口和有效证书，准确路径为 `/api/v1/callbacks/alipay`，不带查询参数、片段或登录信息。例如在已有 `private/ALIPAY_SANDBOX.env.ps1` 中设置 `$env:ALIPAY_NOTIFY_URL='https://你的公网域名/api/v1/callbacks/alipay'`，将该路径转发至 `http://127.0.0.1:8081/api/v1/callbacks/alipay`，并保持原始 POST 表单、禁止重定向、网页登录或验证码；可以仅开放此路径。远程服务器入口需要与本机之间有效的转发通道。重启后新建支付才会携带新通知地址。localhost 不是可用公网回调的证据；本地配置检查也不能证明支付宝服务器实际可达。未配置公网入口时仍可通过主动查询确认支付和退款。

使用 PowerShell 7.4 或以上执行 `./scripts/test-alipay-callback.ps1 -CallbackUrl 'http://127.0.0.1:8081/api/v1/callbacks/alipay' -DatabaseName ReconisSandbox` 可检查本地入口及对应 SQL 拒绝记录。公网入口准备后，把 `CallbackUrl` 换成准确的 HTTPS 地址。脚本只向明确提供的地址发一次无签名测试表单，保留 TLS 证书验证、不跟随跳转，要求返回纯文本 `failure`；指定 `DatabaseName` 时还会检查该测试未创建支付或收款。该检查不代表真实支付宝通知已验收，最终仍须观察一笔配置之后新建的沙箱支付产生有效通知。

## 教学数据与账单

seed_demo.sql提供3家启用商户、5门店、180笔历史收款、部分跨日退款、固定100/30元跨日例、异常账单和工单。标识均含DEMO。

账单先下载Excel/XML模板；分析页导出交易也会生成相同语义的标准账单。手工声明完整不同于渠道确认完整。错误行不进入匹配集，重复业务键的原始行保留。SHA256重导返回原批次，重跑产生独立结果。

## 初始化中断和备份

初始化不会删库，也不会重复执行种子。出错时保留报错与SQL日志，确认最后成功的GO批次，用部署账号在现有库继续缺失批次。不要删库掩盖错误。运行账号只属于reconis_runtime，不是db_owner。

`/api/v1/health` 返回 UP 表示应用能够连接数据库并完成 `SELECT 1`，同时返回实际发布版本及构建源文件散列。全文索引状态需在检索页面单独检查；健康接口也不表示支付宝公网接口或通知通道可用。

`scripts/backup.ps1 -BackupDirectory '已有且SQL服务账号可写目录'` 生成COPY_ONLY+CHECKSUM备份并执行RESTORE VERIFYONLY。沙箱加 `-Environment ALIPAY_SANDBOX`。数据库备份和data目录的原件/证据分别保存，不默认提交GitHub。

提交后用package.ps1从Git HEAD生成源码ZIP并输出SHA256，仅包含已跟踪文件；课程数据库备份通过私有提交渠道另附。

恢复到新实例时，先恢复bak及配套data文件，再创建新的运行登录并用`ALTER USER ... WITH LOGIN ...`映射库内运行用户，使用新生成的本机配置。不要沿用另一台电脑的登录密码或假设备份包含文件目录。也可直接从版本SQL和教学种子重建。

## 回归验证

已有数据库升级前先备份，再分别运行 `scripts/migrate.ps1 -Environment DEMO` 和 `scripts/migrate.ps1 -Environment ALIPAY_SANDBOX`。迁移保留原始资金事实与审核历史。

程序启动后，在 PowerShell 7.4 以上运行 `scripts/verify-business-flow.ps1` 验证接口业务链；运行 `scripts/verify-browser.ps1` 验证实际页面。浏览器验证首次需在 frontend 安装 Playwright Chromium（`pnpm exec playwright install chromium`）。两套业务验收仅针对 DEMO，使用独立临时账号与商户，完成后停用测试账号，保留资金流水与审计证据。结果保存于忽略的 `artifacts/validation`。验收账号密码由本机 private 配置读取，不写入测试结果。
