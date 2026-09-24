# Spec：D1-03 Campaign 最小启动与健康接口

> 状态：草案，等待学习者审批后才编写 Java 代码或修改 Maven 依赖。

## 目标

让 `prizeflux-campaign` 从“只有 POM 的空模块”成为一个可以独立启动、可被 HTTP 探测、可被自动测试的 Spring Boot 服务。

这是后续活动管理、Gateway 路由、Docker 健康检查和部署的最小基础，但**不是**业务功能，也不连接数据库。

成功时，开发者可访问 `GET /api/health`，获得稳定的 JSON：

```json
{
  "status": "UP",
  "application": "prizeflux-campaign"
}
```

响应不得包含操作系统、JVM、磁盘、数据库地址、用户名、密钥或异常堆栈。

## 本任务的技术选择

| 项目 | 决定 | 原因 |
|---|---|---|
| Java | 17 | 已由 PrizeFlux 根 POM 统一规定。 |
| 框架 | Spring Boot 3.5.16 | 已由根 POM 的 Spring Boot Parent 管理。 |
| Web 依赖 | `spring-boot-starter-web` | 提供 Spring MVC、内嵌 Web 服务器与 JSON 序列化能力。 |
| 测试依赖 | `spring-boot-starter-test`（test scope） | 提供 JUnit 与 MockMvc，验证 HTTP 行为。 |
| 启动端口 | `8081` | 为未来 Gateway 预留常见的 `8080` 入口端口；当前服务可独立运行。 |
| 健康实现 | 自定义 `/api/health` Controller | 先学习 Controller 与 JSON 响应；不在本任务引入 Actuator 或暴露运维细节。 |

`spring-boot-starter-web` 与 `spring-boot-starter-test` 在子模块的 `<dependencies>` 中显式声明，但不写版本号：版本由子模块的 `<parent>` 继承 Spring Boot Parent 的版本治理规则。

## 命令

从 `backend` 目录执行：

```powershell
# 执行 Campaign 模块测试
.\mvnw.cmd -pl prizeflux-campaign test

# 启动 Campaign 服务
.\mvnw.cmd -pl prizeflux-campaign spring-boot:run

# 在另一个终端检查接口
Invoke-RestMethod http://localhost:8081/api/health
```

## 项目结构

```text
backend/prizeflux-campaign/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/prizeflux/campaign/
    │   │   ├── PrizeFluxCampaignApplication.java  # 启动类，组件扫描根
    │   │   └── controller/
    │   │       └── HealthController.java          # HTTP 适配层：健康接口
    │   └── resources/
    │       └── application.yml                    # 应用名与本地端口
    └── test/
        └── java/com/prizeflux/campaign/controller/
            └── HealthControllerTest.java          # HTTP 契约测试
```

## 代码风格

- 包名从 `com.prizeflux.campaign` 开始，确保启动类能扫描到其子包中的 Controller。
- 使用构造器注入；但本任务没有业务依赖，因此 Controller 不注入 Service。
- Controller 只处理 HTTP 输入输出；健康状态暂为静态的 `UP`，不伪造数据库可用性。
- 关键类和每个方法都写中文注释：所在架构层、调用者、输入、输出、这样设计的原因。
- 使用不可变 `record` 或等价的只读响应对象，避免用 `Map` 隐藏接口字段。

示例：

```java
/**
 * Web 接入层：接收 HTTP 请求并返回健康状态，不承载活动业务逻辑。
 */
@RestController
@RequestMapping("/api")
class HealthController {

    @GetMapping("/health")
    HealthResponse health() {
        return new HealthResponse("UP", "prizeflux-campaign");
    }
}
```

## 测试策略

- 使用 `@SpringBootTest` 与 `MockMvc` 启动真实 Spring 上下文。
- 断言 `GET /api/health` 返回 HTTP `200 OK`。
- 断言 JSON 中仅有稳定字段 `status=UP`、`application=prizeflux-campaign`。
- Maven 测试通过后，再进行一次人工启动和 HTTP 请求验证。

本任务不要求覆盖数据库、网络依赖、业务异常或压力测试；这些功能尚未引入。

## 边界

- 始终做：使用 Maven Wrapper；添加中文初学者注释；提交前执行模块测试和格式检查；响应不暴露系统信息。
- 需要先问：新增数据库、Redis、Kafka、Actuator、Spring Cloud Gateway、鉴权或任何外部服务依赖；改变端口约定；改动根 POM 版本。
- 绝不做：提交密钥；伪造“数据库健康”；为一个健康接口提前创建 Service、Mapper、Entity 或复杂分层。

## 验收标准

1. `prizeflux-campaign` 可通过 Maven Wrapper 独立测试并启动。
2. `GET http://localhost:8081/api/health` 返回 HTTP 200 和约定 JSON。
3. 响应不泄露系统、配置或异常信息。
4. 自动化测试覆盖状态码和 JSON 字段。
5. Java 代码、POM 和 YAML 都有面向初学者的中文说明。
6. 本次只改动 Campaign 模块及本规格文档，不引入数据库、缓存、消息队列、AI 或 Gateway 运行依赖。

## 开放问题

无。若学习者希望使用不同端口，或此时就引入 Spring Boot Actuator，应先调整本规格。
