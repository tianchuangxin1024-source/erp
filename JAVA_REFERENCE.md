# Java 类及方法参考

以下职责摘自源码 Javadoc。接口使用见 [SPRINGDOC_GUIDE.md](SPRINGDOC_GUIDE.md)，整体架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## ApiExceptionHandler

[查看源码](src/main/java/com/tian/erp/common/exception/ApiExceptionHandler.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class ApiExceptionHandler` | 将 Spring MVC 请求异常和数据库异常转换成统一响应，保留对应的 HTTP 状态。 |
| `ResponseEntity<Object> duplicate` | 将违反唯一约束的请求返回为 HTTP 409。 |
| `ResponseEntity<Object> invalidRecord` | 将字段或引用约束校验失败返回为 HTTP 400。 |
| `ResponseEntity<Object> busy` | 将可重试的数据访问冲突返回为 HTTP 409。 |
| `ResponseEntity<Object> unexpected` | 记录未预期的异常并返回通用的 HTTP 500 消息。 |
| `protected ResponseEntity<Object> handleExceptionInternal` | 使用框架判定的状态和响应头封装 MVC 异常。 |
| `private ResponseEntity<Object> respond` | 生成 JSON 错误体，保留已有响应头并记录服务端异常。 |
| `private String message` | 为已知请求错误选择面向调用方的消息。 |

## BusinessException

[查看源码](src/main/java/com/tian/erp/common/exception/BusinessException.java)

| 类或方法 | 职责 |
| --- | --- |
| `public final class BusinessException` | 携带 HTTP 状态的业务异常，用于拒绝未实现的资源操作。 |
| `private BusinessException` | 创建 HTTP 404 业务异常。 |
| `public static BusinessException unsupported` | 生成未知业务操作的异常。 |

## ApiResponse

[查看源码](src/main/java/com/tian/erp/common/response/ApiResponse.java)

| 类或方法 | 职责 |
| --- | --- |
| `public record ApiResponse` | JSON 接口的统一响应。 |
| `public static <T> ApiResponse<T> ok` | 构造 HTTP 200 对应的成功响应。 |
| `public static <T> ApiResponse<T> created` | 构造 HTTP 201 对应的创建成功响应。 |
| `public static ApiResponse<Void> failure` | 按指定 HTTP 状态和消息构造无业务数据的失败响应。 |

## PageResult

[查看源码](src/main/java/com/tian/erp/common/response/PageResult.java)

| 类或方法 | 职责 |
| --- | --- |
| `public record PageResult` | 常规列表查询的分页结果。 |

## FinanceWebConfig

[查看源码](src/main/java/com/tian/erp/config/FinanceWebConfig.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceWebConfig` | 注册财务会话拦截器和首个管理员的初始化任务。 |
| `public FinanceWebConfig` | 注入财务请求拦截器。 |
| `ApplicationRunner financeBootstrap` | 配置初始管理员账号和密码，在应用启动时执行有条件的初始化。 |
| `public void addInterceptors` | 将会话和来源校验应用到认证及财务 API 路径。 |

## OpenApiConfig

[查看源码](src/main/java/com/tian/erp/config/OpenApiConfig.java)

| 类或方法 | 职责 |
| --- | --- |
| `OperationCustomizer apiDocumentation` | 为 springdoc 配置会话认证、统一错误响应与动态查询参数说明。 |

## AuthController

[查看源码](src/main/java/com/tian/erp/controller/AuthController.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class AuthController` | 提供登录、会话查询和退出接口。 |
| `public AuthController` | 注入负责凭据校验和用户资料查询的服务。 |
| `ApiResponse<Map<String, Object>> session` | 验证用户名和密码，建立新会话并返回用户资料及授权。 |
| `ApiResponse<Void> logout` | 注销现有会话并返回无数据的成功响应。 |

## FinanceController

[查看源码](src/main/java/com/tian/erp/controller/finance/FinanceController.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceController` | 处理财务通用查询、新增、行操作及总账、往来和报表的专用查询。 |
| `FinanceController` | 注入页面查询、分析和各业务写入服务。 |
| `ApiResponse<Map<String, Object>> dashboard` | 按会计期间、资金账户和币种查询工作台汇总。 |
| `ApiResponse<Set<String>> permissionResources` | 列出系统角色可配置的财务资源标识。 |
| `ApiResponse<?> list` | 按资源类型查询分页记录、账簿、账龄或标准报表。 |
| `ApiResponse<Map<String, Object>> detail` | 按资源和主键读取详情以及适用的明细和关联记录。 |
| `ApiResponse<Map<String, Object>> gaps` | 按期间主键检查凭证流水号的缺号。 |
| `ApiResponse<Map<String, Object>> checkClosing` | 返回指定期间能否结账及阻塞结账的业务记录。 |
| `ApiResponse<List<Map<String, Object>>> suggestions` | 为已确认收款或付款单查找同往来单位、同币种同汇率的可核销单据。 |
| `ApiResponse<Map<String, Object>> reportDetails` | 按模板、行次和期间下钻到报表公式及相关已记账分录。 |
| `ApiResponse<Map<String, Object>> customReport` | 使用 URL 中的模板主键查询自定义报表结果。 |

## FinanceFileController

[查看源码](src/main/java/com/tian/erp/controller/finance/FinanceFileController.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceFileController` | 提供凭证和报销附件的上传、下载及财务查询结果导出。 |
| `FinanceFileController` | 注入附件服务和导出所需的查询服务。 |
| `ApiResponse<Map<String, Object>> upload` | 接收 multipart 文件并保存为凭证或报销单附件。 |
| `ResponseEntity<byte[]> download` | 校验附件归属及记录可见性，返回原始文件字节。 |
| `ResponseEntity<byte[]> approvalAttachment` | 按可访问的审批任务下载其对应报销单的附件。 |
| `ResponseEntity<byte[]> export` | 沿用列表或报表筛选生成 XLSX 或 CSV，普通列表超过 200 条时拒绝导出。 |
| `private List<Map<String, Object>> reportRows` | 提取报表或汇总结果中的明细记录供文件编码使用。 |
| `private ResponseEntity<byte[]> file` | 设置下载文件名、媒体类型及长度，并写入原始字节。 |

## FinanceWorkflowController

[查看源码](src/main/java/com/tian/erp/controller/finance/FinanceWorkflowController.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceWorkflowController` | 处理年度结转、规则配置、CSV 导入、银行对账和专项汇总接口。 |
| `FinanceWorkflowController` | 注入年度结转、对账、财务规则和专项查询依赖。 |
| `ApiResponse<Map<String, Object>> yearCarryover` | 为指定的已结账十二月期间建立下一年期初快照。 |
| `ApiResponse<Map<String, Object>> yearSnapshot` | 按结转批次查询期初快照及分页余额明细。 |
| `ApiResponse<Map<String, Object>> accountRule` | 读取当前科目编码的分段长度规则。 |
| `ApiResponse<Map<String, Object>> importAccounts` | 校验现有科目编码后保存新的分段长度规则。 |
| `ApiResponse<Map<String, Object>> importStatementsCsv` | 解析银行流水 CSV 并按指定资金账户导入。 |
| `ApiResponse<Map<String, Object>> adjustment` | 接收 JSON 格式的银行流水并执行批量导入。 |
| `ApiResponse<List<Map<String, Object>>> position` | 确认调节平衡后锁定指定账户和会计期间。 |
| `ApiResponse<List<Map<String, Object>>> assetSummary` | 按部门和类别筛选并汇总在用资产。 |

## UserController

[查看源码](src/main/java/com/tian/erp/controller/UserController.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class UserController` | 提供历史用户档案接口，持久化到 user_tab。 |
| `public UserController` | 注入历史用户档案服务。 |
| `public ApiResponse<List<User>> listUsers` | 查询所有用户 |
| `public ApiResponse<User> getUserById` | 根据ID查询用户 |
| `public ApiResponse<Boolean> addUser` | 新增用户 |
| `public ApiResponse<Boolean> updateUser` | 更新用户 |
| `public ApiResponse<Boolean> deleteUser` | 删除用户 |
| `public ApiResponse<List<User>> searchUsers` | 条件查询示例：根据姓名模糊查询 |

## User

[查看源码](src/main/java/com/tian/erp/entity/User.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class User` | 历史用户档案实体，对应 user_tab 的个人资料字段。 |

## ErpApplication

[查看源码](src/main/java/com/tian/erp/ErpApplication.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class ErpApplication` | ERP 服务启动入口，扫描应用组件及 MyBatis-Plus Mapper。 |
| `public static void main` | 启动 Spring Boot 并传递命令行参数。 |

## UserMapper

[查看源码](src/main/java/com/tian/erp/mapper/UserMapper.java)

| 类或方法 | 职责 |
| --- | --- |
| `public interface UserMapper` | 继承 MyBatis-Plus 的历史用户档案数据访问接口。 |

## FinanceRepository

[查看源码](src/main/java/com/tian/erp/repository/finance/FinanceRepository.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceRepository` | 封装带命名参数的财务 JDBC 操作，由调用方指定受信任的 SQL、表名和字段名。 |
| `FinanceRepository` | 注入支持命名参数的 JDBC 模板。 |
| `public List<Map<String, Object>> rows` | 执行查询并按列名返回结果行。 |
| `public Map<String, Object> one` | 读取查询的首行，无结果时抛出 HTTP 404。 |
| `public Map<String, Object> row` | 按主键读取指定表的一行，可在当前事务中加行锁。 |
| `public int update` | 执行参数化 DML 并返回受影响的行数。 |
| `public long insert` | 按字段映射插入记录并返回数据库生成的主键。 |
| `public void patch` | 按主键更新给出的字段，空字段映射直接跳过。 |
| `public void audit` | 记录操作人、资源、动作和业务对象的财务审计事件。 |

## FinanceAuthInterceptor

[查看源码](src/main/java/com/tian/erp/security/FinanceAuthInterceptor.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceAuthInterceptor` | 对财务及认证请求执行来源、会话有效性和资源权限检查。 |
| `public FinanceAuthInterceptor` | 注入会话用户查询及资源权限服务。 |
| `public boolean preHandle` | 在控制器运行前加载会话用户并校验该方法所需的资源权限。 |
| `private void checkOrigin` | 在写请求带有 Origin 时校验它与服务端识别的来源一致。 |

## FinanceSession

[查看源码](src/main/java/com/tian/erp/security/FinanceSession.java)

| 类或方法 | 职责 |
| --- | --- |
| `public final class FinanceSession` | 管理财务登录会话及请求级用户资料。 |
| `private FinanceSession` | 工具类构造器，禁止实例化。 |
| `public static void start` | 使旧会话失效并在新会话中记录用户主键和会话版本。 |
| `public static void end` | 注销存在的 HTTP 会话。 |
| `public static Map<String, Object> actor` | 读取鉴权拦截器写入当前请求的用户资料。 |
| `public static long actorId` | 返回当前请求中已认证用户的主键。 |

## AssetService

[查看源码](src/main/java/com/tian/erp/service/finance/AssetService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class AssetService` | 在事务中管理资产卡片、变动、处置及月度折旧。 |
| `AssetService` | 注入资产持久化、基础数据校验和总账服务。 |
| `long create` | 将资产资源的创建请求分派给卡片、变动或处置登记逻辑。 |
| `private long asset` | 校验资产部门和折旧参数后保存新资产卡片。 |
| `private long movement` | 保存资产原使用信息及目标信息，登记待处理变动。 |
| `void confirmMovement` | 核对资产当前使用信息，确认调拨、使用人变更或盘点结果。 |
| `private long disposal` | 保存待处理的报废或出售申请，并记录申请时的账面净值。 |
| `long confirmDisposal` | 按最新账面金额生成处置凭证，确认处置单并将资产标记为已处置。 |
| `long depreciate` | 按资产折旧方法计算单资产单期间折旧，保存明细及适用的凭证。 |
| `Long adjust` | 在未结账且无后续计提的期间调整折旧，另存调整记录和凭证。 |
| `private long depreciationVoucher` | 根据折旧金额方向生成费用与累计折旧分录。 |
| `private void active` | 要求资产处于使用中状态，否则拒绝本次操作。 |
| `private BigDecimal net` | 计算资产原值减去累计折旧后的账面净值。 |
| `public List<Map<String, Object>> summary` | 按部门和类别汇总在用资产的原值、累计折旧及净值。 |

## AttachmentService

[查看源码](src/main/java/com/tian/erp/service/finance/AttachmentService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class AttachmentService` | 校验凭证或报销单的附件归属、上传权限及文件限制，并存取数据库文件内容。 |
| `AttachmentService` | 注入附件数据访问、记录可见性查询和会计期间校验服务。 |
| `public long upload` | 仅为操作者自己的草稿保存 PDF、PNG 或 JPEG，每份不超过 10 MB 且单据最多 10 份。 |
| `public Map<String, Object> download` | 确认记录及附件可见性后返回文件名与字节内容。 |
| `private String column` | 将支持的附件资源映射到数据库归属字段。 |
| `private String fileType` | 按文件头识别允许上传的媒体类型。 |
| `private boolean prefix` | 判断文件字节是否以指定签名开头。 |

## AuthService

[查看源码](src/main/java/com/tian/erp/service/finance/AuthService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class AuthService` | 校验登录凭据、读取会话授权并初始化首个管理员。 |
| `AuthService` | 注入财务用户及角色的数据访问组件。 |
| `public Map<String, Object> authenticate` | 按启用状态检索用户并验证密码，返回供会话建立使用的内部用户记录。 |
| `public Map<String, Object> sessionUser` | 按用户主键与会话版本读取仍有效的用户和角色，失效时返回空映射。 |
| `public boolean canAccess` | 检查角色是否拥有指定资源的读取或写入权限。 |
| `public void bootstrap` | 在尚无管理员且提供初始凭据时创建系统管理员角色及账号。 |
| `static String password` | 校验密码参数类型及长度，并保留密码中的原始空白字符。 |
| `static String hash` | 使用随机盐和 PBKDF2WithHmacSHA256 生成密码存储值。 |
| `private static boolean verify` | 对输入密码进行派生，并以恒定时间比较摘要。 |
| `private static byte[] derive` | 使用配置的迭代次数和盐派生 256 位密码摘要。 |
| `public static boolean admin` | 兼容数据库布尔及数值表示，判断是否为初始管理员。 |
| `public Map<String, Object> profile` | 生成对外用户资料，包含角色、部门和资源授权清单。 |

## CashService

[查看源码](src/main/java/com/tian/erp/service/finance/CashService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class CashService` | 在事务中处理资金账户、收付、往来单审核及核销和反核销。 |
| `CashService` | 注入资金数据访问和凭证生成服务。 |
| `public long addBankAccount` | 创建银行或现金账户，设置币种、账务科目及授权范围内的初始余额。 |
| `public void editBankAccount` | 更新账户可维护信息，校验账户类型和启停状态。 |
| `public long addCashOrder` | 登记资金单，往来收付款按预收或预付类型直接确认。 |
| `public long confirmCashOrder` | 要求由其他操作者审核普通收付单，并生成凭证及更新账户余额。 |
| `private long confirm` | 校验单据与账户期间状态，在同一事务中更新资金余额、凭证关联和确认状态。 |
| `void unlocked` | 校验账户已启用且账户所属期间尚未锁定对账。 |
| `public long addTrade` | 录入应收或应付单，校验金额、日期及往来单位，按需记录来源标识。 |
| `public long approveTrade` | 要求非制单人审核待审核往来单，生成含税额分录的待审核凭证。 |
| `public List<Map<String, Object>> suggestions` | 按往来单位、币种、汇率及可用余额返回候选核销单据。 |
| `public long settle` | 锁定资金单与往来单，以 request_key 校验重试，保存核销并更新往来余额。 |
| `public long reverseSettlement` | 恢复原核销金额并关联红字冲销凭证，要求原核销凭证已记账。 |
| `private Map<String, Object> settlementCash` | 读取核销使用的资金单，校验业务方向及已确认状态，可加行锁。 |
| `private BigDecimal usedCash` | 汇总资金单当前未反核销的累计分配金额。 |
| `void updateTradeBalance` | 校验已核销总额并同步待核销、部分核销或已核销状态。 |
| `private void party` | 校验往来单位与 AR 或 AP 业务的类型及启用状态一致。 |
| `static Map<String, Object> line` | 按科目、金额和借贷方向建立分录并复制适用的辅助维度。 |
| `static BigDecimal positive` | 读取正数金额并校验两位小数精度。 |
| `static BigDecimal base` | 将原币金额按汇率换算为两位小数的本位币金额。 |
| `private BigDecimal rate` | 校验汇率为正数且最多八位小数，CNY 汇率固定为 1。 |
| `private String currency` | 读取币种，缺省使用 CNY，并校验三位大写代码。 |
| `static String orderNo` | 使用调用方提供的非空业务号，缺省时用前缀和 UUID 生成业务号。 |
| `static String tradeResource` | 将往来方向映射到应收单或应付单资源标识。 |
| `static String cashResource` | 将往来方向映射到收款核销或付款核销资源标识。 |

## ExpenseService

[查看源码](src/main/java/com/tian/erp/service/finance/ExpenseService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class ExpenseService` | 管理报销明细、版本化审批任务、借款关联及报销支付。 |
| `ExpenseService` | 注入报销数据访问、基础数据、总账和资金服务。 |
| `long claim` | 校验申请部门、费用类型限额及借款冲抵，保存草稿和费用明细。 |
| `void submit` | 为申请人的草稿匹配已发布流程，建立首节点任务并转为待审批。 |
| `private int specificity` | 计算流程的部门及费用类型限定数，用于匹配优先级比较。 |
| `private boolean enqueue` | 查找下一节点并按串行、或签或会签规则建立有效审批人的任务。 |
| `void handle` | 由受派人同意、驳回、转交或加签当前任务，记录历史并按节点条件推进流程。 |
| `private void finish` | 写入任务的办理状态、意见与处理时间。 |
| `private void cancel` | 将指定报销单和节点下尚未办理的任务标记为已取消。 |
| `long loan` | 以申请人身份登记借款申请、预计还款日和借款核算科目。 |
| `void loanAction` | 由其他人员将借款审核或还款登记关联到已确认的资金单。 |
| `long pay` | 锁定已通过的未支付报销单，生成凭证并更新资金、借款冲抵和支付状态。 |
| `private void validateLoan` | 验证借款申请人、审批状态和可用借款余额。 |
| `private void reduceLoan` | 扣减借款余额，并在余额为零时将借款标记为已结清。 |

## FinanceAnalyticsService

[查看源码](src/main/java/com/tian/erp/service/finance/FinanceAnalyticsService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceAnalyticsService` | 提供账簿、账龄、工作台、报表取数及期末检查与结账操作。 |
| `FinanceAnalyticsService` | 注入分析查询、凭证服务和财务规则读取组件。 |
| `public Map<String, Object> query` | 将受支持的分析资源分派到对应的账簿、账龄或报表查询。 |
| `private Map<String, Object> ledgers` | 汇总已记账分录或返回明细账，校验期间顺序及明细数量限制。 |
| `private Map<String, Object> aging` | 按截止日恢复有效核销与坏账核销金额，按客户或供应商及币种汇总账龄。 |
| `public Map<String, Object> dashboard` | 读取期间信息、当前资金余额、收付趋势及调用者可见的待办和近期活动。 |
| `public Map<String, Object> report` | 按模板配置的科目和指标汇总已记账分录，返回本期、累计及对比金额。 |
| `public Map<String, Object> reportDetails` | 读取指定报表行的取数项和凭证分录贡献值。 |
| `public long configureReport` | 新建报表模板或在事务中整体替换现有模板的取数行。 |
| `public Map<String, Object> closingCheck` | 返回未记账凭证、借贷不平、未提折旧、待审核往来及未结转损益等阻塞项。 |
| `public Long close` | 根据动作执行损益结转、月末结账或反结账，并校验后续期间及年度快照限制。 |
| `static YearMonth month` | 将 yyyy-MM 格式的参数解析为会计月份。 |

## FinanceOperationService

[查看源码](src/main/java/com/tian/erp/service/finance/FinanceOperationService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceOperationService` | 分派专项写操作，并处理票据、资金计划和坏账单据。 |
| `FinanceOperationService` | 注入专项操作的持久化和各财务业务服务。 |
| `public long create` | 将专项资源创建请求分派到票据、计划、费用、资产或坏账逻辑。 |
| `public Long action` | 按资源分派专项行操作，并执行借款操作所需的管理员校验。 |
| `private long note` | 校验供应商、票面金额及日期后登记应付票据。 |
| `private void redeem` | 将票据关联到金额和往来单位匹配的已确认普通付款单并标记兑付。 |
| `private long plan` | 校验资金账户、日期和预计收付额后保存周度或月度资金计划。 |
| `public List<Map<String, Object>> position` | 按指定日期读取期初加历史收付净额及当日计划，计算预计余额。 |
| `private long badDebt` | 按计提、核销或收回方式生成凭证并保存坏账记录，校验相关余额上限。 |

## FinanceQueryService

[查看源码](src/main/java/com/tian/erp/service/finance/FinanceQueryService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceQueryService` | 用预定义 SQL 和筛选白名单读取财务页面记录，并应用支持的个人数据范围。 |
| `static boolean known` | 判断资源是否为常规页面、分析页面或工作台。 |
| `public static Set<String> resources` | 返回排序后的完整财务资源标识集合。 |
| `FinanceQueryService` | 注入财务查询的数据访问组件。 |
| `private record Page` | 保存常规查询 SQL 和允许外部使用的筛选字段集合。 |
| `private static Page page` | 用查询 SQL 和允许的筛选字段构造页面定义。 |
| `private Page definition` | 读取常规页面的 SQL 定义，未知资源返回 HTTP 404。 |
| `public PageResult<Map<String, Object>> list` | 校验筛选和分页参数，应用数据范围后读取总数与当前页。 |
| `private void scope` | 为普通用户的报销、借款或审批查询加入本人或受派人条件。 |
| `public Map<String, Object> detail` | 读取可见的单条记录，并按资源附加分录、附件、审批历史等关联数据。 |

## FinanceRuleService

[查看源码](src/main/java/com/tian/erp/service/finance/FinanceRuleService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceRuleService` | 管理科目编码分段及应收应付共用的账龄区间规则。 |
| `FinanceRuleService` | 注入规则存储与审计所需的数据访问组件。 |
| `public List<Integer> accountSegments` | 读取科目编码分段列表，更新或建科目前可选择锁定规则行。 |
| `List<Integer> agingBounds` | 读取共用的三个账龄上限。 |
| `public List<Integer> saveAccountSegments` | 校验五至十二级分段长度及现有科目的兼容性后保存规则。 |
| `public List<Integer> saveAgingBounds` | 校验三个严格递增的正整数账龄上限并保存规则。 |
| `void validateAccountCode` | 按科目层级累加分段长度，验证编码长度与规则一致。 |
| `private List<Integer> read` | 读取配置字符串并解析整数列表，可在当前事务中锁定配置行。 |
| `private void save` | 将整数列表以逗号分隔形式写回指定配置。 |
| `private List<Integer> integers` | 校验指定数组的数量与元素上限，并转换为正整数列表。 |

## FinanceSystemService

[查看源码](src/main/java/com/tian/erp/service/finance/FinanceSystemService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceSystemService` | 管理财务用户、角色授权、基础数据与审批流程版本。 |
| `FinanceSystemService` | 注入系统数据访问与审计组件。 |
| `public long saveMaster` | 新增或维护指定类型的基础数据，校验汇率、费用限额和启停状态。 |
| `public long createUser` | 校验角色与部门后创建财务账号，并保存派生后的密码。 |
| `public void userAction` | 执行密码重置、停启用或角色分配，并增加会话版本使旧会话失效。 |
| `public long saveRole` | 新增或维护角色，保护初始管理员角色。 |
| `public void permissions` | 在事务中整体替换角色的资源及写入授权清单。 |
| `public long createWorkflow` | 创建草稿流程版本和按顺序排列的审批节点。 |
| `public void publishWorkflow` | 校验节点角色拥有有效用户后启用草稿版本。 |
| `Map<String, Object> master` | 读取并校验基础数据的类型和启用状态。 |
| `private long save` | 根据是否给出主键执行新增或字段更新。 |
| `private void validateStatus` | 校验给出的启停状态属于允许值。 |

## LedgerService

[查看源码](src/main/java/com/tian/erp/service/finance/LedgerService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class LedgerService` | 管理会计科目、期间、凭证分录及凭证状态流转。 |
| `LedgerService` | 注入总账数据访问及编码规则服务。 |
| `public long addAccount` | 锁定编码规则，验证父子科目和层级后新增科目。 |
| `public List<Long> importAccounts` | 按 CSV 行顺序解析上级编码并批量新增科目，首个业务错误带原始行号。 |
| `public void accountAction` | 编辑名称及辅助维度、停用或删除符合条件的科目。 |
| `private boolean used` | 检查科目是否已有历史凭证分录。 |
| `public long openPeriod` | 校验完整自然月并串行校验年度时间范围后启用期间。 |
| `Map<String, Object> period` | 按业务日期读取并锁定开放期间，校验该日期位于期间范围。 |
| `public long addVoucher` | 按日期和分录新增草稿凭证。 |
| `long create` | 在已锁定期间内分配连续流水号，保存凭证及分录，并按调用参数选择草稿或待审核状态。 |
| `private record Lines` | 封装已校验的分录行、借方合计和贷方合计。 |
| `private Lines normalize` | 校验分录科目、金额及必填辅助维度，返回规范化分录和借贷合计。 |
| `private void balanced` | 要求至少两条分录、借方非零且借贷合计相等。 |
| `private void writeLines` | 按行序保存凭证分录。 |
| `private List<Map<String, Object>> voucherLines` | 按凭证主键和行序读取原始分录。 |
| `public Long voucherAction` | 执行编辑、提交、审核、记账、复制、红冲或作废，并校验操作者、期间及来源业务限制。 |
| `long reverse` | 在目标开放期间生成关联原凭证的红字待审核凭证。 |
| `public Map<String, Object> gaps` | 按期间的下一个流水号和现有凭证列出缺号。 |

## ReconciliationService

[查看源码](src/main/java/com/tian/erp/service/finance/ReconciliationService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class ReconciliationService` | 处理银行流水导入、自动或人工匹配、余额调节及对账锁定。 |
| `ReconciliationService` | 注入银行流水存储、会计期间和资金状态校验服务。 |
| `public Map<String, Object> loadStatements` | 按账户、日期和流水号去重导入，重复号内容不一致时回滚整批。 |
| `void match` | 将单条未匹配银行流水与同账户、方向、金额及期间的已确认资金单勾对。 |
| `public Map<String, Object> automatic` | 用日期、金额、方向及摘要匹配流水，仅保存一对一候选并返回冲突标识。 |
| `public Map<String, Object> adjustment` | 根据银行期末余额、历史账面收付及本期间未达项计算调节后余额。 |
| `public void lock` | 要求余额调节差额为零后保存账户与期间的对账锁定记录。 |
| `private Map<String, Object> lockPeriod` | 读取并锁定指定的开放会计期间。 |
| `private BigDecimal net` | 按收款为正、付款为负计算流水净额。 |

## YearEndService

[查看源码](src/main/java/com/tian/erp/service/finance/YearEndService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class YearEndService` | 为已完成年末结账的账务建立下一年度期初余额快照。 |
| `YearEndService` | 注入年度结转的数据访问组件。 |
| `public long carryForward` | 锁定期间时间线和来源期间，校验次年一月启用后保存按科目及辅助维度汇总的余额快照。 |
| `public Map<String, Object> snapshot` | 按年度结转主键查询批次信息和分页期初余额。 |

## UserServiceImpl

[查看源码](src/main/java/com/tian/erp/service/impl/UserServiceImpl.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class UserServiceImpl` | 将历史用户档案服务绑定到 UserMapper 和 User 实体。 |

## UserService

[查看源码](src/main/java/com/tian/erp/service/UserService.java)

| 类或方法 | 职责 |
| --- | --- |
| `public interface UserService` | 定义历史用户档案服务并复用 MyBatis-Plus 的通用 CRUD。 |

## FinanceCsv

[查看源码](src/main/java/com/tian/erp/support/finance/FinanceCsv.java)

| 类或方法 | 职责 |
| --- | --- |
| `public class FinanceCsv` | 解析带引号的 UTF-8 CSV 输入，并编码财务导出记录。 |
| `public List<Map<String, Object>> read` | 校验文件大小、编码、必需列与可选列后返回最多 1000 条记录。 |
| `private List<List<String>> parse` | 逐字符解析 CSV 分隔符、转义引号和引号内换行。 |
| `public static byte[] encode` | 将记录列名和数据编码为带 UTF-8 BOM 的 CSV 字节。 |
| `private static String cell` | 转义 CSV 单元格，并对可能触发公式的字符串加文本前缀。 |

## FinanceFields

[查看源码](src/main/java/com/tian/erp/support/finance/FinanceFields.java)

| 类或方法 | 职责 |
| --- | --- |
| `public final class FinanceFields` | 提供财务 Map 参数的字段提取、金额日期解析和业务校验工具。 |
| `private FinanceFields` | 工具类构造器，禁止实例化。 |
| `public static Map<String, Object> values` | 按交替给出的键和值构建保持插入顺序的参数映射。 |
| `public static Map<String, Object> fields` | 仅复制允许名单中的简单类型字段，保留显式 null。 |
| `public static String requiredText` | 要求参数为非空白字符串并去除首尾空白。 |
| `public static long id` | 将主键参数解析为正整数 long，拒绝小数和越界值。 |
| `public static BigDecimal decimal` | 将指定字段解析为 BigDecimal，缺失或不可解析时拒绝请求。 |
| `public static BigDecimal money` | 校验金额最多两位小数且整数部分不超过十七位。 |
| `public static LocalDate date` | 按 yyyy-MM-dd 解析业务日期。 |
| `public static void require` | 条件不满足时抛出带业务消息的 HTTP 400 异常。 |
| `public static void state` | 状态条件不满足时抛出带业务消息的 HTTP 409 异常。 |
| `public static List<Map<String, Object>> items` | 校验非空且未超过上限的对象数组，并复制明细映射。 |

## FinanceWorkbook

[查看源码](src/main/java/com/tian/erp/support/finance/FinanceWorkbook.java)

| 类或方法 | 职责 |
| --- | --- |
| `private FinanceWorkbook` | 工具类构造器，禁止实例化。 |
| `public static byte[] encode` | 写入工作簿及工作表 XML，返回压缩后的 XLSX 字节。 |
| `private static void row` | 写入单行单元格，超过 Excel 数值有效精度的值按文本保存。 |
| `private static String columnName` | 将从 1 开始的列序号转换为 Excel 列字母。 |
| `private static String escape` | 移除 XML 不允许的控制字符并转义文本中的保留字符。 |
| `private static void entry` | 向工作簿 ZIP 包中写入指定路径的 UTF-8 XML 文件。 |
