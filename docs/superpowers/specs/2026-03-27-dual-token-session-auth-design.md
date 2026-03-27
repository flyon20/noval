# 双 Token 会话认证改造设计

## 目标

在保持当前项目整体前后端结构稳定的前提下，将现有“单 Access Token + 前端本地持久化”的认证方式升级为：

- Access Token 短时有效
- Refresh Token 7 天滑动有效
- Redis 作为在线会话主状态
- MySQL 持久化会话状态与设备信息
- 支持同一用户最多 3 台设备并行在线
- 第 4 台设备登录时自动踢掉最久未活跃设备
- 登出、封禁、踢设备、改密后可以做到真失效，而不是等待 token 自然过期

## 范围

- 后端认证、刷新、登出链路改造
- 新增用户会话持久化表
- Redis 会话状态设计与按需回灌策略
- 前端登录态恢复、自动刷新、退出登录流程改造
- Cookie/CORS/CSRF 相关配置调整
- 认证与会话相关测试补齐

## 非目标

- 不在本轮引入第三方身份提供方
- 不在本轮实现设备管理界面
- 不在本轮改造为 Spring Security 全量 session 体系
- 不在本轮支持完全不同主域部署
- 不在本轮移除现有 JWT Access Token 机制

## 当前现状

### 后端

- 当前只签发单个 Access Token，默认 7200 秒有效
- `/api/auth/refresh` 当前本质上仍然是“拿旧 access token 换新 access token”
- 后端已经具备 JWT 验签、黑名单、限流、IP 黑名单能力
- 认证仓储层主要基于 `JdbcTemplate`
- 数据表风格统一采用 `sys_*` 命名，并常见：
  - `status`
  - `create_time`
  - `update_time`
  - `deleted`
  - `version`

### 前端

- 当前 Access Token 持久化在 `localStorage`
- 前端请求拦截器会在 401 后尝试刷新 token
- 登录态恢复依赖本地 token 快照，而不是受控的 Refresh Session

### 风险

- 长期 bearer token 暴露给前端 JS，XSS 风险窗口偏大
- refresh 不具备真正的会话轮换语义
- 真注销、踢设备、多端数量上限都无法稳定表达
- Redis 丢失后缺少正式的会话恢复模型

## 总体方案

采用“短 JWT Access Token + Opaque Refresh Token + Redis 在线会话 + MySQL 持久化会话”的双层认证模型。

### 认证凭证分层

- Access Token
  - JWT
  - 建议有效期：15 分钟
  - 用于业务接口鉴权
  - claims 至少包含：
    - `uid`
    - `username`
    - `roles`
    - `sid`
    - `exp`

- Refresh Token
  - 随机 opaque token，不使用 JWT
  - 建议有效期：7 天滑动
  - 仅用于 `/api/auth/refresh`
  - 只通过 `HttpOnly Cookie` 存储，不让前端 JS 读取

### 会话状态分层

- Redis
  - 在线主状态
  - 受保护请求优先读 Redis
  - 保存 session 主体、refresh 映射、用户会话排序索引

- MySQL
  - 会话持久化源
  - 保存 refresh token 哈希、设备信息、状态变更、时间字段
  - Redis miss 时按需回灌

### 多设备策略

- 同一用户最多 3 个 `ACTIVE` 会话
- 第 4 台设备登录时，自动踢掉 `last_active_time` 最早的活跃会话
- 被踢设备后续请求立刻 401

## 数据结构设计

### MySQL：`sys_user_session`

延续现有 `sys_*` 命名和字段风格，新增表：

```sql
CREATE TABLE sys_user_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    status TINYINT DEFAULT 1,
    device_label VARCHAR(100),
    user_agent VARCHAR(255),
    login_ip VARCHAR(50),
    last_active_time TIMESTAMP,
    last_refresh_time TIMESTAMP,
    refresh_expire_time TIMESTAMP NOT NULL,
    revoke_reason VARCHAR(200),
    revoked_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    version INT DEFAULT 0,
    CONSTRAINT uk_user_session_session_id UNIQUE (session_id),
    CONSTRAINT uk_user_session_refresh_hash UNIQUE (refresh_token_hash)
);

CREATE INDEX idx_user_session_user_status
    ON sys_user_session(user_id, status, deleted);

CREATE INDEX idx_user_session_user_active_time
    ON sys_user_session(user_id, last_active_time);
```

### 字段语义

- `session_id`
  - 登录时生成的全局唯一会话标识
  - 写入 Access Token 的 `sid`

- `refresh_token_hash`
  - 当前 Refresh Token 的哈希值
  - 不保存明文 refresh token

- `status`
  - `1 = ACTIVE`
  - `2 = REVOKED`
  - `3 = EXPIRED`
  - `4 = KICKED`

- `device_label`
  - 前端传入的设备标签，如 `Chrome on Windows`

- `last_active_time`
  - 最近业务请求活跃时间

- `last_refresh_time`
  - 最近 refresh 成功时间

- `refresh_expire_time`
  - Refresh Token 的绝对过期时间
  - 滑动 7 天语义通过 refresh 成功后向后续期实现

### Redis Key 设计

- `auth:session:{sessionId}`
  - 保存：
    - `userId`
    - `status`
    - `deviceLabel`
    - `lastActiveTime`
    - `refreshExpireTime`

- `auth:refresh:{refreshTokenHash}`
  - 值：`sessionId`
  - 用于 refresh 快速定位 session

- `auth:user:sessions:{userId}`
  - 类型：`zset`
  - score：`lastActiveTime`
  - member：`sessionId`
  - 用于控制最大在线设备数

- `auth:session:dirty`
  - 类型：`set`
  - member：`sessionId`
  - 用于批量回写 MySQL 的脏会话

## Cookie 与同站部署策略

当前确认部署模式为同站跨子域，例如：

- 前端：`app.xxx.com`
- 后端：`api.xxx.com`

Refresh Token Cookie 设计如下：

- 名称：`refresh_token`
- `HttpOnly = true`
- `Secure = true`
- `SameSite = Strict`
- `Path = /api/auth`
- host-only cookie，绑定 `api.xxx.com`

不建议：

- 绑定整站主域
- 允许前端 JS 读取
- 将 refresh token 放入 `localStorage`

## 后端时序设计

### 1. 登录

1. 校验用户名密码
2. 查询该用户当前 `ACTIVE` 会话
3. 如果活跃会话数 `< 3`，直接创建新会话
4. 如果活跃会话数 `>= 3`
   - 找 `last_active_time` 最早的会话
   - MySQL 标记该会话为 `KICKED`
   - Redis 删除旧会话相关 key
5. 生成：
   - `sessionId`
   - 明文 `refresh token`
   - `refresh token hash`
6. MySQL 插入 `sys_user_session`
7. Redis 写入在线会话与用户索引
8. 签发 15 分钟 `access token`
9. 下发 7 天 `refresh_token` cookie

### 2. 受保护请求

1. 读取 `Authorization: Bearer <access token>`
2. 验 JWT 签名与过期
3. 从 claims 读取 `sid`
4. 查 Redis `auth:session:{sid}`
5. 命中且状态有效则放行，并更新活跃时间
6. Redis miss 时回查 MySQL：
   - 若 session 仍为 `ACTIVE` 且 refresh 未过期
   - 回灌 Redis 后放行
   - 否则返回 401

### 3. Refresh

1. 浏览器自动带 `refresh_token` cookie
2. 后端计算 `refresh_token_hash`
3. 先查 Redis `auth:refresh:{hash}` 定位 `sessionId`
4. Redis miss 时查 MySQL
5. 校验：
   - session 为 `ACTIVE`
   - `refresh_token_hash` 匹配
   - `refresh_expire_time > now`
6. 成功后轮换：
   - 新 refresh token
   - 新 hash
   - MySQL 更新 hash、活跃时间、刷新时间、过期时间
   - Redis 删除旧 hash 映射，写入新映射
   - 更新 session 热状态和用户 zset
7. 返回新 Access Token，并重写 refresh cookie

### 4. Logout

1. 从 Access Token 中读取 `sid`
2. MySQL 将对应 session 标记为 `REVOKED`
3. Redis 删除该 session 的全部在线状态
4. 清理 refresh cookie

### 5. 第 4 台设备登录

1. 登录前统计该用户 `ACTIVE` session
2. 如果达到 3 台
3. 踢掉 `last_active_time` 最早的一条
4. 旧设备请求因 Redis/MySQL 状态非 `ACTIVE` 而立刻失效

## Redis 与 MySQL 同步策略

### 写路径

- 登录：先写 MySQL，再写 Redis
- Refresh：先写 MySQL，再写 Redis
- Logout：先写 MySQL，再删 Redis
- Kick：先写 MySQL，再删 Redis

### 读路径

- 先 Redis
- Redis miss 再 MySQL
- MySQL 命中后回灌 Redis

### 活跃时间回写

- 业务请求不直接每次写 MySQL
- 业务请求只更新 Redis，并把 `sessionId` 放入 `auth:session:dirty`
- 定时任务每分钟批量回写：
  - `last_active_time`
  - 必要状态字段

### Redis 丢失恢复

不做启动时全量回灌，而是采用按需恢复：

- 业务请求时按 `sid` 回查 MySQL
- refresh 时按 `refresh_token_hash` 回查 MySQL

优点：

- 启动负担小
- 只恢复真实活跃用户

## 前端改造设计

### 存储策略

- Access Token：以内存为主
- Refresh Token：仅依赖 `HttpOnly Cookie`
- 不再把长期登录凭证放 `localStorage`

### 启动恢复

1. App 启动
2. 若内存中无 access token，调用 `/api/auth/refresh`
3. 浏览器自动带 refresh cookie
4. 成功则恢复 access token 与用户会话
5. 失败则保持未登录态

### 请求拦截

- 普通业务请求 401 后，自动尝试一次 refresh
- refresh 成功后重放原请求
- refresh 失败则清空登录态并跳转登录页

### 登录

- `POST /api/auth/login`
- 响应体只接收：
  - `accessToken`
  - `tokenType`
  - `expiresIn`
- Cookie 由浏览器自动存储

### Refresh

- `POST /api/auth/refresh`
- 不再在 body 中传 token
- 通过 `withCredentials` 自动带 cookie

### Logout

- `POST /api/auth/logout`
- 带当前 access token
- 不再显式把 refresh token 或 access token 再放到 body 里

## 接口契约调整

### `POST /api/auth/login`

请求：

```json
{
  "username": "admin",
  "password": "admin123",
  "deviceLabel": "Chrome on Windows"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "jwt",
    "tokenType": "Bearer",
    "expiresIn": 900
  }
}
```

### `POST /api/auth/refresh`

- 请求体为空
- 必须由浏览器自动带 `refresh_token` cookie

### `POST /api/auth/logout`

- 依赖当前 `Authorization` header 中的 Access Token
- 用于定位当前 `sid`

## 实现边界与兼容策略

### 第一阶段：后端兼容落地

- 新增 `sys_user_session`
- 后端支持双 Token
- 登录开始签发 refresh cookie
- refresh 支持基于 cookie 刷新
- 现有 access token 鉴权逐步接入 `sid + Redis/MySQL session`

### 第二阶段：前端切换

- 前端改为：
  - 内存 access token
  - 启动静默 refresh
  - 401 自动单次 refresh
- 稳定后移除旧的本地长期 token 恢复逻辑

## 测试策略

### 后端

- 登录返回 access token 并设置 refresh cookie
- refresh 成功并轮换 refresh token
- 旧 refresh token 重放失败
- logout 后当前会话立即失效
- 第 4 台设备登录时最久未活跃设备被踢
- Redis miss 时可从 MySQL 回灌恢复 session
- 被踢设备或已撤销设备继续请求返回 401

### 前端

- 启动时静默 refresh 恢复登录态
- 401 自动 refresh 后重放请求
- refresh 失败后清空登录态
- logout 后不再能静默恢复

## 风险与权衡

### 1. 引入状态后放弃了 JWT 的纯无状态优势

接受这一点，因为本项目明确需要：

- 真注销
- 设备数控制
- 踢设备
- 持久化会话恢复

### 2. MySQL 持久化与 Redis 热状态之间的一致性

通过以下方式降低风险：

- 核心状态变更先写 MySQL
- Redis 作为热缓存和在线状态
- 业务活跃时间使用 dirty set 批量回写

### 3. 前端从 localStorage 迁移到内存会导致“页面刷新后必须静默 refresh”

这是可接受的，因为：

- 用户无感
- 安全收益明显更高

## 推荐实现顺序

1. 新增 `sys_user_session` 表与迁移 SQL
2. 新建会话 repository/service
3. 改造 login
4. 改造 refresh
5. 改造 Access Token 鉴权过滤器
6. 改造 logout 与踢设备逻辑
7. 加入 dirty session 定时回写
8. 改造前端认证恢复与请求拦截
9. 补齐联调与回归测试

## 成功标准

- 用户在 7 天滑动窗口内无需重新登录
- 同一用户最多 3 台设备同时在线
- 第 4 台登录自动踢掉最久未活跃设备
- logout、kick、封禁后当前会话立即失效
- Redis 重启后活跃用户可按需恢复，不出现大面积被迫重新登录
- 前端不再将长期会话凭证暴露给 JS
