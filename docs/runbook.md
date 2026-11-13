# Reconis 运行说明

## 环境与初始化

使用 Windows 10 / 11、PowerShell 7.4+、Node.js 24 和 SQL Server 2022 Developer（数据库引擎及全文组件）。`bootstrap.ps1` 准备便携 JDK 21、Maven 3.9.11 与 pnpm 依赖，工具保存在 `.tools/`。

在仓库根目录运行：

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1
.\scripts\initialize.ps1
.\scripts\start.ps1
```

尚未安装 SQL Server 时，先在管理员 PowerShell 中运行 `scripts/install-sqlserver.ps1`。脚本安装 RECONIS 实例和全文组件，配置本机 `127.0.0.1:1433`。初始化由拥有数据库创建权限的 Windows 账号执行。

已有 SQL Server 可指定地址：

```powershell
.\scripts\initialize.ps1 -Server '服务器,端口'
# 命名实例需同时指定应用连接的 TCP 端口
.\scripts\initialize.ps1 -Server '服务器\实例' -JdbcServer '服务器:端口'
```

访问 `http://localhost:8080`。教学账号与随机密码保存在 `private/demo-accounts.txt`，环境配置保存在 `private/DEMO.env.ps1`；配置键见 [`.env.example`](../.env.example)。本机配置完成后可双击 `启动Reconis.cmd`。

## 开发与构建

后端在 `backend/` 执行：

```powershell
. ..\private\DEMO.env.ps1
.\mvnw.cmd spring-boot:run
```

前端在 `frontend/` 执行：

```powershell
pnpm install --frozen-lockfile
pnpm dev
```

Vite 在 `5173` 提供页面，将 `/api` 代理到 `8080`。前端页面、公共组件和会话状态分别位于 `src/pages/`、`src/components/`、`src/stores/`；后端业务包位于 `backend/src/main/java/cn/reconis/`。

| 后端模块 | 职责 |
| --- | --- |
| `identity`、`merchant` | 账号、角色、商户、门店与成员 |
| `order`、`payment` | 订单、支付、退款和渠道通知 |
| `reconciliation`、`casework` | 账单、对账任务、工单和复核证据 |
| `analytics`、`common` | 分析、报表、文件访问与公共服务 |

`scripts/build.ps1` 构建前端与后端，生成包含静态页面的 JAR。发布版位于 `artifacts/releases/<ReleaseId>/`，附有程序包与源文件 SHA256 清单。使用 `-NoSelect` 构建后，可通过 `scripts/select-release.ps1 -ReleaseId <ReleaseId>` 切换默认版本，`-Rollback` 回退；重启后生效。

需要统一 Maven 下载源时可添加 `-MavenSettings backend/.mvn/central-settings.xml`。单独运行 `pnpm build` 可生成前端产物。

## 数据库与教学账单

| SQL 文件 | 内容 |
| --- | --- |
| `database/001_schema.sql` | 15 张关系表 |
| `database/002_constraints_indexes.sql` | 完整性约束与索引 |
| `database/003_views_functions_procedures.sql` | 视图、函数与业务过程 |
| `database/004_triggers_fulltext.sql` | 触发器与中文全文索引 |
| `database/005_bootstrap_admin.sql` | 初始管理员 |
| `database/migrations/` | 增量更新 |
| `database/seed_demo.sql`、`database/demo/` | 校园商户与教学交易 |

`initialize.ps1` 创建 ReconisDemo、执行 SQL 与教学种子；`migrate.ps1` 更新已有数据库。种子包含 7 家营业商户、1 家待审核商户、9 家门店、180 笔日常收款与 1 笔跨日部分退款订单。旧版商户资料可通过 `migrate.ps1 -RefreshCampusData` 刷新。

[`samples/statements/cross-day-demo.xml`](../samples/statements/cross-day-demo.xml) 展示 2026-10-18 收款 100 元、次日退款 30 元。种子按初始化时的相对日期生成；核对当前账套时，可从分析页导出交易 XML 作为输入。

登录后可通过 `/api/v1/templates?kind=statement&format=xlsx` 下载账单模板，`format=xml` 下载 XML；商户模板使用 `kind=merchants`。XLSX 金额使用整数分，标识和时间使用文本。重导同一文件复用输入批次，重新对账生成独立结果。

## 支付宝沙箱

```powershell
.\scripts\initialize.ps1 -Environment ALIPAY_SANDBOX
.\scripts\start.ps1 -Environment ALIPAY_SANDBOX -Port 8081
```

沙箱使用 ReconisSandbox 与独立文件目录，初始管理员密码位于 `private/sandbox-admin.txt`。在 `private/ALIPAY_SANDBOX.env.ps1` 配置 `ALIPAY_APP_ID`、`ALIPAY_SELLER_ID`、`ALIPAY_PRIVATE_KEY_FILE`、`ALIPAY_PUBLIC_KEY_FILE` 和 `ALIPAY_NOTIFY_URL`。私钥为测试应用私钥，公钥为支付宝公钥，支持 PEM 或 Base64 文件。

通知地址设为公网 HTTPS 的 `/api/v1/callbacks/alipay`，将原始 POST 表单转发到 `http://127.0.0.1:8081/api/v1/callbacks/alipay`。配置后重启并新建支付；主动查询也可恢复支付与退款状态。

## 运行、迁移与备份

| 命令 | 用途 |
| --- | --- |
| `scripts/start.ps1 -CheckOnly` | 检查程序包与数据库连接 |
| `scripts/migrate.ps1 -Environment DEMO` | 升级教学数据库 |
| `scripts/migrate.ps1 -Environment ALIPAY_SANDBOX` | 升级沙箱数据库 |
| `scripts/backup.ps1 -BackupDirectory '备份目录'` | COPY_ONLY + CHECKSUM 备份及 RESTORE VERIFYONLY |
| `scripts/select-release.ps1 -List` | 查看本地发布版本 |
| `scripts/package.ps1` | 从 Git HEAD 打包源码 ZIP |

迁移前备份数据库。备份目录需允许 SQL Server 服务账号写入，沙箱备份添加 `-Environment ALIPAY_SANDBOX`。数据库与 `data/` 中的账单、附件分别保存；恢复后重新映射运行登录并生成本机配置。

`GET /api/v1/health` 返回数据库连接状态、实际发布版本与构建源文件散列。显式 `-JarFile`、`-ReleaseId` 和 `RECONIS_JAR_FILE` 优先于默认发布版。局域网部署开放应用端口；HTTPS 部署设置 `COOKIE_SECURE=true`。

## 按需验证

`frontend/tests/` 保存单元测试，`frontend/e2e/` 保存浏览器流程，后端测试位于 `backend/src/test/`。

| 命令 | 检查范围 |
| --- | --- |
| 在 `frontend/` 运行 `pnpm test` | 前端逻辑 |
| `scripts/build.ps1 -DatabaseTests` | 构建与真实 SQL Server 集成测试 |
| `scripts/verify-business-flow.ps1` | DEMO 接口业务链 |
| `scripts/verify-browser.ps1` | DEMO 页面交互 |
| `scripts/test-alipay-callback.ps1 -CallbackUrl '准确通知地址'` | 通知入口的无签名请求处理 |

浏览器首次使用需在 `frontend/` 运行 `pnpm exec playwright install chromium`。验证结果保存在 `artifacts/validation/`；渠道支付、通知与账单联调分别按实际渠道结果核对。
