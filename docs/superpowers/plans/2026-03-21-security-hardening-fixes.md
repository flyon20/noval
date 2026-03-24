# Security Hardening Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复当前后端认证与权限链路中的高风险安全缺口，并用测试锁定行为。

**Architecture:** 保持现有自定义过滤器与 `@RequireRole` 权限框架不变，在认证入口、刷新链路、IP 解析与响应写回层做最小必要修复。优先复用现有 `RateLimitService`、黑名单与日志机制，避免引入新的认证框架迁移风险。

**Tech Stack:** Spring Boot 3.2, Java 17, JUnit 5, MockMvc, H2

---

### Task 1: 补安全失败测试

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\auth\controller\AuthControllerTest.java`
- Modify: `D:\Git\agent\noval\backend\src\test\java\com\novelanalyzer\modules\security\Phase2SecurityIntegrationTest.java`
- Create: `D:\Git\agent\noval\backend\src\test\resources\application.yml`

- [ ] Step 1: 为登出后 refresh、禁用用户 refresh、伪造 XFF、真实 HTTP 状态码补失败测试
- [ ] Step 2: 运行相关测试，确认按预期失败

### Task 2: 修复认证与刷新链路

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\AuthProperties.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\auth\service\AuthService.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\auth\repository\AuthRepository.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\AuthConfigValidator.java`

- [ ] Step 1: 移除默认 demo 回退并增加安全配置校验
- [ ] Step 2: 让 refresh 校验黑名单并回查数据库用户与角色
- [ ] Step 3: 运行认证相关测试确认通过

### Task 3: 修复登录限流与 IP 解析

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\config\SecurityProperties.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\auth\controller\AuthController.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\modules\security\filter\AuthTokenFilter.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\common\web\RequestIpResolver.java`

- [ ] Step 1: 抽取统一 IP 解析器并引入可信代理配置
- [ ] Step 2: 让登录入口接入限流
- [ ] Step 3: 运行安全测试确认通过

### Task 4: 修复 HTTP 状态码语义

**Files:**
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\common\exception\GlobalExceptionHandler.java`
- Modify: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\common\utils\JsonResponseWriter.java`
- Create: `D:\Git\agent\noval\backend\src\main\java\com\novelanalyzer\common\result\HttpStatusMapper.java`

- [ ] Step 1: 为 `ResultCode` 映射真实 HTTP 状态码
- [ ] Step 2: 修复过滤器和全局异常处理的写回逻辑
- [ ] Step 3: 运行相关测试确认通过

### Task 5: 全量回归

**Files:**
- Modify: `D:\Git\agent\noval\progress.md`

- [ ] Step 1: 运行 `mvn test`
- [ ] Step 2: 记录验证结果与剩余风险
