# Spec：D2-01 统一响应与错误语义

状态：规格、计划和任务均已批准。本轮限定范围实现、回归、用户阅读/运行、基础应用/订正及笔记闭环已完成。独立L4与长期复习另行验证，不表示整个Java基础或全部项目已完成。

能力归属：CAPABILITY-MAP.md 中 campaign-core 的 API 契约前置切片。项目实际文件位于 prizeflux-contract，供后续 Campaign 和其他模块复用。

## 1. 目标与使用者

为后续活动接口建立稳定的成功/失败响应结构、业务错误码和业务异常。调用方包括未来的 Controller/异常处理器、应用服务以及消费这些 HTTP 接口的前端或其他客户端。

本轮定义 Java 契约与单元测试，尚不接入真实 HTTP 接口。HealthResponse 是健康检查专用 DTO，继续保留现有 status/application 契约。

## 2. 已明确的范围假设

1. 使用 Java 17 和现有 Maven Wrapper/父 POM 版本规则。
2. Contract 的生产代码保持纯 Java，不依赖 Spring MVC、数据库、Redis 或 Kafka。
3. 不修改 HealthController、HealthResponse 或学习者当前 HealthControllerTest 修改。
4. HTTP 状态映射、JSON 集成验证、参数校验和全局异常处理在 D2-02 实现。
5. 本轮不推送 GitHub、不部署，不把新增契约类说成已有业务 API。

## 3. 契约设计与输入输出

### ApiResponse<T>

选择泛型 record，固定字段 code、message、data。T 表示本次响应的数据类型。

```java
// 只展示响应形状，具体校验和工厂实现放在实施阶段。
public record ApiResponse<T>(String code, String message, T data) {
}
```

- success(T data)：返回成功错误码及固定成功消息，data 原样承载；允许 null，表示没有业务数据。
- failure(ErrorCode errorCode)：返回 ApiResponse<Void>，使用枚举中的安全固定消息，data 必须为 null。
- 构造校验：code/message 不得为 null 或空白；非成功响应不允许携带非 null 的 data。
- failure 不接受 null 或成功码；错误输入明确抛 IllegalArgumentException。
- record 只保证封套组件不可重新赋值，不保证任意 T 深度不可变；本轮不尝试通用深拷贝，响应数据所有权由具体 DTO/调用场景负责。

示意（尚未用 Jackson/HTTP 进行验证）：

```json
{"code":"PFX-0000","message":"成功","data":{"id":1}}
```

```json
{"code":"PFX-1001","message":"参数无效","data":null}
```

### ErrorCode

枚举固定业务标识与对外安全消息，不依赖 HttpStatus，也不在本轮绑定 HTTP 状态。

| 枚举 | code | message |
|---|---|---|
| SUCCESS | PFX-0000 | 成功 |
| INVALID_ARGUMENT | PFX-1001 | 参数无效 |
| BUSINESS_RULE_VIOLATION | PFX-2001 | 业务规则不满足 |
| INTERNAL_ERROR | PFX-9999 | 服务内部错误 |

这是 PrizeFlux 自定义契约，不是 Spring 标准。未知异常未来由 Web 层转换成 INTERNAL_ERROR，不能将异常堆栈、数据库报错、Token 等作为对外 message。

### BusinessException

继承 RuntimeException，构造输入为非成功 ErrorCode，保留错误码并以其固定消息初始化异常；提供 getErrorCode() 供后续异常处理器读取。null/SUCCESS 不得创建业务失败异常。

调用链（部分为后续任务，当前仅定义契约）：

```text
应用/领域业务失败 → BusinessException（错误码）
  → D2-02 Web 异常处理器 → HTTP 状态 + ApiResponse.failure
  → Spring MVC/Jackson → 客户端
```

本轮测试不启动 Spring，不创建 MockMvc，不监听端口；异常类本身不决定 HTTP 状态或直接发送响应。

## 4. 文件结构和技术栈

预计生产/测试变更限定 5 个文件：

- backend/prizeflux-contract/pom.xml：仅增加 org.junit.jupiter:junit-jupiter，scope=test，版本继承父 POM。
- backend/prizeflux-contract/src/main/java/com/prizeflux/contract/api/ApiResponse.java。
- backend/prizeflux-contract/src/main/java/com/prizeflux/contract/error/ErrorCode.java。
- backend/prizeflux-contract/src/main/java/com/prizeflux/contract/error/BusinessException.java。
- backend/prizeflux-contract/src/test/java/com/prizeflux/contract/api/ApiResponseTest.java：覆盖三个契约类。

不引入 spring-boot-starter-test 或 Spring Web 到 Contract；不修改根 POM 或 Campaign 依赖。

## 5. 命令与验证

PowerShell 先进入正确目录，避免相对路径指向 system32：

```powershell
Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-contract test
.\mvnw.cmd test
.\mvnw.cmd -DskipTests validate
git -C (git rev-parse --show-toplevel) diff --check
```

本轮不需要启动服务器；测试通过表示纯 Java 契约及当前既有后端测试通过，不表示 D2-02 的真实 HTTP 异常处理已实现。

## 6. 测试策略与成功条件

使用 JUnit Jupiter，先红后绿；不设置无依据的行覆盖率目标，验收具体行为：

1. success 对普通数据及 null 数据产生约定 code/message/data；以至少两种 T 验证泛型用途。
2. failure 保留枚举错误码和固定消息，data 为 null。
3. code/message 的空值或空白、失败响应携带数据、failure(null/SUCCESS) 被拒绝。
4. BusinessException 保留错误枚举和固定消息，拒绝 null/SUCCESS。
5. ErrorCode 的业务标识非空且不重复；不绑定 HTTP 状态。
6. Contract 生产代码只引用 Java 标准类型，契约测试无 Spring 日志或真实端口。
7. 当前 Campaign 健康测试保持通过；不覆盖学习者现有修改。
8. 能从调用者、输入输出和成功/失败路径解释三个类；项目实现/测试证据和知识验收分别记录。

## 7. 代码风格与边界

- Always：中文解释职责和边界；泛型不使用 raw type；先写有意义的失败测试再实现；测试结束整理真实证据。
- Ask first：改 Health 接口、引入运行时依赖、扩充数据库/HTTP 全局处理或改变本契约语义。
- Never：提交真实秘密；返回内部异常详情；把测试结果说成已部署或远程 CI 通过；覆盖用户修改。

## 8. 学习顺序与开放问题

按学习者本次指示：先完成本轮项目阅读/实现/验证，再进入绑定基础轨。实现过程中仅解释直接妨碍理解的必要名词；正式基础轨先确认熟悉度，再选择补课或 L3/L4 验收。

绑定基础：泛型、枚举、RuntimeException、record 的浅不可变、HTTP 状态与业务码。数组封装错题继续保留，项目轮后结合 DTO 所有权补齐，不改成已通过。

本轮没有必须由用户另选技术方案的开放问题；上述字段、错误标识和边界作为可审阅设计提交。规格批准后制定实施计划与任务，沿用已约定的逐关流程。
