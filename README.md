# Reconis

校园多商户跨日支付对账与差错协同处理系统。

围绕一次校园收款，串联订单、支付确认、部分退款、渠道账单、历史对账和差错复核。依据《数据库系统原理》课程设计指导手册建设，采用 **Spring Boot + MyBatis + SQL Server + Vue 3**。

## 工程能力

- 中文工作台，商户/门店/成员管理，账号注册审核，订单草稿与明细，手机付款页。
- DEMO 与支付宝沙箱适配器，预下单、通知验签、定时查询恢复，部分/全额退款和独立审批。
- XLSX/XML 账单与商户资料导入导出，预览、原始行保留、重复和错误反馈。
- 逐笔对账、历史运行、证据双栏、重跑比较、派单、处理与独立复核。
- SQL Server 原生全文、相似解决案例、商户/门店及年/月/日钻取、多表关联统计。
- ECharts 图表、PDF/Word 图文报告、访问及操作审计。
- 15 张关系表、5 个业务过程、2 个函数、9 个触发器、外模式与运行角色。

**2026-11-02 更新：**已同步课程收尾分支，完成 SQL Server 增量升级和完整构建，26 组 DEMO 业务流程通过。新增消费者概览、商户与时间筛选、实时待办和手机订单卡片，统一界面字号与中文文案。正式 Word、PDF 报告见[课程报告](docs/reports/final)。

![Reconis 实际运行工作台](docs/assets/workspace.png)

## 本机运行

使用 PowerShell 7.4+、Node.js 24 LTS。仓库不包含固定密码或支付密钥。

```powershell
# 仓库根目录
.\scripts\bootstrap.ps1
.\scripts\build.ps1

# 管理员 PowerShell：SQL Server 2022 Developer + 全文组件
.\scripts\install-sqlserver.ps1

# 使用本机 Windows 数据库管理员身份初始化一次
.\scripts\initialize.ps1
.\scripts\start.ps1
```

打开 `http://localhost:8080`。教学账号与随机密码仅位于 `private/demo-accounts.txt`。开发时在 `frontend` 运行 `pnpm dev`，地址为 `http://127.0.0.1:5173`。

已完成本地配置、数据库初始化和构建的电脑可运行 `启动Reconis.cmd`。只有源码的目录需先按上方步骤准备环境。

初始化保留已有数据库，不自动清库。详细配置、沙箱接入、备份及中断处理见[运行说明](docs/runbook.md)。

## 结构

| 路径 | 内容 |
| --- | --- |
| backend/ | JDK21、Spring Boot4.1.1、MyBatis4.0.0，Maven Wrapper |
| frontend/ | Vue3、TypeScript、Element Plus、ECharts，pnpm锁文件 |
| database/ | 15表、约束索引、视图函数过程、触发器全文与教学种子 |
| scripts/ | 构建、安装、初始化、启动、备份和打包 |
| docs/design.md | 完整设计基线与关系模式 |
| docs/reports/ | 设计与实现报告源稿、正式 Word/PDF、演示讲稿 |
| docs/presentation/ | 答辩 PPT 的结构化源稿与证据规划 |
| docs/handover.md | 提交内容、重建方法与剩余验收条件 |
| samples/ | 标准账单教学样例和模板说明 |
| .codex/CODEX.md | 项目实施记忆 |

金额为整数分，API金额与ID为字符串；时间存UTC，业务日为上海时区。流水、原始账单、历史结果不可改。退款审批锁定原支付检查额度，外部渠道调用在短事务之外。

昨日收100元、今日退30元，两日分别统计100元和−30元，合计70元。退款仍关联原支付；重跑建立新批次，保留旧观察。

全部基本及高级项入口见[要求对照](docs/requirements-matrix.md)。课程评分为设计报告20%、实现与报告50%、演示30%，高级功能需最终实际演示。

两份报告按“标题、目录、正文”编排，采用黑体标题、A4 页面和自动目录。生成命令与部署步骤见[交付说明](docs/handover.md)。
