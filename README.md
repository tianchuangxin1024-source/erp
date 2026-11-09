# ERP 财务服务端

基于 Java 17、Spring Boot、MariaDB 和 springdoc 的财务管理服务端，与 React 财务页面对接。

支持总账、应收应付、资金、报销审批、固定资产、报表和系统管理。JSON 接口统一返回 `code`、`message`、`data`，业务请求使用会话 Cookie 鉴权。

## 本地运行

准备 Java 17、MariaDB，创建字符集为 `utf8mb4` 的数据库。用环境变量设置数据库连接及初始管理员密码。

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:mariadb://localhost:3306/tian"
$env:SPRING_DATASOURCE_USERNAME = "数据库用户"
$env:SPRING_DATASOURCE_PASSWORD = "数据库密码"
$env:ERP_SCHEMA_INIT = "always"
$env:ERP_BOOTSTRAP_PASSWORD = "请设置至少12字符的初始管理员密码"
./mvnw.cmd spring-boot:run
```

首次初始化也可手动执行 [建表脚本](src/main/resources/db/finance-schema.sql)。初始化账号默认为 `admin`，密码取初始化时配置的值。

本机配置可写入 `src/main/resources/application-local.properties`，该文件已加入忽略规则。敏感凭据通过本机配置或环境变量设置。

启动后打开 [Swagger UI](http://localhost:8080/swagger-ui.html)，先调用登录接口，再用同一浏览器会话调试财务接口。

## 文档

- [Springdoc 使用与调试指南](SPRINGDOC_GUIDE.md)
- [接口、数据库和初始化说明](FINANCE_API.md)
- [整体架构](ARCHITECTURE.md)
- [类及方法参考](JAVA_REFERENCE.md)

接口支持范围及尚待补齐的业务能力见 [业务边界](FINANCE_API.md#业务边界)。
