# 后端异常处理与事务治理规范

## 文档作用

本文定义 Foggy Navigator 后端异常处理、HTTP 响应映射、事务回滚边界和历史治理方案。目标是避免可预期业务错误被统一吞成 HTTP 500，尤其避免在事务方法内捕获异常后仍因 rollback-only 触发 `UnexpectedRollbackException`。

本文适用于 Java/Spring Boot 后端模块，包括 `business-agent-module`、`session-module`、`metadata-config-module`、`user-auth-module`、`launcher` 及各 addon Java 服务。

## 1. 问题背景

当前代码中异常处理存在三类分散逻辑：

1. Controller 层部分接口返回 `RX<T>`，但失败路径有的直接抛异常，有的手动返回 `RX.failA/B/C`。
2. Service 层大量使用 `IllegalArgumentException`、`IllegalStateException`、`SecurityException` 表达业务校验、资源不可见、readiness not-ready 等预期错误。
3. 部分事务方法已经使用 `@Transactional(noRollbackFor = ...)` 防止 readiness 异常污染外层事务，例如 `A2AgentResourceResolver`；但其它 service 未统一覆盖，导致异常被外层捕获后，事务仍已被 Spring 标记为 rollback-only，最终提交阶段变成 HTTP 500。

tenant 138 的 `ensure-tenant` 问题属于第 3 类：`grantModelConfig()` 抛出 model config visibility 异常后被外层转换为 structured blocker，但内部事务拦截器已经把共享事务标记为 rollback-only，最终触发 `UnexpectedRollbackException`。

## 2. 异常分类

后端异常必须先判断语义，再决定是否回滚和如何映射 HTTP。

| 分类 | 语义 | 示例 | HTTP | 是否默认回滚 |
|------|------|------|------|--------------|
| 请求参数错误 | 调用方请求字段非法或缺失 | 缺少 `modelConfigId`、非法 status | 400 | 否，除非已发生写入 |
| 鉴权/授权错误 | token、scope、tenant、owner 不匹配 | ClientApp 不可见、tenant mismatch | 401/403/404 | 否 |
| 资源 not-ready | 资源存在但未达到运行条件 | worker binding 缺失、directory tenant mismatch、model config not visible | 200 structured not-ready 或 409 | 否 |
| 业务冲突 | 当前状态无法执行命令 | disabled grant cannot be default、重复绑定冲突 | 409 | 视是否已写入 |
| 外部依赖失败 | Worker、LLM、Webhook、远程 HTTP 失败 | Biz worker unavailable、LLM timeout | 502/503/504 或 structured task failure | 通常是当前操作失败，应回滚本次写入 |
| 系统缺陷 | 空指针、唯一结果多行、未预期数据异常 | `NonUniqueResultException`、NPE | 500 | 是 |

不要继续把所有业务错误都塞进裸 `IllegalArgumentException`。新增或大改代码时，优先使用领域异常承载明确语义。

## 3. 统一领域异常模型

后续治理目标是引入统一领域异常基类，逐步替代分散的裸运行时异常：

```java
public class NavigatorDomainException extends RuntimeException {
    private final String errorCode;
    private final int httpStatus;
    private final List<String> missingFields;
    private final List<String> blockers;
    private final String remediationHint;
}
```

推荐派生类型：

| 异常类型 | 用途 |
|----------|------|
| `BadRequestException` | 请求参数非法 |
| `ForbiddenResourceException` | 资源存在但调用方不可见或无权访问 |
| `ResourceNotReadyException` | readiness/preflight/ensure 中的可预期 not-ready |
| `BusinessConflictException` | 状态冲突、重复操作、幂等冲突 |
| `ExternalDependencyException` | 外部系统不可用或返回不可恢复错误 |

在领域异常全面落地前，允许兼容现有 `IllegalArgumentException`、`IllegalStateException`、`SecurityException`，但必须在事务和 Controller advice 中明确处理。

## 4. HTTP 响应规范

Controller 成功返回继续使用 `RX<T>`。

可预期失败必须有稳定映射：

| 场景 | 推荐返回 |
|------|----------|
| 普通 CRUD 参数错误 | HTTP 400 + `RX.failB(message)` |
| 鉴权失败 | HTTP 401/403 + `RX.failC(message)` 或统一 security error |
| 查询不存在 | HTTP 404 + `RX.failB(message)` |
| 命令冲突 | HTTP 409 + `RX.failB(message)` |
| readiness / preflight not-ready | HTTP 200 + DTO 内 `ready=false` / `activationReady=false` / `errorCode` / `blockers` |
| Worker/LLM 外部依赖失败 | HTTP 502/503/504，或任务 DTO 内 structured failure |
| 未预期系统异常 | HTTP 500，不伪装成业务失败 |

readiness、preflight、self-healing ensure 这类接口的核心价值是诊断状态，因此资源不可见、绑定缺失、目录 tenant mismatch 等预期问题应该返回 structured not-ready，而不是 HTTP 500。

## 5. 事务规范

### 5.1 事务方法不得随意 catch 后继续

如果事务内调用的 Spring 代理方法抛出 RuntimeException，即使外层 catch 住，事务也可能已经被标记为 rollback-only。表现是业务日志显示已返回 DTO，但提交阶段抛：

```text
UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only
```

因此，以下写法只在被调用方法已经配置正确 `noRollbackFor` 时才安全：

```java
try {
    service.validateOrGrant(...);
} catch (IllegalArgumentException e) {
    result.getBlockers().add(e.getMessage());
}
```

### 5.2 可预期校验异常使用 noRollbackFor

凡是 service 方法可能被 readiness / ensure / preflight 调用，并且会抛出可预期资源异常，必须使用统一元注解：

```java
@ReadinessTransactional(readOnly = true)
```

该注解位于 `business-agent-module` 的 `com.foggy.navigator.business.agent.transaction.ReadinessTransactional`，统一声明 `readOnly` / `propagation` 与兼容期 `noRollbackFor` 策略。不要在同一类方法里继续散落手写 `@Transactional(noRollbackFor = ...)`。

非 `business-agent-module` 暂未引入该注解时，等价策略必须显式声明：

```java
@Transactional(noRollbackFor = {
        IllegalArgumentException.class,
        IllegalStateException.class,
        SecurityException.class
})
```

read-only resolver 方法同理：

```java
@Transactional(readOnly = true, noRollbackFor = {
        IllegalArgumentException.class,
        IllegalStateException.class,
        SecurityException.class
})
```

兼容期保留三类 JDK 运行时异常是为了不让历史代码继续制造 rollback-only。新代码应逐步收敛到 `NavigatorDomainException` 及其派生类；引入领域异常后，应先加入统一元注解，而不是在各 service 重复维护异常列表。

### 5.3 写入与 readiness 校验分阶段

优先采用两阶段结构：

1. `ensure/update/create`：负责幂等创建或刷新资源。
2. `readiness/preflight/validate`：负责读取和诊断资源状态，返回 structured not-ready。

如果一个 ensure 接口必须同时写入和诊断，诊断阶段调用的方法必须满足：

- read-only 或只做幂等修复；
- 可预期异常 `noRollbackFor`；
- 对 self-healing / readiness 聚合类入口，优先使用 `@ReadinessTransactional(propagation = Propagation.NOT_SUPPORTED)`，避免一个大外层事务被某个下游资源校验标记为 rollback-only；
- 不在校验失败后继续执行依赖已失败资源的危险写入；
- response 中明确 `activationReady=false`、`errorCode`、`missingFields`、`blockers`、`remediationHint`。

### 5.4 需要回滚的异常不要 noRollbackFor

以下异常不应加入通用 `noRollbackFor`：

- 数据库唯一约束、乐观锁、悲观锁失败；
- ORM 非唯一结果、实体状态异常；
- NPE、序列化 bug、类型转换 bug；
- 外部依赖写入一半失败且无法补偿的异常。

这些代表系统缺陷或写操作失败，应让事务回滚，并由全局异常处理返回 500 或明确的外部依赖错误。

## 6. ControllerAdvice 治理目标

当前项目存在局部 `@RestControllerAdvice`，但尚未形成全局统一模型。建议在后续专项中新增或整理统一异常处理器：

```java
@RestControllerAdvice
public class NavigatorApiExceptionHandler {
    @ExceptionHandler(NavigatorDomainException.class)
    ResponseEntity<RX<?>> handleDomain(NavigatorDomainException ex) { ... }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<RX<?>> handleBadRequest(IllegalArgumentException ex) { ... }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<RX<?>> handleSecurity(SecurityException ex) { ... }

    @ExceptionHandler(UnexpectedRollbackException.class)
    ResponseEntity<RX<?>> handleUnexpectedRollback(UnexpectedRollbackException ex) { ... }
}
```

注意：`UnexpectedRollbackException` 的 handler 只能用于暴露清晰诊断，不应把它当成正常业务分支。真正修复点仍在事务边界和异常分类。

## 7. 测试要求

涉及异常和事务治理的改动必须补以下测试之一：

1. 反射测试：确认关键 service 方法存在 `@Transactional(noRollbackFor = ...)`。
2. Service 单测：模拟资源不可见、tenant mismatch、worker binding 缺失，确认返回 structured not-ready。
3. Controller/Web 测试：确认预期业务异常不会变成 HTTP 500。
4. 集成测试：对 self-healing ensure / preflight / ask 链路确认失败语义稳定。

测试断言至少覆盖：

- HTTP status；
- `RX.code` / DTO `errorCode`；
- `activationReady` 或 `ready`；
- `missingFields`；
- `blockers`；
- `remediationHint`。

## 8. 现有代码治理计划

### 阶段 1：止血

- 对 readiness / preflight / ensure 相关 service 补齐 `noRollbackFor`。
- 将已知资源异常转换为 structured not-ready。
- 对 tenant ensure、A2Agent resolver、ClientApp model grant 等高频路径补单测。

### 阶段 2：统一异常类型

- 新增 `NavigatorDomainException` 和派生异常。
- 将新代码默认使用领域异常。
- 将历史裸 `IllegalArgumentException` 中的资源可见性、状态冲突、not-ready 语义逐步替换。

### 阶段 3：统一 HTTP 映射

- 建立全局 `NavigatorApiExceptionHandler`。
- 清理 Controller 内重复 try/catch 和手工 `RX.fail*`。
- 为开放接口、BFF、后台管理接口定义一致的 status code 策略。

### 阶段 4：专项扫描

每次遇到 HTTP 500 但本质是业务语义时，必须回填治理项：

- 根因属于异常分类错误、事务 rollback-only、Controller advice 缺失，还是数据库/索引系统缺陷；
- 是否需要新增领域异常；
- 是否需要补 `noRollbackFor`；
- 是否需要回归测试；
- 是否需要历史数据 repair API。

## 9. 开发检查清单

开发或评审后端异常处理时，至少检查：

- 这个异常是系统缺陷，还是调用方/资源状态的预期错误；
- 如果外层会 catch 并继续返回 DTO，被调用事务方法是否 `noRollbackFor`；
- 是否把 readiness/preflight 的 not-ready 错误错误映射成了 HTTP 500；
- response 是否包含足够的 `errorCode`、`missingFields`、`blockers`、`remediationHint`；
- 是否有测试固定事务边界和响应语义；
- 日志是否记录资源定位信息，但不泄露密钥、token、ClientApp secret。
