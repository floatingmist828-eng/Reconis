# 教学样例与模板

cross-day-demo.xml为固定日期教学例：2026-10-18收100元、2026-10-19退30元。若其他日期初始化，seed按相对昨日/今日生成，应从分析页导出当时交易XML再导入。

登录后访问 `/api/v1/templates?kind=statement&format=xlsx` 下载Excel模板，format=xml下载XML；商户资料使用kind=merchants。由服务端组件生成，避免模板漂移。

XLSX金额是整数分，标识与时间使用文本，禁止公式单元格。XML禁用DTD和外部实体。所有教学记录明确标为DEMO。
