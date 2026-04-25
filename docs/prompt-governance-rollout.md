# Prompt Governance Rollout

## 文档目的

这份文档用于沉淀本轮提示词治理改造的实际落地内容，包含：

- 当前代码改动点
- 数据库落库脚本与执行顺序
- 服务上线操作
- 上线后校验 SQL
- 回滚和注意事项

适用场景：

- 在服务器上把本轮提示词治理改造正式上线
- 校验管理员全局模板、用户绑定模板、运行时模板选择是否符合预期
- 处理线上数据库仍然保留老默认模板名时的兼容问题

## 本轮改动概览

### 1. 默认模板命名兼容

本轮重点解决了老模板命名与新治理模型之间的兼容问题。

当前代码已经同时兼容以下默认模板名：

- `default`
- `default-deconstruct`
- `default-structure`
- `default-plot`
- `default-theme`

兼容效果：

- legacy `/api/config/prompt` 在读取默认模板时，会自动命中当前类型的默认模板
- legacy `/api/config/prompt` 在更新默认模板时，不会再新建一条运行时不会被使用的记录
- 系统治理服务在读取系统默认模板和做兜底回退时，也能识别老默认模板名

涉及主要代码：

- `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java`
- `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptGovernanceService.java`
- `backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java`

### 2. 运行时提示词解析修复

分析运行时现在统一通过治理服务解析有效模板，并补齐了运行时元数据兼容：

- `selectedModelKey` 无用户偏好时不再是 `null`
- 会回退到系统默认模型，例如 `deepseek-chat`
- `promptRuntime.effectiveSource` 对老测试和老结果兼容为 `SYSTEM_DEFAULT`

涉及主要代码：

- `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`

### 3. 结构化输出支持扩展

过去只有 `theme` 会触发 JSON 结构化输出提示。

现在以下类型也支持结构化输出：

- `deconstruct`
- `structure`
- `plot`
- `theme`

触发条件：

- 配置了 `outputJsonSchema`
- 或配置了 `inputJsonSchema`
- 或 `postProcessType = json_extract`
- 或 `parseConfigJson` 中配置了 JSON parser

效果：

- 会附加 `response_format`
- 会在 system prompt 中附加 `input schema`
- 会在 system prompt 中附加 `output schema`
- 会附加 `Please output valid JSON only.`

涉及主要代码：

- `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java`
- `backend/src/main/java/com/novelanalyzer/modules/config/service/DefaultPromptContractCatalog.java`

### 4. 数据模型治理主链

本轮改造已引入或补齐以下治理相关结构：

- `prompt_publish_version`
- `prompt_publish_item`
- `user_prompt_binding`
- `user_prompt_effective_history`

对应语义：

- 管理员保存草稿
- 管理员显式发布
- 用户绑定全局模板或个人副本
- 记录用户实际生效模板历史

## 已完成验证

本地已明确通过的验证：

- `mvn "-Dtest=PromptConfigServiceTest,AiGatewayServiceTest" test`
- `mvn "-Dtest=Phase4AnalysisIntegrationTest" test`
- `npm run type-check`
- `npx vitest run src/views/config/prompt/__tests__/PromptConfigView.spec.ts --pool=threads --maxWorkers=1 --no-file-parallelism`

说明：

- 当前本机存在明显内存限制
- 部分 Maven fork JVM 与 Vitest worker 在额外验证时会因为环境内存不足退出
- 这类失败不对应当前业务逻辑失败
- 核心后端业务集成测试 `Phase4AnalysisIntegrationTest` 已通过

## 上线前准备

### 1. 确认代码目录

假设服务器项目目录为：

```bash
/opt/noval
```

### 2. 确认关键文件存在

至少确认以下文件已经同步到服务器：

```bash
ls /opt/noval/backend/sql/mysql/phase5-schema.sql
ls /opt/noval/backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java
ls /opt/noval/backend/src/main/java/com/novelanalyzer/modules/config/service/PromptGovernanceService.java
ls /opt/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java
ls /opt/noval/backend/src/main/java/com/novelanalyzer/modules/analysis/service/AiGatewayService.java
```

### 3. 建议先备份数据库

```bash
mkdir -p /opt/noval/db-backup
mysqldump -h127.0.0.1 -uroot -p novel_analyzer > /opt/noval/db-backup/novel_analyzer-$(date +%F-%H%M%S).sql
```

如果 MySQL 在容器内：

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc '
mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer
' > /opt/noval/db-backup/novel_analyzer-$(date +%F-%H%M%S).sql
```

## 数据库落库操作

### 方案 A：从 MySQL 容器内执行

如果 `backend/sql/mysql/phase5-schema.sql` 已映射进容器：

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc '
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer < /app/backend/sql/mysql/phase5-schema.sql
'
```

### 方案 B：从宿主机直接执行

如果 SQL 文件就在宿主机：

```bash
mysql -h127.0.0.1 -uroot -p novel_analyzer < /opt/noval/backend/sql/mysql/phase5-schema.sql
```

### 如需初始化或补种子

如果你要补开发种子数据，可以执行：

```bash
mysql -h127.0.0.1 -uroot -p novel_analyzer < /opt/noval/backend/sql/mysql/phase5-seed.sql
```

注意：

- 生产环境通常不建议直接执行 `phase5-seed.sql`
- 除非你明确需要补默认治理版本和演示数据

## 服务上线操作

### 1. 重建后端镜像

```bash
cd /opt/noval
docker compose -f /opt/noval/docker-compose.yml build backend
```

### 2. 重启后端服务

```bash
docker compose -f /opt/noval/docker-compose.yml up -d backend
```

### 3. 查看后端日志

```bash
docker compose -f /opt/noval/docker-compose.yml logs --tail=200 backend
```

### 4. 如果前端也要一起发

```bash
cd /opt/noval
docker compose -f /opt/noval/docker-compose.yml build frontend
docker compose -f /opt/noval/docker-compose.yml up -d frontend
docker compose -f /opt/noval/docker-compose.yml logs --tail=200 frontend
```

## 上线后校验

### 1. 校验治理表是否存在

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SHOW TABLES LIKE '\''prompt_publish_version'\'';
SHOW TABLES LIKE '\''prompt_publish_item'\'';
SHOW TABLES LIKE '\''user_prompt_binding'\'';
SHOW TABLES LIKE '\''user_prompt_effective_history'\'';
"'
```

### 2. 校验当前提示词模板表

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT id, prompt_type, prompt_name, scope_type, owner_user_id, source_prompt_config_id, is_default, status, deleted
FROM prompt_config
WHERE deleted = 0
ORDER BY prompt_type, is_default DESC, id ASC;
"'
```

你重点看：

- `prompt_name` 是否还是老默认名，例如 `default-deconstruct`
- `scope_type` 是否正确区分了 `SYSTEM` / `USER_COPY`
- `is_default` 是否只保留真正的系统兜底默认模板

### 3. 校验当前全局发布版本

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT id, version_no, published_by, publish_note, create_time
FROM prompt_publish_version
WHERE deleted = 0
ORDER BY version_no DESC;

SELECT publish_version_id, prompt_type, prompt_config_id, prompt_name
FROM prompt_publish_item
WHERE deleted = 0
ORDER BY publish_version_id DESC, prompt_type ASC;
"'
```

### 4. 校验用户模型偏好

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT user_id, config_key, config_value, updated_at
FROM user_config
WHERE config_key = '\''ai.preferred-model'\''
ORDER BY updated_at DESC, user_id ASC;
"'
```

### 5. 校验系统模型注册表

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT JSON_PRETTY(config_value)
FROM system_config
WHERE config_key = '\''ai.model-registry.json'\''
  AND deleted = 0\\G
"'
```

重点看：

- `defaultModelKey`
- 各模型的 `modelKey`
- 各模型 `enabled`

### 6. 校验某个用户当前绑定模板

把下面 SQL 里的 `5` 改成真实用户 id：

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT user_id, prompt_type, binding_mode, bound_prompt_config_id, last_selected_prompt_config_id, effective_prompt_config_id, publish_version_id, fallback_warning, update_time
FROM user_prompt_binding
WHERE deleted = 0
  AND user_id = 5
ORDER BY prompt_type;
"'
```

### 7. 校验某个用户实际生效模板历史

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT id, user_id, prompt_type, publish_version_id, binding_mode, bound_prompt_config_id, effective_prompt_config_id, effective_source, previous_effective_prompt_config_id, selected_model_key, fallback, create_time
FROM user_prompt_effective_history
WHERE deleted = 0
  AND user_id = 5
ORDER BY id DESC
LIMIT 50;
"'
```

### 8. 快速判断某用户当前分析实际会用哪个模板

```bash
docker compose -f /opt/noval/docker-compose.yml exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer -e "
SELECT
  b.user_id,
  b.prompt_type,
  b.binding_mode,
  b.bound_prompt_config_id,
  b.effective_prompt_config_id,
  pc.prompt_name,
  pc.model_name,
  pc.scope_type,
  b.publish_version_id,
  b.fallback_warning
FROM user_prompt_binding b
LEFT JOIN prompt_config pc ON pc.id = b.effective_prompt_config_id
WHERE b.deleted = 0
  AND pc.deleted = 0
  AND b.user_id = 5
ORDER BY b.prompt_type;
"'
```

## 推荐上线后人工验证

### 管理员侧

验证项：

- 进入提示词配置页后能看到当前系统模板
- 修改默认模板内容后保存成功
- 再次读取时内容一致
- 发布新版本后，`prompt_publish_version` 和 `prompt_publish_item` 有新增记录

### 用户侧

验证项：

- 用户只能看到全局已发布模板和自己的副本
- 用户不能修改 JSON 合同字段
- 用户不能删除系统默认模板
- 用户绑定自己的副本后，`user_prompt_binding` 正确更新
- 当副本无效后，能够回退到全局模板

### 分析运行时

验证项：

- 发起 `deconstruct` 分析时，结果中的 `promptRuntime.selectedModelKey` 不再是 `null`
- 结果中的 `promptRuntime.effectiveSource` 正常
- `deconstruct/structure/plot` 配置结构化输出后，请求体里应带 `response_format`

## 回滚建议

如果上线后发现问题：

### 1. 回滚服务代码

回滚到上线前镜像或上线前提交。

### 2. 数据库回滚

优先使用上线前备份：

```bash
mysql -h127.0.0.1 -uroot -p novel_analyzer < /opt/noval/db-backup/xxx.sql
```

如果只想停用新逻辑而不整体回滚数据库：

- 可先不删除治理表
- 先回滚后端代码
- 让系统继续按旧逻辑运行

## 注意事项

### 1. 当前线上库很可能仍保留老默认模板名

例如：

- `default-deconstruct`
- `default-structure`
- `default-plot`
- `default-theme`

本轮代码已经兼容这些名字，所以不要求你先手工改名。

### 2. 不建议手工把所有 `is_default` 都设为 1

新治理下：

- `is_default` 不再代表“当前生效模板”
- 真正当前生效的是：
  - 管理员发布版本
  - 用户绑定记录
  - 运行时治理解析结果

### 3. 判断当前模型使用模板时，不要只查 `prompt_config.is_default`

正确做法是综合看：

- `user_config.ai.preferred-model`
- `system_config.ai.model-registry.json`
- `prompt_publish_version`
- `prompt_publish_item`
- `user_prompt_binding`
- `prompt_config`

## 建议后续补充

建议后面再补两份文档：

- 单独的“生产库一次性迁移脚本”
- 单独的“用户当前实际使用模板判定手册”

这样后续排查线上用户问题会更快。
