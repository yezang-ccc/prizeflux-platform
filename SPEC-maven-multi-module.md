# Spec: platform-runtime / D1-02 Maven 多模块父工程

状态：待学习者审阅。

## Objective

为 PrizeFlux 建立一个可重复解析的 Maven 多模块工程。它只解决“构建坐标、Java 版本、模块边界和依赖版本由谁统一管理”的问题，不提前实现业务代码。

使用者包括：

- 项目作者：可以从根目录一条命令验证全部后端模块。
- 后续开发模块：继承同一套 Java、Spring Boot 与 Spring Cloud 版本。
- 面试官和审阅者：可以直接看到运行单元和共享契约的边界，而不是在单个巨型工程中猜测职责。

本任务完成后，五个模块都能被 Maven Reactor 解析：

| 模块 | 职责 | 当前是否写业务代码 |
|---|---|---|
| `prizeflux-contract` | 将来存放跨模块 API、事件和错误契约 | 否 |
| `prizeflux-gateway` | 将来承担入口路由、鉴权与限流 | 否 |
| `prizeflux-campaign` | 将来承担活动、抽奖、库存和中奖记录 | 否 |
| `prizeflux-worker` | 将来承担异步发奖、重试和补偿 | 否 |
| `prizeflux-ai` | 将来承担 RAG、只读工具和 SSE | 否 |

## Tech Stack

- JDK：17，通过 `maven.compiler.release` 固定。
- Maven：Wrapper 固定 Maven 3.9.x 分发版本，Windows 使用 `mvnw.cmd`。
- Spring Boot：`3.5.16`。
- Spring Cloud BOM：`2025.0.3`。
- 根坐标：`com.prizeflux:prizeflux-backend:0.1.0-SNAPSHOT`。

Spring Cloud 2025.0 与 Spring Boot 3.5.x 是官方公布的兼容组合。版本在本次任务中固定；后续升级必须单独提交，并重新执行全部验证。

本任务只导入 Spring Boot Parent 与 Spring Cloud BOM，不在任何模块引入 Web、MyBatis、Redis、Kafka 或 Spring AI Starter。这样可以避免“工程骨架”阶段出现尚未使用的依赖和错误的自动配置。

## Commands

在项目根目录执行：

```powershell
cd backend
.\mvnw.cmd -DskipTests validate
```

查看 Maven Reactor 实际识别的模块：

```powershell
cd backend
.\mvnw.cmd -DskipTests validate -X
```

后续提交前的最低验证：

```powershell
cd backend
.\mvnw.cmd -DskipTests validate
```

## Project Structure

```text
backend/
├─ pom.xml                              # 聚合器、统一坐标、版本和插件配置
├─ mvnw
├─ mvnw.cmd
├─ .mvn/
│  └─ wrapper/
│     ├─ maven-wrapper.jar
│     └─ maven-wrapper.properties
├─ prizeflux-contract/
│  └─ pom.xml
├─ prizeflux-gateway/
│  └─ pom.xml
├─ prizeflux-campaign/
│  └─ pom.xml
├─ prizeflux-worker/
│  └─ pom.xml
└─ prizeflux-ai/
   └─ pom.xml
```

所有子模块使用 `jar` 打包。根 POM 使用 `pom` 打包，并按上表顺序聚合模块。

## Code Style

POM 的职责以 XML 注释解释“为什么”，不堆砌重复说明。版本只在根 POM 管理，子模块不得私自覆盖。

```xml
<!--
  Spring Cloud 的版本通过 BOM 统一管理。
  子模块以后声明 Gateway 等依赖时不写 version，防止同一项目出现不兼容的依赖组合。
-->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Testing Strategy

本任务尚无业务代码，因此不虚构单元测试覆盖率。

验证重点是 Maven 模型：

1. Wrapper 能下载并使用指定 Maven 版本。
2. 根 POM 能解析五个模块。
3. JDK 17 与 Spring Boot、Spring Cloud BOM 可以共同解析。
4. `mvnw.cmd -DskipTests validate` 返回 0。
5. 根 POM 和所有子 POM 不含真实密钥、私服地址或个人路径。

下一任务 D1-03 才开始加入 Spring Boot 启动类与 HTTP 测试。

## Boundaries

### Always

- 只使用 Maven Wrapper 验证，避免依赖开发者机器上的 Maven 版本。
- 只在根 POM 固定 Java 与依赖版本。
- 每个新增 POM 都解释其架构角色和调用边界。
- 在提交前运行 `mvnw.cmd -DskipTests validate`。

### Ask First

- 将 Java 17 改为其他版本。
- 升级或降级 Spring Boot、Spring Cloud 版本。
- 新增 Web、MyBatis、Redis、Kafka、Spring AI 等运行时依赖。
- 改变五个模块的职责或合并、拆分模块。

### Never

- 将密码、Token、私服凭据或真实服务器地址写入 POM。
- 复制训练营项目的 POM、源码或构建脚本。
- 为了构建通过而跳过 Maven Wrapper、删除验证或使用未锁定版本。
- 在 D1-02 提前实现业务接口、数据库表或HTTP控制器。

## Success Criteria

- [ ] `backend/pom.xml` 是唯一聚合根，`packaging` 为 `pom`。
- [ ] 五个子模块均有独立 POM，且均被根 POM 聚合。
- [ ] 所有模块继承 `com.prizeflux:prizeflux-backend` 根坐标。
- [ ] Java 版本固定为 17。
- [ ] Spring Boot `3.5.16` 与 Spring Cloud `2025.0.3` 由根 POM 管理。
- [ ] Maven Wrapper 文件完整存在。
- [ ] `backend\mvnw.cmd -DskipTests validate` 成功。
- [ ] 本任务提交不包含业务 Java、数据库脚本、真实环境配置或训练营文件。
- [ ] 学习者能解释聚合与继承、Parent POM 与 BOM 的区别，以及为什么子模块不应自行写依赖版本。

## Open Questions

- 无。发布前的许可证最终选择、GitHub远端地址和腾讯云部署均不属于本任务。
