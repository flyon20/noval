# Phone SMS Auth And Cloudflare Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有用户名密码认证切换为手机号主账号体系，接入阿里云短信认证一期能力，并保持现有双 token 会话模型不变。

**Architecture:** 先改 Phase2 MySQL/H2 认证基线和后端认证接口，再接入短信发送/校验服务与风控，最后改前端登录页为四态表单并对齐新接口。会话、refresh cookie、Redis/MySQL session 模型继续复用，只调整账号标识和登录入口。

**Tech Stack:** Spring Boot 3.2, JdbcTemplate, Redis, H2/MySQL SQL scripts, Vue 3, Pinia, Vitest, Element Plus

---

### Task 1: 固定数据库与测试基线

**Files:**
- Modify: `backend/sql/mysql/phase2-schema.sql`
- Modify: `backend/sql/mysql/phase2-seed.sql`
- Modify: `backend/src/test/resources/sql/phase2-schema-h2.sql`
- Modify: `backend/src/test/resources/sql/phase2-data-h2.sql`
- Test: `backend/src/test/java/com/novelanalyzer/sql/MySqlPhase2SchemaTest.java`

- [ ] **Step 1: 写失败测试，锁定手机号主账号与短信日志表结构**

在 `MySqlPhase2SchemaTest.java` 增加断言：
- `sys_user.phone` 为非空且唯一
- `sys_user.username` 允许为空或至少不再作为唯一登录主标识
- `sys_login_log` 包含 `phone` / `login_type`
- 新增 `sys_sms_code_log`

- [ ] **Step 2: 运行单测确认失败**

Run: `mvn "-Dtest=MySqlPhase2SchemaTest" "-Dsurefire.forkCount=0" test`
Expected: FAIL，当前 schema 不满足手机号主账号与短信审计表要求

- [ ] **Step 3: 最小修改 MySQL 与 H2 schema/data**

修改 SQL：
- `sys_user.phone` 改为 `NOT NULL` 并唯一
- 调整种子数据为手机号账号
- `sys_login_log` 增加手机号与登录方式字段
- 新增 `sys_sms_code_log`

- [ ] **Step 4: 运行单测确认通过**

Run: `mvn "-Dtest=MySqlPhase2SchemaTest" "-Dsurefire.forkCount=0" test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/sql/mysql/phase2-schema.sql backend/sql/mysql/phase2-seed.sql backend/src/test/resources/sql/phase2-schema-h2.sql backend/src/test/resources/sql/phase2-data-h2.sql backend/src/test/java/com/novelanalyzer/sql/MySqlPhase2SchemaTest.java
git commit -m "test: align auth schema with phone-based accounts"
```

### Task 2: 重构认证仓储与 DTO 为手机号模型

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/LoginRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/PasswordLoginRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/SmsLoginRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/SmsSendRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/dto/PasswordResetRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/model/AuthUserEntity.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/repository/AuthRepository.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: 写失败测试，锁定手机号字段与新接口请求结构**

在 `AuthControllerTest.java` 增加或改写用例：
- 密码登录改用 `phone`
- 注册改用 `phone + smsCode + password`
- 增加 `POST /api/auth/login/password`
- 增加 `POST /api/auth/login/sms`
- 增加 `POST /api/auth/sms/send`
- 增加 `POST /api/auth/password/reset`

- [ ] **Step 2: 运行认证控制器单测确认失败**

Run: `mvn "-Dtest=AuthControllerTest" "-Dsurefire.forkCount=0" test`
Expected: FAIL，接口路径或字段不匹配

- [ ] **Step 3: 最小修改 DTO、Entity、Repository**

实现：
- 查询用户由 `username` 切到 `phone`
- 插入用户按手机号创建，昵称可空
- 登录日志写入 `phone` 和 `login_type`

- [ ] **Step 4: 运行认证控制器单测，确认仍有短信服务相关失败**

Run: `mvn "-Dtest=AuthControllerTest" "-Dsurefire.forkCount=0" test`
Expected: 部分 FAIL，但失败集中在尚未实现的短信/流程逻辑

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/auth
git commit -m "refactor: switch auth dto and repository to phone identity"
```

### Task 3: 接入短信发送与校验服务

**Files:**
- Create: `backend/src/main/java/com/novelanalyzer/config/SmsAuthProperties.java`
- Modify: `backend/src/main/java/com/novelanalyzer/config/AuthConfig.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/model/SmsCodeLogEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/repository/SmsCodeLogRepository.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/service/SmsAuthService.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/service/SmsRiskControlService.java`
- Create: `backend/src/test/java/com/novelanalyzer/modules/auth/service/SmsAuthServiceTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: 写失败测试，锁定短信发送/校验行为**

新增 `SmsAuthServiceTest.java`：
- 发送短信时创建 `sys_sms_code_log`
- 同手机号 60 秒内重复发送被拒绝
- 验证成功后记录消费状态
- 校验失败次数超阈值后冻结

- [ ] **Step 2: 运行短信服务单测确认失败**

Run: `mvn "-Dtest=SmsAuthServiceTest" "-Dsurefire.forkCount=0" test`
Expected: FAIL，服务和仓储尚不存在

- [ ] **Step 3: 最小实现短信服务边界**

实现：
- 统一封装短信发送和验码接口
- 首期先支持通过配置关闭真实外呼，并允许测试桩运行
- 按手机号/IP 做 Redis 频控
- 审计表落库

- [ ] **Step 4: 运行短信服务单测确认通过**

Run: `mvn "-Dtest=SmsAuthServiceTest" "-Dsurefire.forkCount=0" test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/novelanalyzer/config/SmsAuthProperties.java backend/src/main/java/com/novelanalyzer/modules/auth backend/src/test/java/com/novelanalyzer/modules/auth/service/SmsAuthServiceTest.java
git commit -m "feat: add sms auth service and risk controls"
```

### Task 4: 实现手机号认证控制器与会话联动

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/auth/service/PhoneAuthService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/auth/vo/TokenResponse.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthSessionLoginIntegrationTest.java`

- [ ] **Step 1: 写失败测试，锁定登录/注册/重置密码全流程**

在控制器与集成测试中增加：
- `phone + password` 登录成功并落 session
- `phone + smsCode` 登录成功并落 session
- `phone + smsCode + password` 注册成功并自动登录
- 重置密码成功后所有 session 被撤销

- [ ] **Step 2: 运行认证相关测试确认失败**

Run: `mvn "-Dtest=AuthControllerTest,AuthSessionLoginIntegrationTest" "-Dsurefire.forkCount=0" test`
Expected: FAIL，流程未实现

- [ ] **Step 3: 最小实现控制器与服务**

实现：
- 保留 `/refresh`、`/logout`
- 新增 `/api/auth/login/password`
- 新增 `/api/auth/login/sms`
- 新增 `/api/auth/sms/send`
- 改造 `/api/auth/register`
- 新增 `/api/auth/password/reset`
- 重置密码后批量撤销该用户所有 session

- [ ] **Step 4: 运行认证相关测试确认通过**

Run: `mvn "-Dtest=AuthControllerTest,AuthSessionLoginIntegrationTest" "-Dsurefire.forkCount=0" test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/auth backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthControllerTest.java backend/src/test/java/com/novelanalyzer/modules/auth/controller/AuthSessionLoginIntegrationTest.java
git commit -m "feat: add phone password and sms auth flows"
```

### Task 5: 对齐前端认证类型与 API

**Files:**
- Modify: `frontend/src/types/auth.ts`
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/src/lib/auth-session.ts`
- Modify: `frontend/src/utils/jwt.ts`
- Test: `frontend/src/lib/__tests__/http.spec.ts`
- Test: `frontend/src/utils/__tests__/jwt.spec.ts`

- [ ] **Step 1: 写失败测试，锁定手机号认证 API 与 claims 兼容**

增加前端测试：
- `authApi` 调用新接口路径
- JWT claims 支持 `phone`
- 旧 `refresh/logout` 逻辑不回归

- [ ] **Step 2: 运行相关前端测试确认失败**

Run: `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/lib/__tests__/http.spec.ts src/utils/__tests__/jwt.spec.ts`
Expected: FAIL

- [ ] **Step 3: 最小实现类型与 API 对齐**

实现：
- 新增 `PasswordLoginRequest`、`SmsLoginRequest`、`SmsSendRequest`、`PasswordResetRequest`
- 保持旧 token 存储逻辑不变

- [ ] **Step 4: 运行相关前端测试确认通过**

Run: `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/lib/__tests__/http.spec.ts src/utils/__tests__/jwt.spec.ts`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add frontend/src/types/auth.ts frontend/src/api/auth.ts frontend/src/lib/auth-session.ts frontend/src/utils/jwt.ts frontend/src/lib/__tests__/http.spec.ts frontend/src/utils/__tests__/jwt.spec.ts
git commit -m "refactor: align frontend auth client with phone login api"
```

### Task 6: 改造登录页为四态认证表单

**Files:**
- Modify: `frontend/src/views/login/LoginView.vue`
- Modify: `frontend/src/views/login/__tests__/LoginView.spec.ts`
- Modify: `frontend/src/stores/auth.ts`
- Test: `frontend/src/views/login/__tests__/LoginView.spec.ts`

- [ ] **Step 1: 写失败测试，锁定四态认证 UI**

在 `LoginView.spec.ts` 增加：
- 默认密码登录使用手机号输入
- 可切换验证码登录
- 注册必须填写验证码
- 可切换重置密码
- 发码按钮触发新 API
- 重置密码成功后回到密码登录

- [ ] **Step 2: 运行登录页测试确认失败**

Run: `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/views/login/__tests__/LoginView.spec.ts`
Expected: FAIL

- [ ] **Step 3: 最小实现登录页四态交互**

实现：
- 复用现有 `/login` 路由
- 支持密码登录、验证码登录、注册、重置密码
- 加手机号图标入口和验证码倒计时
- 成功后保持现有 bootstrap 跳转

- [ ] **Step 4: 运行登录页测试确认通过**

Run: `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/views/login/__tests__/LoginView.spec.ts`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/login/LoginView.vue frontend/src/views/login/__tests__/LoginView.spec.ts frontend/src/stores/auth.ts
git commit -m "feat: add phone sms login register and reset password ui"
```

### Task 7: 端到端回归与最小文档补充

**Files:**
- Modify: `README.md`
- Modify: `docs/本地联调说明.md`
- Modify: `docs/auth-session-rollout.md`

- [ ] **Step 1: 跑后端认证回归**

Run: `mvn "-Dtest=AuthControllerTest,AuthSessionLoginIntegrationTest,AuthSessionServiceTest,Phase2SecurityIntegrationTest" "-Dsurefire.forkCount=0" test`
Expected: PASS

- [ ] **Step 2: 跑前端认证回归**

Run: `node --max-old-space-size=4096 ./node_modules/vitest/vitest.mjs run --pool=threads src/views/login/__tests__/LoginView.spec.ts src/stores/__tests__/auth.spec.ts src/lib/__tests__/auth-bootstrap.spec.ts src/lib/__tests__/http.spec.ts`
Expected: PASS

- [ ] **Step 3: 跑前端类型检查**

Run: `npm run type-check`
Expected: PASS

- [ ] **Step 4: 补充文档**

更新：
- 本地环境变量
- 手机号认证流程
- 阿里云短信认证占位配置

- [ ] **Step 5: 最终提交**

```bash
git add README.md docs/本地联调说明.md docs/auth-session-rollout.md
git commit -m "docs: document phone-based auth rollout"
```
