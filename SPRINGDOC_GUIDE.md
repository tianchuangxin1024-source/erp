# Springdoc 接口使用文档

项目通过 springdoc 自动扫描控制器，生成 OpenAPI 和 Swagger UI。接口用途、动态业务请求示例、统一返回结构及会话要求由源码注解和 [OpenApiConfig](src/main/java/com/tian/erp/config/OpenApiConfig.java) 维护。

## 文档地址

按 [初始化步骤](FINANCE_API.md#初始化) 配置数据库并启动应用。默认端口的地址如下，端口或 context path 有调整时使用实际地址。

| 地址 | 用途 |
| --- | --- |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | 浏览接口和交互调用 |
| [OpenAPI JSON](http://localhost:8080/v3/api-docs) | 导入 Postman 等工具 |
| [OpenAPI YAML](http://localhost:8080/v3/api-docs.yaml) | YAML 格式接口描述 |

接口分为登录会话、财务通用业务、专项查询、附件导出和历史用户档案五个标签。

## 会话登录

1. 打开「01 登录与会话」，在 `POST /api/auth/login` 点击「Try it out」。
2. 将示例用户名与密码替换为真实账号。初始管理员密码是部署时设置的 `ERP_BOOTSTRAP_PASSWORD`。
3. 执行登录后，浏览器接受服务端的 HttpOnly `JSESSIONID` Cookie。
4. 调用 `GET /api/auth/session` 验证会话，再在同一来源的 Swagger UI 中调用财务接口。
5. 使用 `POST /api/auth/logout` 退出。过期会话需要重新登录。

安全方案 `sessionCookie` 标识需要会话的接口。浏览器通过登录接口建立 Cookie，授权按钮中的手工输入不能代替服务端登录。角色授权不足时返回 403。

## 动态业务路径与示例

通用路由 `/api/finance/{module}/{page}` 使用业务模块和页面组合。path 中的 `page` 是页面名称，query 中的 `page` 是从 1 开始的分页页码。

| module/page | 用途 |
| --- | --- |
| `gl/accounts`、`gl/vouchers`、`gl/closing`、`gl/ledgers` | 科目、凭证、期末处理及账簿 |
| `ar/receivables`、`ar/collections`、`ar/ar-aging`、`ar/bad-debts` | 应收管理 |
| `ap/payables`、`ap/payments`、`ap/ap-aging`、`ap/notes` | 应付管理 |
| `funds/bank-accounts`、`funds/cash-orders`、`funds/reconciliation`、`funds/cash-plans` | 资金管理 |
| `expense/claims`、`expense/loans`、`expense/approvals`、`expense/expense-payments` | 费用管理 |
| `fa/asset-cards`、`fa/depreciation`、`fa/asset-movements`、`fa/asset-disposals` | 固定资产 |
| `reports/balance-sheet`、`reports/income-statement`、`reports/cash-flow`、`reports/custom-reports` | 报表 |
| `system/users`、`system/permissions`、`system/workflow`、`system/master-data`、`system/periods`、`system/logs`、`system/backups` | 系统配置与记录 |

具体读写能力和筛选白名单见 [页面与操作路径](FINANCE_API.md#页面与操作路径)。Swagger UI 的通用查询字段是多个资源支持字段的并集，填写当前页面支持的筛选，其余留空。

新增接口的「Examples」可切换科目、凭证、往来、账户、费用、资产、报表等请求体。行操作也提供核销、审批、付款和配置示例。请将例子的主键、科目、账户和日期替换为当前实际数据，并先启用业务日期所在会计期间。

例如查询科目时填写 module=`gl`、page=`accounts`；核销时填写 module=`ar`、page=`collections`、id=资金单主键、action=`settle`。

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

双方单据须属于同一往来单位、币种和汇率，并满足可核销余额。同一资金单上使用相同请求号和参数重试返回原核销记录。关联凭证继续按总账流程审核、记账。

## 返回与错误处理

```json
{
  "code": 200,
  "message": "成功",
  "data": { "items": [], "total": 0, "page": 1, "size": 20 }
}
```

| HTTP 状态 | 说明 |
| --- | --- |
| 200、201 | 从 data 读取业务结果；创建通常返回 data.id，行操作返回 success 和可空 result_id |
| 400 | 字段、金额日期、引用关系或 JSON 不合法 |
| 401 | 未登录、密码错误或会话失效 |
| 403 | 角色、业务操作人或来源检查未通过 |
| 404 | 资源、接口或业务记录不存在 |
| 409 | 状态、余额、唯一约束或并发冲突 |
| 500 | 查看服务端日志定位失败 |

批量凭证的 HTTP 200 表示批量请求已处理，还需逐项检查 `data.items[].success` 和 `message`。历史用户档案 `/api/users` 查询不存在的主键时返回 HTTP 200 和 `data=null`。

## CSV、附件与导出

CSV 导入使用 multipart 的 `file` 字段，银行流水额外提供 `bank_account_id`。编码为 UTF-8，最多 2 MB 和 1000 行，标题列见对应操作说明。科目父级先于子级，错误时整批回滚。

凭证和报销附件支持 PDF、PNG、JPEG，单份最多 10 MB，单据最多 10 份。导出 `format` 可选 `xlsx`、`csv`，默认 XLSX。普通列表最多导出 200 条，明细账最多 2000 条。下载和导出成功直接返回文件，失败返回统一 JSON。

## 文档维护

控制器上的 `@Tag`、`@Operation`、`@Parameter` 说明用途与参数，`@ExampleObject` 维护请求示例，响应和实体上的 `@Schema` 说明字段。新增业务或动作时同步更新注解和 [FINANCE_API.md](FINANCE_API.md)。

`OpenApiConfig` 管理标题、会话方案、公共错误格式、Map 查询字段和文件媒体类型。整体架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。
