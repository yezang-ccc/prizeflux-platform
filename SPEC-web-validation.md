# Spec：D2-02 参数校验与全局异常处理

状态：学习者已批准规格、计划及任务；两项依赖和4文件范围实现/回归通过，用户20测试成功，阅读与绑定基础首轮限定闭环完成。独立L3题8/7/7及订正分别记录，不等于独立L4、延迟掌握或生产验收。任务见tasks/D2-02-web-validation-tasks.md，复盘见docs/learning/d2-02-module-review.md。

能力归属：CAPABILITY-MAP.md 的 campaign-core，承接已完成的 D2-01 Java 契约，建立 Web 输入/失败输出边界。不新增能力模块。

## 1. 目标与使用者

让未来调用活动接口的客户端获得稳定的参数错误/业务失败/内部错误响应，让后端开发者能定位失败阶段。用真实 Spring MVC 消息转换和 MockMvc 验证 D2-01 契约如何成为 JSON 响应，而不是只直接调用 Java 工厂。

## 2. 范围假设与取舍

1. 继续使用项目 Java17、Spring Boot3.5.16 和现有父POM版本规则，不升级框架。
2. 本轮新增 Campaign 对 Contract 的模块依赖（version=${project.version}）和 spring-boot-starter-validation（版本由父POM管理），必须随规格获得批准；Contract仍保持纯Java。
3. 请求DTO只定义前置形状 name：非null、非空白，最多100字符，消息使用固定中文；不是完整活动领域模型，不校验时间、概率或库存。
4. 暂不提供生产活动创建接口。MockMvc使用仅test作用域的测试Controller，入口路径与行为明确标为测试夹具，不进入生产组件扫描或部署制品。有效请求返回200成功只是验证链路，不代表创建业务完成。
5. 沿用D2-01失败data=null，不增加错误明细数据或自定义对外异常消息。因此历史todo中的ValidationError和messages_zh_CN.properties不在本轮新增：字段级错误输出与多语言资源留待需求明确后另行规格，不为凑文件数量而实现。
6. 健康接口仍返回原HealthResponse，不改现有HealthControllerTest用户修改。无数据库、Redis、Kafka、安全认证、真实HTTP端口验收、远程CI、提交/推送/部署。

## 3. 输入输出与HTTP状态规则

| 场景 | HTTP状态 | 响应code/message | data |
| --- | --- | --- | --- |
| 测试夹具收到有效name | 200 | PFX-0000 / 成功 | 测试夹具回显的请求对象 |
| @Valid请求字段校验失败 | 400 | PFX-1001 / 参数无效 | null |
| JSON语法/形状错误或请求体缺失，导致无法读取DTO | 400 | PFX-1001 / 参数无效 | null |
| BusinessException(INVALID_ARGUMENT) | 400 | PFX-1001 / 参数无效 | null |
| BusinessException(BUSINESS_RULE_VIOLATION) | 422 | PFX-2001 / 业务规则不满足 | null |
| BusinessException(INTERNAL_ERROR) | 500 | PFX-9999 / 服务内部错误 | null |
| 未预期Exception，包括错误调用failure(SUCCESS)产生的IllegalArgumentException | 500 | PFX-9999 / 服务内部错误 | null |

400/422/500是本项目拟定的映射，不是ErrorCode或BusinessException自带规则。不把所有IllegalArgumentException自动映射400，避免把程序错误伪装成客户端错误。

Handler处理范围限Campaign的MVC请求链，不捕获JVM Error，不承诺处理Filter、后台线程/任务或所有进程异常。其他已知MVC异常（如405/415）沿用框架原HTTP状态及必要响应头，本轮不强行统一其JSON结构；不得被兜底Exception处理器错误改成500。

JSON示例：

```json
{"code":"PFX-1001","message":"参数无效","data":null}
```

## 4. 项目结构与代码风格

本轮拟改生产/测试文件4份：

- backend/prizeflux-campaign/pom.xml：新增上述两项依赖。
- backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/controller/dto/CreateCampaignRequest.java：请求record，不含业务保存逻辑。
- backend/prizeflux-campaign/src/main/java/com/prizeflux/campaign/controller/advice/GlobalExceptionHandler.java：使用@RestControllerAdvice与ResponseEntity明确HTTP状态；基于ResponseEntityExceptionHandler维护已有MVC异常状态，处理请求校验/解析、业务异常和未预期Exception。
- backend/prizeflux-campaign/src/test/java/com/prizeflux/testsupport/web/GlobalExceptionHandlerTest.java：配置明确的Spring MVC测试上下文，包含测试夹具Controller；package在应用扫描根之外，显式加入本测试上下文，避免影响健康测试。

规划、证据、导读、知识与错题文件另行记录。中文注释说明调用方、输入输出、失败边界；未知错误的对外消息只使用INTERNAL_ERROR枚举。

示例风格（尚未实现）：

```java
public record CreateCampaignRequest(
        @NotBlank(message = "活动名称不能为空")
        @Size(max = 100, message = "活动名称不能超过100字符")
        String name
) {}
```

## 5. 日志与安全边界

- 对外不得返回异常类名、内部message、堆栈、SQL、凭证或rejectedValue。
- 校验/JSON读取失败日志仅记录安全事件类型和预定义字段，不直接打印原请求、BindingResult或完整解析异常消息。
- 业务失败日志记录固定业务码，不记录任意请求体。
- 未预期异常记录服务端诊断堆栈。堆栈/异常消息仍可能含敏感数据，生产日志访问控制、脱敏/清理是后续独立工作；本轮不宣称日志自动无秘密。测试使用虚构标记，验证响应不泄露、内部日志保留定位信息。

## 6. 测试策略与成功标准

先写测试确认编译/行为红灯并分别记录，再实现到绿色。JUnit+MockMvc测试必须走请求反序列化、校验、异常解析和JSON输出，不只调用handler方法。

- 有效name可转DTO，返回成功JSON；确认测试夹具方法确实被调用。
- name=null、空串、空白、长度101均400；长度100可通过；非法字段时夹具业务方法未执行。
- 错误JSON、缺失请求体、无法转成预期DTO的JSON输入均400，不暴露虚构敏感标记。
- 上表3种BusinessException分别断言状态/完整code/message/data。
- 未预期异常及failure(SUCCESS)错误调用均500，响应固定且不泄露内部消息。
- 错误HTTP方法和不支持媒体类型保持405/415；405必要Allow响应头保持。
- 测试日志能区分失败类别，未知异常保留服务端诊断信息；参数失败日志不包含虚构输入秘密。
- 既有HealthControllerTest与Contract28测试回归通过；没有测试夹具生产端点出现；健康响应结构不变。

本轮通过条件：助手验证、用户阅读和本机运行、绑定基础先摸底后验收、逐项评分/定位与知识错题笔记全部真实记录。未教知识先教学，不扣未教术语分。独立通过、订正通过与复习待验证分开记。

## 7. 验证命令

PowerShell中先进入backend：

```powershell
Set-Location -LiteralPath '.\backend'
.\mvnw.cmd -pl prizeflux-campaign -am '-Dtest=GlobalExceptionHandlerTest,HealthControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd test
.\mvnw.cmd -DskipTests validate
git -C (git rev-parse --show-toplevel) diff --check
```

`-am`一起构建Contract依赖；failIfNoSpecifiedTests=false仅允许上游模块没有指定类，不意味着目标测试实际执行可省略。必须核对目标报告与测试统计。

## 8. 行为边界与复审

- 始终：保留用户修改；使用已批准契约；先测后实现；分别记录助手/用户结果；模块进入/结束显示层级、评分、证据及唯一下步。
- 先询问：变更D2-01结构/错误码、扩大生产接口/字段或新增其他依赖、日志安全基础设施、CI/数据库修改。
- 禁止：声称测试夹具就是完整活动API；删除失败测试或改健康契约凑通过；提交秘密；未经授权推送/部署。

复审结论：依赖方向Campaign→Contract，无循环；错误响应保持data=null；400/422/500不同；测试入口不入生产；健康契约及用户修改保留。各审批阶段已完成，助手本地验证通过，学习者实践/知识验收另行核验，不混为已完成。

参考：Spring6.2官方@RequestBody与异常处理说明，用于确认反序列化/@Valid及异常解析的框架机制；项目状态映射为本规格自定义。

- https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-methods/requestbody.html
- https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-exceptionhandler.html
