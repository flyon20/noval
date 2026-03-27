# 手机号短信认证与 Cloudflare 防护设计

## 1. 背景

当前项目认证体系仍以 `username + password` 为主，前端登录入口集中在 `frontend/src/views/login/LoginView.vue`，后端认证核心位于：

- `backend/src/main/java/com/novelanalyzer/modules/auth/controller/AuthController.java`
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthService.java`
- `backend/src/main/java/com/novelanalyzer/modules/auth/service/AuthSessionService.java`

当前会话机制已经升级为双 token：

- `access token`：JWT，短时有效
- `refresh token`：HttpOnly Cookie
- 会话热状态：Redis
- 会话持久化：MySQL `sys_user_session`

本设计目标是在不推翻现有双 token 会话体系的前提下，将账号主标识切换为手机号，并接入阿里云号码认证服务中的短信认证能力；认证链路稳定后，再接入 Cloudflare 做边缘防护。

## 2. 已确认决策

- 账号主标识改为手机号。
- 允许破坏式改库，不保留旧用户名账号体系。
- 登录支持两种方式并存：
  - `手机号 + 密码`
  - `手机号 + 短信验证码`
- 注册必须使用短信验证码。
- 新增重置密码能力，必须使用短信验证码。
- 密码登录不要求二次短信校验。
- 昵称在注册时不必填。
- 重置密码成功后，强制让该手机号所有已登录设备下线。
- 第一期只做阿里云短信认证接入；第二期再接 Cloudflare。

## 3. 范围划分

### 3.1 第一期 In Scope

1. 手机号主账号体系落地。
2. 阿里云短信认证接入。
3. 登录页支持密码登录、验证码登录、手机号注册、重置密码四种状态。
4. 后端新增短信发送、验证码登录、密码登录、注册、重置密码接口。
5. 保持现有双 token 机制不变。
6. 增加短信风控、审计和会话失效策略。

### 3.2 第二期 In Scope

1. Cloudflare Turnstile 接入认证入口。
2. Cloudflare WAF / Rate Limiting 保护认证相关路径。
3. 与后端本地频控联动，形成双层防护。

### 3.3 Out of Scope

1. 第一期不做社交登录。
2. 第一期不做海外手机号支持。
3. 第一期不做短信模板运营后台。
4. 第一期不改分析、爬虫、趋势等业务模块。

## 4. 方案选择

采用“保留密码体系，新增短信验证码体系，双入口并存”的方案。

原因：

- 改动集中在认证域，不影响现有会话协议和受保护页面。
- 用户体验自然，密码登录与验证码登录各自独立。
- 注册与重置密码可直接复用短信验证码能力。
- 第二期接 Cloudflare 时，可以明确针对 `sms/send`、`login/sms`、`register`、`password/reset` 做边缘防护。

不采用“验证码优先、密码退居辅助”的原因是短信成本和风控压力会更高；也不采用“先接 Cloudflare 再改认证”的原因是认证模型本身尚未稳定，容易返工边缘规则。

## 5. 认证模型设计

### 5.1 账号模型

首期将 `phone` 作为唯一账号标识。

为降低 Java 实体、Mapper、日志和前端展示改动量，`sys_user.username` 字段暂不删除，语义调整为“昵称 / 展示名”，允许为空，不再作为登录账号使用。

账号登录规则：

- 密码登录：`phone + password`
- 验证码登录：`phone + smsCode`
- 注册：`phone + smsCode + password`
- 重置密码：`phone + smsCode + newPassword`

### 5.2 会话模型

以下机制保持不变：

- 登录成功继续签发 `accessToken`
- `refreshToken` 继续写入 HttpOnly Cookie
- Redis 继续保存会话热状态
- MySQL `sys_user_session` 继续保存持久化会话
- `refresh`、`logout`、会话恢复逻辑继续沿用

需要调整的仅是登录成功后的账号 claims 来源：

- `uid`：保持 user id
- `username`：改为展示名；若为空可回退为脱敏手机号或完整手机号
- 新增建议 claim：`phone`

## 6. 数据库设计

### 6.1 `sys_user`

建议首期直接调整为：

- `phone VARCHAR(20) NOT NULL UNIQUE`
- `username VARCHAR(50) NULL`，语义为昵称
- `password VARCHAR(100) NOT NULL`

建议补充字段：

- `phone_verified TINYINT DEFAULT 1`
- `password_updated_time DATETIME`

说明：

- 首期注册一定经过短信验证码校验，因此新用户默认视为 `phone_verified = 1`
- 首期仍要求注册时设置密码，因此 `password` 不允许为空

### 6.2 `sys_login_log`

保留现有表，新增或调整以下字段以支持手机号体系：

- `phone VARCHAR(20)`
- `login_type VARCHAR(20)`：`PASSWORD` / `SMS`

`username` 字段可保留，用于记录昵称快照；但认证链路不再依赖该字段。

### 6.3 `sys_user_session`

不需要结构性改动，只需保证：

- 会话创建时能记录手机号账号对应的 `user_id`
- 重置密码后可按 `user_id` 撤销全部活跃 session

### 6.4 新增短信审计表 `sys_sms_code_log`

建议新增表，用于审计、频控关联和问题排查。

建议字段：

- `id BIGINT PK`
- `phone VARCHAR(20) NOT NULL`
- `biz_type VARCHAR(32) NOT NULL`
- `provider VARCHAR(32) NOT NULL DEFAULT 'aliyun-pnvs'`
- `out_id VARCHAR(64) NOT NULL`
- `request_id VARCHAR(64)`
- `biz_id VARCHAR(64)`
- `scheme_name VARCHAR(32)`
- `status VARCHAR(32) NOT NULL`
- `verify_result VARCHAR(32)`
- `send_ip VARCHAR(50)`
- `trace_id VARCHAR(64)`
- `message VARCHAR(500)`
- `expire_time DATETIME`
- `verified_time DATETIME`
- `consumed_time DATETIME`
- `create_time DATETIME`
- `update_time DATETIME`
- `deleted TINYINT`

状态建议：

- `SENT`
- `SEND_FAILED`
- `VERIFIED`
- `VERIFY_FAILED`
- `CONSUMED`
- `EXPIRED`
- `REVOKED`

说明：

- 采用阿里云动态验证码模式时，服务端不保存验证码明文，也不保存验证码哈希。
- 服务端通过 `out_id + phone + biz_type` 关联一次待验证会话。

## 7. 阿里云短信认证接入设计

### 7.1 采用能力

第一期采用阿里云号码认证服务中的短信认证 API：

- `SendSmsVerifyCode`
- `CheckSmsVerifyCode`

采用服务端接入，不在客户端集成阿里云认证 SDK。

### 7.2 接入策略

首期采用阿里云动态生成验证码并校验的模式，避免本地自行生成和保存验证码。

发送时使用：

- `TemplateParam = {"code":"##code##","min":"5"}`

这样验证码由阿里云生成，后端只负责：

- 发码请求
- 保存 `out_id`、`request_id`、`biz_id`、业务类型、有效期和状态
- 核验用户输入的验证码
- 成功后消费这次验证码记录

### 7.3 模板与签名

用户当前尚未确定模板。

首期设计约束：

- 推荐优先使用阿里云短信认证控制台赠送的签名和模板
- 首期至少准备 3 套业务模板：
  - 注册验证码
  - 登录验证码
  - 重置密码验证码

如果控制台限制需要统一模板，也允许首期以“通用验证码模板 + 业务文案前缀”方式落地，但接口仍保留 `bizType`，方便后续分模板。

### 7.4 环境变量

首期建议使用以下环境变量，不入库真实密钥：

- `ALIYUN_DYPNS_ACCESS_KEY_ID`
- `ALIYUN_DYPNS_ACCESS_KEY_SECRET`
- `ALIYUN_DYPNS_ENDPOINT`
  - 默认：`dypnsapi.aliyuncs.com`
- `ALIYUN_DYPNS_COUNTRY_CODE`
  - 默认：`86`
- `ALIYUN_DYPNS_SCHEME_NAME`
  - 默认：`noval-web`
- `ALIYUN_DYPNS_SIGN_NAME`
- `ALIYUN_DYPNS_TEMPLATE_CODE_REGISTER`
- `ALIYUN_DYPNS_TEMPLATE_CODE_LOGIN`
- `ALIYUN_DYPNS_TEMPLATE_CODE_RESET_PASSWORD`
- `ALIYUN_DYPNS_VALID_TIME_MINUTES`
  - 默认：`5`
- `ALIYUN_DYPNS_INTERVAL_SECONDS`
  - 默认：`60`

说明：

- `scheme_name` 发送和校验时需保持一致。
- `sign_name` / `template_code` 由你后续在控制台选择后填入。

## 8. 后端接口设计

### 8.1 短信发送

`POST /api/auth/sms/send`

请求：

```json
{
  "phone": "13800138000",
  "bizType": "REGISTER"
}
```

说明：

- `bizType` 枚举：`REGISTER` / `LOGIN` / `RESET_PASSWORD`
- 后端对不同业务类型应用不同前置校验：
  - `REGISTER`：手机号未注册才允许发送
  - `LOGIN`：手机号已注册才允许发送
  - `RESET_PASSWORD`：手机号已注册才允许发送
- 对外错误提示统一，不暴露账号是否存在

### 8.2 密码登录

`POST /api/auth/login/password`

请求：

```json
{
  "phone": "13800138000",
  "password": "YourPassword123",
  "deviceLabel": "Chrome on Windows"
}
```

响应沿用当前双 token 协议。

### 8.3 验证码登录

`POST /api/auth/login/sms`

请求：

```json
{
  "phone": "13800138000",
  "smsCode": "123456",
  "deviceLabel": "Chrome on Windows"
}
```

流程：

1. 找到该手机号最近一条未消费、未过期的 `LOGIN` 验证记录。
2. 调用 `CheckSmsVerifyCode`。
3. 成功则消费验证码记录并创建 session。
4. 返回 `accessToken`，写入 refresh cookie。

### 8.4 注册

`POST /api/auth/register`

请求：

```json
{
  "phone": "13800138000",
  "smsCode": "123456",
  "password": "YourPassword123"
}
```

流程：

1. 核验 `REGISTER` 类型验证码。
2. 创建用户。
3. 默认赋予 `USER` 角色。
4. 直接签发登录态。

### 8.5 重置密码

`POST /api/auth/password/reset`

请求：

```json
{
  "phone": "13800138000",
  "smsCode": "123456",
  "newPassword": "NewPassword123"
}
```

流程：

1. 核验 `RESET_PASSWORD` 类型验证码。
2. 更新密码与 `password_updated_time`。
3. 撤销该用户所有活跃 session。
4. 清理 Redis 会话热状态。
5. 将当前可识别 access token 拉黑。
6. 返回成功，不自动登录。

## 9. 后端服务拆分建议

建议新增以下服务边界：

### 9.1 `SmsAuthService`

职责：

- 调用阿里云发码
- 调用阿里云验码
- 维护 `sys_sms_code_log`
- 提供“查找当前有效验证码会话”“消费验证码”能力

### 9.2 `PhoneAuthService`

职责：

- 手机号密码登录
- 手机号验证码登录
- 手机号注册
- 手机号重置密码

### 9.3 `SmsRiskControlService`

职责：

- 发码频控
- 验码失败计数
- 手机号/IP 维度冻结

### 9.4 复用现有服务

- `AuthSessionService`：继续负责 session 创建、撤销、刷新
- `RefreshTokenService`：继续负责 refresh token
- `TokenBlacklistService`：继续负责 access token 黑名单

## 10. 风控与频率限制

### 10.1 首期推荐默认值

短信发送：

- 同手机号：`60 秒` 内最多 1 次
- 同手机号：`1 小时` 最多 5 次
- 同手机号：`24 小时` 最多 10 次
- 同 IP：`1 小时` 最多 10 次
- 同 IP：`24 小时` 最多 30 次

验证码校验：

- 单条验证码记录最多校验 5 次
- 同手机号 1 小时最多失败 10 次
- 同 IP 1 小时最多失败 20 次

冻结策略：

- 达到失败阈值后短时冻结 15 分钟
- 风险提示统一返回，不暴露具体冻结原因

### 10.2 存储策略

首期建议：

- 高频限流计数放 Redis
- 审计明细放 MySQL `sys_sms_code_log`

## 11. 前端页面设计

### 11.1 登录页状态

继续复用现有 `/login` 页面，不新增新路由，内部切换 4 个状态：

- 密码登录
- 验证码登录
- 手机号注册
- 重置密码

### 11.2 UI 约束

- 默认显示密码登录
- 密码表单账号输入框改为手机号
- 密码登录表单下方增加“手机验证码登录”入口，使用手机图标
- 保留注册入口，改为手机号注册
- 增加“忘记密码”入口
- 验证码按钮显示倒计时
- 手机号统一做大陆手机号格式校验
- 所有错误提示走统一提示区

### 11.3 交互规则

- 发码成功：只提示“验证码已发送”
- 发码失败：不暴露手机号是否已注册
- 注册成功：直接进入首页
- 验证码登录成功：直接进入首页
- 重置密码成功：跳回密码登录并清空表单

### 11.4 与现有 bootstrap 兼容

登录成功后继续触发现有 auth bootstrap，不改：

- refresh cookie 恢复
- 登录后后台 bootstrap
- 受保护路由守卫

## 12. 重置密码后的会话失效策略

重置密码成功后必须让该手机号所有设备下线。

建议执行顺序：

1. 查询该用户所有活跃 `sys_user_session`
2. 批量设置为 `REVOKED`
3. 记录 `revoke_reason = password reset`
4. 删除 Redis 中对应 `auth:session:*`、`auth:refresh:*`、`auth:user:sessions:*`
5. 现有客户端在后续请求中触发 `401` 并跳回登录页

## 13. 第二期 Cloudflare 设计

### 13.1 Turnstile 接入点

建议在以下入口接入 Turnstile：

- `/api/auth/sms/send`
- `/api/auth/login/sms`
- `/api/auth/register`
- `/api/auth/password/reset`

密码登录可首期不强制，视攻击情况再启用。

### 13.2 WAF / Rate Limiting

建议 Cloudflare 对以下路径做独立规则：

- `/api/auth/sms/send`
- `/api/auth/login/password`
- `/api/auth/login/sms`
- `/api/auth/register`
- `/api/auth/password/reset`
- `/api/auth/refresh`

原则：

- Cloudflare 做边缘流量拦截
- 后端 Redis 频控做业务级兜底
- 两层规则不能互相替代

### 13.3 第二期环境变量

- `CLOUDFLARE_TURNSTILE_SITE_KEY`
- `CLOUDFLARE_TURNSTILE_SECRET_KEY`

## 14. 测试策略

### 14.1 后端

至少覆盖：

- 手机号注册成功
- 手机号重复注册
- 密码登录成功 / 失败
- 验证码登录成功 / 失败
- 发码频控命中
- 重置密码成功
- 重置密码后旧 session 全部失效
- refresh 逻辑在新手机号体系下仍可用

### 14.2 前端

至少覆盖：

- 登录页四种状态切换
- 发码倒计时
- 表单校验
- 登录成功后跳转
- 重置密码成功后回到密码登录

### 14.3 联调

首期联调顺序建议：

1. 发码
2. 验码注册
3. 密码登录
4. 验证码登录
5. 重置密码
6. 验证旧设备掉线

## 15. 实施顺序建议

1. 改库与测试 SQL
2. 改后端 DTO / Repository / Auth Service
3. 接入阿里云短信认证 SDK
4. 落地短信风控与审计
5. 改前端登录页四态表单
6. 跑通本地联调
7. 再规划第二期 Cloudflare

## 16. 风险与注意事项

- 阿里云短信认证当前仅支持中国大陆手机号，国家码默认 `86`。
- 若使用阿里云动态验证码模式，服务端不能把验证码内容作为本地真相源。
- 必须使用统一的 `scheme_name`，否则发送和校验可能不匹配。
- 重置密码后全设备下线会影响当前在线用户体验，但这是本次明确要求。
- Cloudflare 第二期接入后，前后端如存在跨域，需额外验证 Turnstile 校验与 Cookie 行为。

## 17. 待你补充的实施前信息

1. 阿里云控制台最终选定的签名名称。
2. 注册 / 登录 / 重置密码使用的模板编码。
3. 生产环境密钥注入方式。
4. Cloudflare 站点与 API 域名拓扑。

## 18. 参考资料

- 阿里云个人开发者短信认证接入：
  - https://help.aliyun.com/zh/pnvs/use-cases/sms-verify-for-individual-developers
- 阿里云短信认证服务说明：
  - https://help.aliyun.com/zh/pnvs/user-guide/sms-authentication-service
- 阿里云号码认证 API 概览：
  - https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-overview
- 阿里云 `CheckSmsVerifyCode`：
  - https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-checksmsverifycode
- Cloudflare Turnstile：
  - https://developers.cloudflare.com/turnstile/turnstile-analytics/token-validation/
- Cloudflare Rate Limiting：
  - https://developers.cloudflare.com/waf/rate-limiting-rules/
- Cloudflare Turnstile + WAF 集成：
  - https://developers.cloudflare.com/turnstile/tutorials/integrating-turnstile-waf-and-bot-management/
