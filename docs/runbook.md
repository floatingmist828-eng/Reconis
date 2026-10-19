# Reconis 运行说明

## 环境与启动

Windows10/11、PowerShell7.4+、Node.js24 LTS、SQL Server2022 Developer（引擎和全文）。不使用Express LocalDB代替全文环境。默认数据库只监听本机1433，不开放防火墙。

1. 仓库根目录运行 `scripts/bootstrap.ps1`、`scripts/build.ps1`，生成包含前端的JAR。工具位于忽略的.tools，Maven Wrapper固定3.9.11，依赖首次下载需网络。
2. 管理员PowerShell运行 `scripts/install-sqlserver.ps1`，校验Microsoft签名，下载官方ISO，创建RECONIS实例并安装FULLTEXT，启用混合认证，仅启用127.0.0.1:1433。不会修改已有MySQL。
3. 安装时获得数据库管理员身份的Windows账号运行 `scripts/initialize.ps1`，创建ReconisDemo、启用SNAPSHOT，执行001–004及教学种子。密码经stdin生成BCrypt散列，不作为shell参数输出。
4. `scripts/start.ps1`，打开 `http://localhost:8080`。初始账号与随机密码在 `private/demo-accounts.txt`，不能提交或截图。

已有SQL Server可运行 `scripts/initialize.ps1 -Server '服务器\实例'`。脚本生成的JDBC默认指向本机1433，远程环境请修改private/DEMO.env.ps1的地址；数据库名仍为ReconisDemo、目录仍为data/demo。

## 开发与部署

后端在backend加载 `. ..\private\DEMO.env.ps1` 后运行 `mvnw.cmd spring-boot:run`。前端在frontend运行 `pnpm dev`，Vite将/api代理到8080。

build.ps1构建前端并复制到后端静态目录，再打包JAR。单包使用同源Cookie Session和CSRF；HTTPS部署设置COOKIE_SECURE=true。局域网演示只对需要的客户端开放应用8080，数据库保持本机访问。

## 支付宝沙箱

运行 `initialize.ps1 -Environment ALIPAY_SANDBOX`，建立独立ReconisSandbox，初始管理员密码在private/sandbox-admin.txt；不导入教学交易。private/ALIPAY_SANDBOX.env.ps1追加ALIPAY_APP_ID、ALIPAY_SELLER_ID、ALIPAY_PRIVATE_KEY_FILE、ALIPAY_PUBLIC_KEY_FILE、ALIPAY_NOTIFY_URL。

私钥是测试应用私钥，公钥是支付宝公钥，支持PEM或纯Base64文件。使用 `start.ps1 -Environment ALIPAY_SANDBOX`。notify-url应指向可被测试渠道访问的/api/v1/callbacks/alipay；localhost不是可用公网回调的证据。接入页“已配置”只代表文件和参数齐全，真实收款退款与查询仍需实际验证。

## 教学数据与账单

seed_demo.sql提供3家启用商户、5门店、180笔历史收款、部分跨日退款、固定100/30元跨日例、异常账单和工单。标识均含DEMO。

账单先下载Excel/XML模板；分析页导出交易也会生成相同语义的标准账单。手工声明完整不同于渠道确认完整。错误行不进入匹配集，重复业务键的原始行保留。SHA256重导返回原批次，重跑产生独立结果。

## 初始化中断和备份

初始化不会删库，也不会重复执行种子。出错时保留报错与SQL日志，确认最后成功的GO批次，用部署账号在现有库继续缺失批次。不要删库掩盖错误。运行账号只属于reconis_runtime，不是db_owner。

`/api/v1/health`返回UP仅表示HTTP服务启动，不代表数据库和全文可用。

`scripts/backup.ps1 -BackupDirectory '已有且SQL服务账号可写目录'` 生成COPY_ONLY+CHECKSUM备份并执行RESTORE VERIFYONLY。沙箱加 `-Environment ALIPAY_SANDBOX`。数据库备份和data目录的原件/证据分别保存，不默认提交GitHub。

提交后用package.ps1从Git HEAD生成源码ZIP并输出SHA256，仅包含已跟踪文件；课程数据库备份通过私有提交渠道另附。

恢复到新实例时，先恢复bak及配套data文件，再创建新的运行登录并用`ALTER USER ... WITH LOGIN ...`映射库内运行用户，使用新生成的本机配置。不要沿用另一台电脑的登录密码或假设备份包含文件目录。也可直接从版本SQL和教学种子重建。
