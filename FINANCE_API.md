# 财务模块数据库与接口

交互文档入口和调用步骤见 [SPRINGDOC_GUIDE.md](SPRINGDOC_GUIDE.md)，整体结构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

后端以同级目录 `erp-react-web` 的登录页、财务工作台及 35 个业务页为依据。React 原型用接口说明弹窗描述业务操作，接入时可按下表把页面路由映射到真实 API。

## 初始化

1. 创建一个字符集为 `utf8mb4` 的 MariaDB 数据库，设置 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD`。现有实例也可以应用同一个脚本。
2. 用部署账号执行 [finance-schema.sql](src/main/resources/db/finance-schema.sql)。开发环境也可设置 `ERP_SCHEMA_INIT=always`，由 Spring Boot 启动时执行建表脚本。脚本使用 `CREATE TABLE IF NOT EXISTS`，表结构升级需要执行对应迁移。
3. 首次启动时通过环境变量 `ERP_BOOTSTRAP_PASSWORD` 提供至少 12 字符的初始密码。管理员账号默认是 `admin`，可通过 `ERP_BOOTSTRAP_USERNAME` 设置。管理员创建成功后可移除密码环境变量。登录使用初始化时设置的密码。
4. 通过 `mvnw.cmd spring-boot:run` 或打包后的 JAR 启动。让 React 与 API 同源，例如在 Vite 设置 `server.proxy['/api'] = { target: 'http://localhost:8080' }`。浏览器使用 `fetch('/api/...', { credentials: 'same-origin' })`。部署 HTTPS 时设置 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`，并使反向代理把真实协议与主机传给服务端。

## 表结构

| 业务 | 主要关系 |
| --- | --- |
| 系统与基础数据 | `fin_role` 1→N `fin_user`、`fin_permission`；`fin_master` 按类型管理部门、客户、供应商、项目、币种、汇率和费用类型；`fin_audit` 保存财务关键操作 |
| 总账 | `fin_period` 1→N `fin_voucher` 1→N `fin_voucher_line`；科目可自关联，凭证分录外键引用科目和辅助核算；附件随凭证或报销单保存 |
| 往来 | `fin_trade` 区分应收和应付；`fin_settlement` 关联往来单及资金单并记录核销与冲销；`fin_note` 记录票据；`fin_bad_debt` 记录坏账计提、核销和收回 |
| 资金 | `fin_bank_account` 1→N `fin_cash_order`、`fin_bank_statement`、`fin_cash_plan`；`fin_reconciliation_lock` 按账户与期间锁定 |
| 费用 | `fin_claim` 1→N `fin_claim_line`、`fin_approval_task`；`fin_workflow_step` 绑定审批流程版本及角色；`fin_loan` 记录借款和冲抵余额 |
| 资产 | `fin_asset` 1→N `fin_depreciation`、`fin_asset_movement`、`fin_asset_disposal`；调整单保留原折旧与凭证关系 |
| 报表 | `fin_report` 1→N `fin_report_line`；按报表行次配置科目、指标和系数，从已记账分录取数 |
| 运维 | `fin_backup_record` 保存运维系统写入的备份记录 |

金额使用 `DECIMAL(19,2)`，汇率使用 `DECIMAL(19,8)`。资金账户以原币计余额，总账以 CNY 计金额。期初科目余额来自期间前已记账凭证，资金账户期初另存为资金余额的起点。核销时锁定资金单和往来单并校验余额，`request_key` 重试返回同一核销记录。核销使用同币种同汇率；汇兑差额需要独立业务规则。

## 通用契约

- JSON 接口统一返回 `{ code, message, data }`，`code` 与 HTTP 状态码一致。请求成功时从 `data` 读取原业务字段，失败时读取 `message`，此时 `data` 为 `null`。历史 `/api/users` 接口的数组、对象和布尔结果也放在 `data` 下。
- `POST /api/auth/login` 接收 `{ "username": "...", "password": "..." }`，在 `data` 中返回用户及角色授权，写入 `JSESSIONID`。`GET /api/auth/session` 恢复会话，`POST /api/auth/logout` 返回 HTTP 200 和 `data: null`。停用和重置密码会使旧会话失效。
- 财务路径统一以 `/api/finance` 开头。列表查询 `GET /{resource}?page=1&size=20` 的 `data` 为 `{ items, total, page, size }`，汇总和报表数据也从 `data` 读取。前端表格按字段名取值，并用记录 `id` 执行行操作。
- `GET /{resource}/{id}` 查询单条记录。`POST /{resource}` 新增记录，返回 HTTP 201，`data` 为 `{ id }`。行操作用 `POST /{resource}/{id}/{action}`，无参数时可传 JSON 空对象 `{}`，`data` 为 `{ success, result_id }`，其中 `result_id` 依操作为新单据或凭证 ID。凭证批量处理请逐条检查 `data.items[].success`。
- 日期格式 `yyyy-MM-dd`，期间格式 `yyyy-MM`。筛选和请求体字段使用 `snake_case`。常规列表日期支持 `_from`、`_to` 闭区间，例如 `due_date_from`、`due_date_to`。文本组合搜索使用 `q`。
- `GET /{resource}/export` 使用相同筛选，默认导出 XLSX，也可指定 `format=csv`。导出和附件下载成功时直接返回文件，失败时使用统一 JSON 错误响应。普通列表单次最多导出 200 条，明细账查询最多 2,000 条。附件上传采用 multipart 字段 `file`，每个凭证或报销单最多 10 份 PDF、PNG、JPEG，每份不超过 10 MB。
- 服务端使用 HTTP 400、401、403、404、409 等状态码。角色授权以 `resource` 为单位，`can_write` 控制写入权限。管理员维护系统配置，制单与审核分离。报销申请及借款查询限制为本人，审批任务限制为受派人，管理员可查看全量。

```json
{
  "code": 200,
  "message": "成功",
  "data": { "items": [], "total": 0, "page": 1, "size": 20 }
}
```

```json
{ "code": 401, "message": "请先登录", "data": null }
```

## 页面与操作路径

下表中行操作使用上面的通用路径，单独给出路径的操作直接拼到对应资源下。

| 页面资源 | 筛选参数 | 写入与专用操作 |
| --- | --- | --- |
| `dashboard` | `period_code, bank_account_id, currency` | `GET /dashboard` 返回期间、账户余额、本月收付、近六个月趋势、待办和近期活动 |
| `gl/accounts` | `q, category, status, parent_id, account_level` | 新增，`edit`、`disable`、`delete`；返回层级、父级与末级标志；`GET/POST /code-rule` 管理分段长度，`POST /import` 导入 CSV |
| `gl/vouchers` | `period_code, q, status, voucher_date` | 新增，`edit`、`submit`、`approve`、`post`、`copy`、`reverse`、`void`；`POST /batch/approve`、`POST /batch/post` 接收 `ids`；`GET /periods/{periodId}/gaps` |
| `gl/closing` | `period_code, status` | `GET /{id}/check`，`transfer-profit`、`close`、`reopen`；`POST /{id}/year-carryover` 年度结转，`GET /year-carryovers/{id}` 查询期初快照 |
| `gl/ledgers` | `start_period, end_period, ledger_type, account_level, account_id, department_id, project_id, party_id` | 科目余额或凭证分录，`voucher_id` 联查凭证 |
| `ar/receivables`、`ap/payables` | `party_id, party, due_date, status, currency` | 新增，`approve`，`POST /sync` 接收来源单据字段并按来源编号去重 |
| `ar/collections`、`ap/payments` | `party_id, party, transaction_date, status, account_name` | 登记预收或预付款，`settle`、`reverse-settlement`，`GET /{id}/suggestions` |
| `ar/ar-aging`、`ap/ap-aging` | `cutoff_date, party_id, account_id, department_id, project_id` | 按截止日还原核销，返回未到期及四档逾期余额，`POST /ar/ar-aging/buckets` 配置共同区间 |
| `ar/bad-debts` | `period_code, party_id, party, status, treatment` | 新增，`treatment` 为计提、核销或收回 |
| `ap/notes` | `party_id, party, note_type, due_date, status` | 新增、`redeem` 关联已确认付款 |
| `funds/bank-accounts` | `name, currency, account_type, status` | 新增、`edit`，查询仅返回脱敏账号 |
| `funds/cash-orders` | `account_name, bank_account_id, transaction_date, direction, status, currency` | 新增、`approve` |
| `funds/reconciliation` | `account_name, bank_account_id, period_code, match_status, transaction_date` | `POST /import` 接收流水数组，`POST /auto-match`，`match`，`GET /adjustment`，`POST /lock` |
| `funds/cash-plans` | `period_code, cycle, account_name, bank_account_id` | 新增，`GET /position?date=...` |
| `expense/claims` | `applicant, applicant_id, department, department_id, expense_type, approval_status, payment_status` | 新增、`submit`；费用类型由基础数据维护 |
| `expense/loans` | `applicant, applicant_id, due_date, status` | 申请、`approve`、`repay` 关联已确认的资金单 |
| `expense/approvals` | `applicant, expense_type, status, claim_id` | `approve`、`reject`、`transfer`、`add-signer` |
| `expense/expense-payments` | `applicant, applicant_id, department, department_id, payment_status` | `pay`，生成凭证、扣减资金与借款余额 |
| `fa/asset-cards` | `q, category, department, department_id, status` | 新增，`GET /summary`，卡片详情含折旧、变动和处置 |
| `fa/depreciation` | `period_code, category, department, department_id, asset_code` | 对资产计提、`adjust` |
| `fa/asset-movements` | `asset_code, movement_type, status` | 新增调拨、变更或单资产盘点记录，`confirm` |
| `fa/asset-disposals` | `asset_code, disposal_type, status` | 申请、`confirm` |
| `reports/balance-sheet`、`reports/income-statement`、`reports/cash-flow` | `period_code, comparison, report_id` | 新建模板，`configure` 更新公式，GET 查询时报表实时取数，`GET /{id}/lines/{lineNo}/ledger?period_code=...` 联查 |
| `reports/custom-reports` | `name, status` | 新建模板、`configure`，`GET /{id}/run?period_code=...` |
| `system/users` | `q, department, department_id, status` | 新增，`reset-password`、`disable`、`enable`、`assign-role` |
| `system/permissions` | `name, status` | 新增或 `edit` 角色、`configure` 授权，`GET /resources` |
| `system/workflow` | `name, department, department_id, status` | 新建版本、`publish` |
| `system/master-data` | `data_type, q, status` | 新增、`edit` |
| `system/periods` | `fiscal_year, period_code, status` | 新增自然月期间 |
| `system/logs` | `operator, operation, resource, operated_at, result` | 列表和详情 |
| `system/backups` | `backup_at, status` | 列表和详情 |

凭证示例

```http
POST /api/finance/gl/vouchers
Content-Type: application/json

{
  "voucher_date": "2026-11-06",
  "summary": "收到客户预付款",
  "lines": [
    { "account_id": 1, "debit": "100.00" },
    { "account_id": 2, "credit": "100.00", "party_id": 3 }
  ]
}
```

核销示例

```http
POST /api/finance/ar/collections/12/settle
Content-Type: application/json

{
  "trade_id": 5,
  "amount": "60.00",
  "settled_on": "2026-11-06",
  "request_key": "allocation-12-5-001"
}
```

报表模板示例

```json
{
  "name": "利润表",
  "lines": [
    { "line_no": 1, "label": "营业收入", "account_id": 10, "metric": "贷方发生", "factor": 1 },
    { "line_no": 1, "label": "营业收入", "account_id": 11, "metric": "借方发生", "factor": -1 }
  ]
}
```

## 常用写入参数

| 资源或操作 | 请求参数 |
| --- | --- |
| 科目新增 | `code, name, category, balance_side`，可选 `parent_id, dimensions`。辅助核算维度填写部门、项目、客户或供应商。 |
| `gl/accounts/code-rule` | `{"segments":[4,2,2,2,2]}`，启用前校验所有现有科目编码。 |
| `gl/accounts/import` | multipart `file`，UTF-8 CSV 标题为 `code,name,category,balance_side,parent_code,dimensions`，后两列可省。先列父级再列子级，首个错误包含行号并回滚整批。 |
| `ar/ar-aging/buckets` | `{"bounds":[30,60,90]}`，按上限由小到大配置。查询返回 `bounds` 和对应的 `bucket_1` 到 `bucket_4`。 |
| 往来单新增或同步 | `party_id, order_date, due_date, amount, trade_account_id, counterpart_account_id`，可选 `tax_amount, tax_account_id, currency, exchange_rate, department_id, project_id`。同步必填 `source_type, source_no`。 |
| 账户新增 | `name, account_type, ledger_account_id`，银行账户还需要 `bank_name, account_number`。可选 `currency, opening_balance`。 |
| 收付款新增 | `bank_account_id, counterpart_account_id, transaction_date, purpose, amount`，可选 `exchange_rate`。普通收付单传 `direction`，应收收款或应付付款传 `party_id` 并把对方科目设为预收或预付款科目。 |
| 反核销 | `settlement_id, reversed_on`，原核销凭证须已记账且原期间尚未结账。 |
| 对账流水导入 | JSON `bank_account_id, lines`，或 multipart `bank_account_id, file`。行字段为 `transaction_date, reference_no, summary, direction, amount`。 |
| 对账匹配、锁定 | 自动匹配传 `bank_account_id, period_id`，人工匹配传 `cash_order_id`，锁定再传 `bank_ending_balance`。 |
| 报销申请 | `department_id, expense_type_id, lines`，可选 `loan_id, loan_offset`。分录字段为 `expense_type_id, account_id, expense_date, amount, purpose`，可选 `project_id`。 |
| 审批流程配置 | `name, version, steps`，可选 `department_id, expense_type_id, min_amount, max_amount`。节点字段为 `role_id, name, mode`。 |
| 审批和支付 | 驳回传 `opinion`，加签、转交传 `assignee_id`，付款传 `transaction_date, bank_account_id`。全额冲借款时无需资金账户。 |
| 资产新增 | `code, name, category, department_id, acquisition_date, original_value, method, life_months, residual_rate, asset_account_id, expense_account_id, depreciation_account_id`。工作量法需 `total_units`。 |
| 折旧计提或调整 | 计提传 `asset_id, period_id`，工作量法需 `units`；调整传有正负方向的 `amount` 和 `reason`。 |
| 角色配置 | `{"permissions":[{"resource":"gl/vouchers","can_write":true}]}`。修改会替换该角色的授权清单。 |

年度结转以已结账的十二月为来源，并要求下一年度一月已启用。期初快照按科目与辅助维度生成，历史分录仍用于账簿汇总。

## 业务边界

- 销售及采购同步以调用方提交的来源数据为输入。银行流水支持 UTF-8 CSV 或解析后的 JSON。银行专用网银模板、XLSX 导入、PDF 报表、运维恢复入口及邮件提醒仍需补齐。
- 当前支持可配置账龄区间、科目编码分段规则、线性流程节点及金额、部门、费用类型路由。凭证模板、完整审批图、可配置数据范围和按钮级授权尚待补充。
- 报表公式需按实际科目配置，业务生成的凭证按总账流程审核及记账后进入账簿和财务报表。React 原型需要把演示数据源换成真实请求。
