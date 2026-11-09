# Reconis 接口说明

接口前缀为 `/api/v1`，随应用部署在同一域名下。接口使用 Cookie Session；普通业务写请求需要 CSRF 令牌，支付宝通知使用签名校验。

## 登录与请求

1. 请求 `GET /api/v1/auth/csrf`，保存响应 Cookie，并读取 `data.token` 和 `data.headerName`。
2. 向 `POST /api/v1/auth/login` 发送 JSON：`{"username":"用户名","password":"密码"}`，在响应指定的请求头中携带 CSRF 令牌。
3. 后续请求继续携带 Cookie。可通过 `GET /api/v1/auth/me` 获取账号、平台角色和商户成员关系。
4. `POST /api/v1/auth/logout` 注销会话。会话失效后重新获取令牌并登录。

账号注册入口为 `POST /api/v1/auth/register`，字段为 `username`、`password` 和 `displayName`；新账号需管理员审核启用。

普通 JSON 成功响应包含 `data` 与 `requestId`；失败响应包含 `code`、`message` 与 `requestId`。文件下载返回文件内容，支付宝通知返回纯文本 `success` 或 `failure`。发生错误时保留请求编号，便于关联服务日志。

| HTTP 状态 | 含义与处理 |
| --- | --- |
| 401 | 登录失效或凭据错误，重新登录 |
| 403 | 权限不足、账号未启用或 CSRF 校验失败，检查角色和令牌 |
| 409 | 状态、数据约束或并发冲突，刷新数据后按提示处理 |
| 422 | 参数或文件格式不合法，修正输入 |
| 503 | 数据库等依赖暂时不可用，稍后重试 |

## 主要入口

下表列出常用入口。查询参数、请求字段和权限条件见对应控制器；列表查询由服务端执行商户及角色范围限制。

| 模块 | 路径（省略 `/api/v1`） | 实现 |
| --- | --- | --- |
| 身份与成员权限 | `/auth/*`、`/admin/users` | [IdentityController](../backend/src/main/java/cn/reconis/identity/IdentityController.java) |
| 商户与门店 | `/merchants`、`/merchants/{id}/stores` | [商户模块](../backend/src/main/java/cn/reconis/merchant/) |
| 订单 | `/orders`、`/orders/{id}`，提交与取消使用 `POST /orders/{id}/submit`、`POST /orders/{id}/cancel` | [订单模块](../backend/src/main/java/cn/reconis/order/) |
| 支付与退款 | `/orders/{id}/payments`、`/payments/{id}/sync`、`/payments/{id}/refunds`、`/refunds` | [支付模块](../backend/src/main/java/cn/reconis/payment/) |
| 账单与对账 | `/reconciliation/imports`、`/reconciliation/batches`、`/reconciliation/automatic` | [对账模块](../backend/src/main/java/cn/reconis/reconciliation/) |
| 差错处理 | `/cases`、`/cases/search`、`/cases/{id}`，以及分派、证据、提交、复核入口 | [工单模块](../backend/src/main/java/cn/reconis/casework/) |
| 分析与审计 | `/dashboard`、`/analytics/transactions`、`/audit/events` | [分析模块](../backend/src/main/java/cn/reconis/analytics/) |
| 文件与导出 | `/templates`、`/exports`、`/files/{id}` | [ExportController](../backend/src/main/java/cn/reconis/analytics/ExportController.java) |

## 数据约定

- 金额以整数分传递和存储。Java `Long` 和 `BigInteger` 在 JSON 中序列化为字符串，客户端应保留精度。
- 时间输入采用带时区的 ISO 8601 格式；数据库时间按 UTC 保存，业务日按北京时间计算。
- 使用 `clientRequestId` 的写接口要求 UUID。重试同一次操作时保留原值，发起新操作时生成新值。
- 需要乐观锁的更新应携带读取时的版本值；冲突时重新获取记录，再决定是否提交。
- 上传文件需符合接口允许的格式和大小。工单附件、账单原件及下载均受访问权限控制。

## 支付通知与运行检查

支付宝通知入口为 `POST /api/v1/callbacks/alipay`，接收原始表单并校验签名及业务字段。公网 HTTPS 配置和沙箱运行方法见[运行说明](runbook.md#支付宝沙箱)。

`GET /api/v1/health` 检查应用与数据库连接；全文检索、支付渠道和回调链路需分别检查。
