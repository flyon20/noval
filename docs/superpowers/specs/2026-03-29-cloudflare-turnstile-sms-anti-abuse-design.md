# Cloudflare Turnstile 与短信防刷落地设计

## 1. 目标

- 在当前手机号认证体系上，为短信验证码发送链路增加 Cloudflare Turnstile 服务端校验。
- 将短信防刷从“仅手机号冷却”升级为“手机号 + IP + 业务类型”的多层风控。
- 保持现有密码登录、短信登录、注册、重置密码主流程不变，仅增强 `/api/auth/sms/send` 的发码门槛。
- 保持 Cloudflare secret 只存在于后端，前端仅拿 site key。

## 2. 当前现状

前端登录页集中在：

- `frontend/src/views/login/LoginView.vue`
- `frontend/src/api/auth.ts`

后端发码与认证集中在：

- `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/SmsAuthService.java`
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/SmsRiskControlService.java`

当前短信风控只有：

- 按手机号做 Redis 冷却键 `sms:interval:{phone}`
- 未校验任何 Turnstile token

## 3. 方案选择

### 方案 A：Turnstile + 后端 Siteverify + 本地短信风控联动

推荐采用。

优点：

- 与当前项目结构最匹配，改动边界清晰。
- 能显著降低恶意刷短信成本。
- 后续可以继续叠加 Cloudflare WAF / Rate Limit，不冲突。

缺点：

- 需要新增前后端配置和一条校验链路。

### 方案 B：仅 Cloudflare 边缘限速

不推荐单独采用。

缺点：

- 无法可靠拦截分布式低频攻击。
- 无法基于手机号做业务级风控。

### 方案 C：仅自研验证码/设备指纹

不建议当前阶段采用。

缺点：

- 实现与维护成本更高。
- 首期抗滥用效果通常不如 Turnstile 成熟。

## 4. 目标架构

### 4.1 前端

- 登录页仅在需要短信验证码的场景显示 Turnstile：
  - 短信登录
  - 注册
  - 重置密码
- 点击“发送验证码”前，必须拿到有效 Turnstile token。
- token 仅随 `/api/auth/sms/send` 请求提交，不参与密码登录和最终登录提交。

### 4.2 后端

- 为 `/api/auth/sms/send` 新增 Turnstile token 入参。
- 后端新增 `TurnstileService`：
  - 调用 Cloudflare Siteverify
  - 校验 `success`
  - 可选校验 `hostname` / `action`
- 后端在 Turnstile 通过后，再进入短信风控与阿里云短信发送。

### 4.3 风控

`SmsRiskControlService` 从单一手机号冷却升级为：

- 手机号最小间隔限制
- 同一 IP 的窗口限制
- 同一手机号在窗口内的累计限制
- `bizType + phone` 维度限制

Redis 不可用时：

- 不应阻断整个登录页
- 但至少保留手机号最小冷却的内存级降级或温和放行策略

## 5. API 设计

### 5.1 发码请求

`POST /api/auth/sms/send`

请求体新增字段：

```json
{
  "phone": "13800138000",
  "bizType": "REGISTER",
  "turnstileToken": "<cf-turnstile-response>"
}
```

### 5.2 系统配置

新增后端配置来源：

- `CLOUDFLARE_TURNSTILE_ENABLED`
- `CLOUDFLARE_TURNSTILE_SITE_KEY`
- `CLOUDFLARE_TURNSTILE_SECRET_KEY`
- `CLOUDFLARE_TURNSTILE_VERIFY_URL`
- `CLOUDFLARE_TURNSTILE_EXPECT_HOSTNAME`

可选追加到 `system_config` 的运行时阈值：

- `security.sms.ip.window-seconds`
- `security.sms.ip.max-attempts`
- `security.sms.phone.window-seconds`
- `security.sms.phone.max-attempts`

## 6. 前端实现边界

- `auth.ts` 的 `SmsSendRequest` 扩展 `turnstileToken`
- 登录页新增 Turnstile 状态：
  - 当前 token
  - 过期状态
  - 校验失败提示
- 新增独立组件承载 Turnstile widget，避免将第三方脚本细节塞进 `LoginView.vue`

推荐新增：

- `frontend/src/components/auth/TurnstileWidget.vue`

## 7. 后端实现边界

推荐新增：

- `backend/src/main/java/com/novelanalyzer/config/CloudflareTurnstileProperties.java`
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/TurnstileService.java`
- `backend/src/main/java/com/novelanalyzer/modules/auth/dto/TurnstileVerifyResult.java` 或内部 VO

调整：

- `SmsSendRequest` 增加 `turnstileToken`
- `AuthController.sendSmsCode(...)` 先校验 Turnstile，再走风控和发码
- `SmsRiskControlService` 扩展多维限流键

## 8. 错误处理

对用户暴露的提示保持克制：

- Turnstile 缺失或失败：`请完成人机校验后再发送验证码`
- 发码过频：`验证码发送过于频繁，请稍后再试`
- 系统异常：`验证码发送失败，请稍后重试`

日志中记录：

- phone 脱敏值
- requestIp
- bizType
- turnstile success / failure
- Cloudflare 错误码

## 9. 测试策略

### 9.1 后端

先补失败测试：

- 未提供 Turnstile token 时 `/api/auth/sms/send` 返回 400
- Turnstile 校验失败时返回 400 / 403
- Turnstile 通过时才允许继续调用 `SmsAuthService`
- 同一手机号超限被拒绝
- 同一 IP 超限被拒绝

### 9.2 前端

先补失败测试：

- 短信模式下无 token 不能发送验证码
- widget 成功回调后能携带 token 发请求
- token 过期后需要重新完成校验

## 10. 验证命令

后端：

- `mvn "-Dtest=AuthControllerTest,SmsAuthServiceTest" test`

前端：

- `npm run test -- --run src/views/login/__tests__/LoginView.spec.ts`

## 11. 非目标

- 本轮不接 Cloudflare WAF API 自动下发规则
- 本轮不接 Cloudflare pre-clearance
- 本轮不做设备指纹 SDK
