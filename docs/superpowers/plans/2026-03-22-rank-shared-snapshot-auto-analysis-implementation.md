# Shared Rank Snapshot And Auto Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前写死 `category` 的扫榜链路升级为共享的 `channel + board` 榜单快照、数据库分页、登录自动补抓、自动榜单分析、管理员可配缓存与次数限制的完整联调实现。

**Architecture:** Python crawler 负责发现番茄真实频道/榜单目录，并按选中的 `channelCode + boardCode` 一次抓完整榜单。Spring Boot 负责榜单目录落库、快照持久化、共享缓存复用、登录补抓、榜单分析与日志埋点，再向前端提供目录、分页、分析和配置接口。Vue 前端只负责选择榜单、请求分页、渲染当前快照及其分析结果，不再自己维护硬编码分类和抓取逻辑。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + MySQL/H2, Vue 3 + Element Plus + Vitest, FastAPI + Pydantic crawler

---

## File Structure

**SQL and seeds**
- Modify: `backend/sql/mysql/phase5-schema.sql` - 新增 `rank_board`、`rank_snapshot`，扩展 `crawl_rank`、`analysis_result`、`prompt_config`
- Modify: `backend/sql/mysql/phase5-seed.sql` - 补齐系统配置 key、榜单分析 prompt JSON 契约示例
- Modify: `backend/src/test/resources/sql/phase5-schema-h2.sql` - 与 MySQL 结构保持一致
- Modify: `backend/src/test/resources/sql/phase5-data-h2.sql` - 提供榜单目录、快照、榜单分析、配置测试数据

**Backend crawler domain**
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/model/RankBoardEntity.java` - 榜单目录实体
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/model/RankSnapshotEntity.java` - 榜单快照头实体
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/model/CrawlRankEntity.java` - 绑定 `snapshotId/channelCode/boardCode`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/mapper/RankBoardMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/mapper/RankSnapshotMapper.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/repository/CrawlerRepository.java` - 目录、快照、分页、频控查询
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/dto/CrawlerRankRequest.java` - 从 `category` 迁移为 `channelCode + boardCode`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankBoardCatalogVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankBoardOptionVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankRefreshResultVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankPageVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerRefreshPolicyService.java` - 榜单/图书缓存和次数限制全部改为系统配置读取
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerService.java` - 目录同步、完整榜单抓取、快照分页、日志、自动分析触发
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/controller/CrawlerController.java` - 新增 `/boards`、`/rank/refresh`、`/rank/page`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/client/PythonCrawlerClient.java` - 对接 crawler 新目录接口和新抓榜协议
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/client/model/ExternalRankBoard.java`

**Backend analysis and visual**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/model/AnalysisResultEntity.java` - 增加 `scopeType/snapshotId/channelCode/boardCode/triggerType/reanalysisSeq`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/repository/AnalysisRepository.java` - 榜单分析查询、最新有效结果、人工重分析次数统计
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java` - 榜单分析输入、缓存过期判定、JSON 校验兜底
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/controller/AnalysisController.java` - 新增 `/rank/latest`、`/rank/reanalyze`
- Create: `backend/src/main/java/com/novelanalyzer/modules/analysis/dto/RankReanalyzeRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/analysis/vo/RankAnalysisVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/controller/DataController.java` - 新增 `/visual/rank`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/service/DataQueryService.java` - 只按当前榜单快照返回词云/题材表/摘要
- Create: `backend/src/main/java/com/novelanalyzer/modules/data/vo/RankVisualDataVO.java`

**Backend config and bootstrap**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptConfigEntity.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptConfigUpdateRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptConfigVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java` - 支持 `outputSchema/outputExample/postProcessType`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/system/controller/SystemController.java` - 新增登录补抓接口
- Create: `backend/src/main/java/com/novelanalyzer/modules/system/service/LoginBootstrapService.java`

**Crawler service**
- Modify: `crawler/app/models/rank.py` - 新目录请求/响应模型
- Modify: `crawler/app/api/rank.py` - 新增目录接口，并把抓榜请求改为 `channelCode + boardCode`
- Modify: `crawler/app/services/fanqie_crawler.py` - 解析真实频道/榜单目录，按榜单抓完整排名
- Modify: `crawler/tests/test_fanqie_crawler.py` - 目录发现与完整榜单抓取回归

**Frontend**
- Modify: `frontend/src/views/login/LoginView.vue` - 精简展示，并在登录后触发 bootstrap
- Modify: `frontend/src/views/rank/RankView.vue` - 榜单目录切换、分页、分析状态、重新抓取/重新分析
- Modify: `frontend/src/views/config/system/SystemConfigView.vue` - 展示新增缓存/次数限制配置
- Modify: `frontend/src/views/config/prompt/PromptConfigView.vue` - 支持榜单分析 JSON 契约配置
- Modify: `frontend/src/api/crawler.ts`
- Modify: `frontend/src/api/analysis.ts`
- Modify: `frontend/src/api/data.ts`
- Create: `frontend/src/api/system.ts`
- Modify: `frontend/src/types/crawler.ts`
- Modify: `frontend/src/types/data.ts`
- Modify: `frontend/src/types/config.ts`
- Create: `frontend/src/types/system.ts`
- Modify: `frontend/src/views/login/__tests__/LoginView.spec.ts`
- Modify: `frontend/src/views/rank/__tests__/RankView.spec.ts`
- Modify: `frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts`
- Modify: `frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts`

**Integration tests**
- Modify: `backend/src/test/java/com/novelanalyzer/modules/crawler/CrawlerPhase3IntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java`
- Modify: `backend/src/test/java/com/novelanalyzer/modules/system/controller/SystemControllerTest.java`

### Task 1: Shared Rank Schema And Config Foundation

**Files:**
- Modify: `backend/sql/mysql/phase5-schema.sql`
- Modify: `backend/sql/mysql/phase5-seed.sql`
- Modify: `backend/src/test/resources/sql/phase5-schema-h2.sql`
- Modify: `backend/src/test/resources/sql/phase5-data-h2.sql`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/model/RankBoardEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/model/RankSnapshotEntity.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/model/CrawlRankEntity.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/mapper/RankBoardMapper.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/mapper/RankSnapshotMapper.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/model/AnalysisResultEntity.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/model/PromptConfigEntity.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptConfigUpdateRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptConfigVO.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldExposeBoardScopedVisualSeedData() throws Exception {
    String token = loginAndGetToken("admin", "admin123");

    mockMvc.perform(get("/api/data/visual/rank")
            .header("Authorization", "Bearer " + token)
            .param("platform", "fanqie")
            .param("channelCode", "male-new")
            .param("boardCode", "urban-brain"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.snapshotId").isNumber())
        .andExpect(jsonPath("$.data.wordCloud.length()").value(2));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=Phase5BackendIntegrationTest test`

Expected: FAIL，错误集中在缺少 `rank_board`/`rank_snapshot` 结构、`analysis_result` 新字段或 `/api/data/visual/rank` 数据准备不足。

- [ ] **Step 3: Write minimal implementation**

```sql
CREATE TABLE rank_board (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  platform VARCHAR(32) NOT NULL,
  channel_code VARCHAR(64) NOT NULL,
  channel_name VARCHAR(64) NOT NULL,
  board_code VARCHAR(64) NOT NULL,
  board_name VARCHAR(64) NOT NULL,
  source_path VARCHAR(255),
  enabled TINYINT DEFAULT 1,
  sort_order INT DEFAULT 0,
  last_sync_time DATETIME,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_rank_board(platform, channel_code, board_code)
);

ALTER TABLE crawl_rank
  ADD COLUMN snapshot_id BIGINT NOT NULL,
  ADD COLUMN channel_code VARCHAR(64) NOT NULL,
  ADD COLUMN board_code VARCHAR(64) NOT NULL;
```

```java
@TableName("rank_snapshot")
public class RankSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("platform")
    private String platform;
    @TableField("channel_code")
    private String channelCode;
    @TableField("board_code")
    private String boardCode;
    @TableField("snapshot_time")
    private LocalDateTime snapshotTime;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=Phase5BackendIntegrationTest test`

Expected: PASS，至少新的 schema/seed 能支撑后续接口测试启动。

- [ ] **Step 5: Commit**

```bash
git add backend/sql/mysql/phase5-schema.sql backend/sql/mysql/phase5-seed.sql backend/src/test/resources/sql/phase5-schema-h2.sql backend/src/test/resources/sql/phase5-data-h2.sql backend/src/main/java/com/novelanalyzer/modules/crawler/model/RankBoardEntity.java backend/src/main/java/com/novelanalyzer/modules/crawler/model/RankSnapshotEntity.java backend/src/main/java/com/novelanalyzer/modules/crawler/model/CrawlRankEntity.java backend/src/main/java/com/novelanalyzer/modules/crawler/mapper/RankBoardMapper.java backend/src/main/java/com/novelanalyzer/modules/crawler/mapper/RankSnapshotMapper.java backend/src/main/java/com/novelanalyzer/modules/analysis/model/AnalysisResultEntity.java backend/src/main/java/com/novelanalyzer/modules/config/model/PromptConfigEntity.java backend/src/main/java/com/novelanalyzer/modules/config/dto/PromptConfigUpdateRequest.java backend/src/main/java/com/novelanalyzer/modules/config/vo/PromptConfigVO.java backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java
git commit -m "feat: add shared rank snapshot schema"
```

### Task 2: Python Crawler Board Catalog And Full-Board Fetch

**Files:**
- Modify: `crawler/app/models/rank.py`
- Modify: `crawler/app/api/rank.py`
- Modify: `crawler/app/services/fanqie_crawler.py`
- Test: `crawler/tests/test_fanqie_crawler.py`

- [ ] **Step 1: Write the failing test**

```python
def test_fetch_rank_catalog_returns_channels_and_boards():
    crawler = FanqieCrawler(http_client=FakeHttpClient([catalog_html]))

    result = crawler.fetch_rank_catalog()

    assert result[0]["channelCode"] == "male-new"
    assert result[0]["boards"][0]["boardCode"] == "urban-brain"


def test_fetch_rank_uses_channel_and_board():
    crawler = FanqieCrawler(http_client=FakeHttpClient([rank_html]))

    items = crawler.fetch_rank("male-new", "urban-brain")

    assert items[0].rankNo == 1
    assert items[0].platformBookId == "7312456"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest tests/test_fanqie_crawler.py -q`

Expected: FAIL，因为当前只有 `fetch_rank(category)`，不存在目录发现与 `channelCode + boardCode` 协议。

- [ ] **Step 3: Write minimal implementation**

```python
class RankBoardItem(BaseModel):
    channelCode: str
    channelName: str
    boardCode: str
    boardName: str
    sourcePath: str


class RankRequest(BaseModel):
    platform: str
    channelCode: str
    boardCode: str
```

```python
@router.get("/rank/boards", response_model=ApiResult)
def boards(platform: str):
    crawler = build_crawler(platform)
    return ApiResult(code=200, message="success", data=crawler.fetch_rank_catalog())


@router.post("/rank", response_model=ApiResult)
def rank(req: RankRequest):
    crawler = build_crawler(req.platform)
    return ApiResult(code=200, message="success", data=crawler.fetch_rank(req.channelCode, req.boardCode))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/test_fanqie_crawler.py -q`

Expected: PASS，目录发现和完整榜单抓取都能返回稳定结构。

- [ ] **Step 5: Commit**

```bash
git add crawler/app/models/rank.py crawler/app/api/rank.py crawler/app/services/fanqie_crawler.py crawler/tests/test_fanqie_crawler.py
git commit -m "feat: support fanqie board catalog crawling"
```

### Task 3: Backend Rank Catalog, Snapshot Paging, Shared Reuse, And Login Bootstrap

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/client/PythonCrawlerClient.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/client/model/ExternalRankBoard.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/dto/CrawlerRankRequest.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/repository/CrawlerRepository.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerRefreshPolicyService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/crawler/controller/CrawlerController.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankBoardCatalogVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankBoardOptionVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankRefreshResultVO.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankPageVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/system/controller/SystemController.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/system/service/LoginBootstrapService.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/crawler/CrawlerPhase3IntegrationTest.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/system/controller/SystemControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldRefreshWholeBoardAndPageSnapshotWithoutRecrawling() throws Exception {
    String token = loginAndGetToken("admin", "admin123");

    mockMvc.perform(post("/api/crawler/rank/refresh")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.snapshotId").isNumber())
        .andExpect(jsonPath("$.data.analysisTriggered").value(true));

    mockMvc.perform(get("/api/crawler/rank/page")
            .header("Authorization", "Bearer " + token)
            .param("platform", "fanqie")
            .param("channelCode", "male-new")
            .param("boardCode", "urban-brain")
            .param("page", "2")
            .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(2));
}
```

```java
@Test
void shouldBootstrapEnabledBoardsOnLogin() throws Exception {
    String token = loginAndGetToken("admin", "admin123");

    mockMvc.perform(post("/api/system/login-bootstrap")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].boardCode").isNotEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=CrawlerPhase3IntegrationTest,SystemControllerTest test`

Expected: FAIL，因为当前没有榜单目录接口、快照分页接口、登录 bootstrap 接口，`CrawlerRankRequest` 仍然只接受 `category`。

- [ ] **Step 3: Write minimal implementation**

```java
public Result<List<RankBoardCatalogVO>> boards(@RequestParam("platform") String platform) {
    return Result.success(crawlerService.getBoardCatalog(platform));
}

public Result<RankRefreshResultVO> refresh(@Valid @RequestBody CrawlerRankRequest request) {
    return Result.success(crawlerService.refreshRankBoard(request));
}

public Result<RankPageVO> page(@RequestParam String platform,
                               @RequestParam String channelCode,
                               @RequestParam String boardCode,
                               @RequestParam Integer page,
                               @RequestParam Integer pageSize) {
    return Result.success(crawlerService.getRankPage(platform, channelCode, boardCode, page, pageSize));
}
```

```java
log.info("rank.refresh decision traceId={} platform={} channelCode={} boardCode={} reused={} limited={}",
    TraceIdHolder.get(), platform, channelCode, boardCode, reused, refreshLimited);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=CrawlerPhase3IntegrationTest,SystemControllerTest test`

Expected: PASS，能覆盖目录同步、完整榜单入库、数据库分页、切换榜单独立频控、登录自动补抓与日志写入。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/crawler/client/PythonCrawlerClient.java backend/src/main/java/com/novelanalyzer/modules/crawler/client/model/ExternalRankBoard.java backend/src/main/java/com/novelanalyzer/modules/crawler/dto/CrawlerRankRequest.java backend/src/main/java/com/novelanalyzer/modules/crawler/repository/CrawlerRepository.java backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerRefreshPolicyService.java backend/src/main/java/com/novelanalyzer/modules/crawler/service/CrawlerService.java backend/src/main/java/com/novelanalyzer/modules/crawler/controller/CrawlerController.java backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankBoardCatalogVO.java backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankBoardOptionVO.java backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankRefreshResultVO.java backend/src/main/java/com/novelanalyzer/modules/crawler/vo/RankPageVO.java backend/src/main/java/com/novelanalyzer/modules/system/controller/SystemController.java backend/src/main/java/com/novelanalyzer/modules/system/service/LoginBootstrapService.java backend/src/test/java/com/novelanalyzer/modules/crawler/CrawlerPhase3IntegrationTest.java backend/src/test/java/com/novelanalyzer/modules/system/controller/SystemControllerTest.java
git commit -m "feat: add shared rank snapshot refresh workflow"
```

### Task 4: Rank Analysis Cache, Manual Reanalysis Limit, Prompt JSON Contract, And Rank Visual API

**Files:**
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/repository/AnalysisRepository.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/analysis/controller/AnalysisController.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/analysis/dto/RankReanalyzeRequest.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/analysis/vo/RankAnalysisVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/controller/DataController.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/data/service/DataQueryService.java`
- Create: `backend/src/main/java/com/novelanalyzer/modules/data/vo/RankVisualDataVO.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java`
- Modify: `backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java`
- Test: `backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldReturnCachedRankAnalysisWithinReuseWindow() throws Exception {
    String token = loginAndGetToken("admin", "admin123");

    mockMvc.perform(get("/api/analysis/rank/latest")
            .header("Authorization", "Bearer " + token)
            .param("platform", "fanqie")
            .param("channelCode", "male-new")
            .param("boardCode", "urban-brain"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.cached").value(true))
        .andExpect(jsonPath("$.data.manualReanalyzeRemaining").value(3));
}

@Test
void shouldReturnBoardScopedVisualData() throws Exception {
    String token = loginAndGetToken("admin", "admin123");

    mockMvc.perform(get("/api/data/visual/rank")
            .header("Authorization", "Bearer " + token)
            .param("platform", "fanqie")
            .param("channelCode", "male-new")
            .param("boardCode", "urban-brain"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.themeTable[0].theme").isNotEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=Phase5BackendIntegrationTest test`

Expected: FAIL，因为当前只有平台级 `/api/analysis/trend` 与 `/api/data/visual`，没有快照级缓存和人工重分析限制。

- [ ] **Step 3: Write minimal implementation**

```java
public RankAnalysisVO getLatestRankAnalysis(String platform, String channelCode, String boardCode, Long snapshotId) {
    RankSnapshotEntity snapshot = crawlerRepository.findLatestSnapshot(platform, channelCode, boardCode, snapshotId);
    AnalysisResultEntity cached = analysisRepository.findLatestValidRankAnalysis(snapshot.getId(), reuseCutoff);
    if (cached != null) {
        return RankAnalysisVO.from(cached, true, remainingManualTimes(snapshot.getId()));
    }
    return analyzeRankSnapshot(snapshot, "AUTO");
}
```

```java
String inputText = snapshotItems.stream()
    .map(item -> "#" + item.getRankNo() + " " + item.getBookName() + " / " + item.getAuthor() + " / " + item.getIntro())
    .collect(Collectors.joining("\n"));
Map<String, Object> resultJson = validateAgainstPromptSchema(aiResult.getResultJson(), promptConfig);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=Phase5BackendIntegrationTest test`

Expected: PASS，切换榜单时能命中当前快照的缓存分析；过期时自动重算；人工重分析按配置限制次数；词云/题材表严格来自当前榜单快照。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/novelanalyzer/modules/analysis/repository/AnalysisRepository.java backend/src/main/java/com/novelanalyzer/modules/analysis/service/AnalysisService.java backend/src/main/java/com/novelanalyzer/modules/analysis/controller/AnalysisController.java backend/src/main/java/com/novelanalyzer/modules/analysis/dto/RankReanalyzeRequest.java backend/src/main/java/com/novelanalyzer/modules/analysis/vo/RankAnalysisVO.java backend/src/main/java/com/novelanalyzer/modules/data/controller/DataController.java backend/src/main/java/com/novelanalyzer/modules/data/service/DataQueryService.java backend/src/main/java/com/novelanalyzer/modules/data/vo/RankVisualDataVO.java backend/src/main/java/com/novelanalyzer/modules/config/service/PromptConfigService.java backend/src/main/java/com/novelanalyzer/modules/config/controller/PromptConfigController.java backend/src/test/java/com/novelanalyzer/modules/data/Phase5BackendIntegrationTest.java
git commit -m "feat: add board scoped analysis cache and visuals"
```

### Task 5: Frontend Login, Rank Page, Prompt Config, And System Config Refactor

**Files:**
- Modify: `frontend/src/views/login/LoginView.vue`
- Modify: `frontend/src/views/rank/RankView.vue`
- Modify: `frontend/src/views/config/system/SystemConfigView.vue`
- Modify: `frontend/src/views/config/prompt/PromptConfigView.vue`
- Modify: `frontend/src/api/crawler.ts`
- Modify: `frontend/src/api/analysis.ts`
- Modify: `frontend/src/api/data.ts`
- Create: `frontend/src/api/system.ts`
- Modify: `frontend/src/types/crawler.ts`
- Modify: `frontend/src/types/data.ts`
- Modify: `frontend/src/types/config.ts`
- Create: `frontend/src/types/system.ts`
- Test: `frontend/src/views/login/__tests__/LoginView.spec.ts`
- Test: `frontend/src/views/rank/__tests__/RankView.spec.ts`
- Test: `frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts`
- Test: `frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts`

- [ ] **Step 1: Write the failing test**

```ts
test('loads board catalog, refreshes selected board, and pages current snapshot', async () => {
  vi.mocked(crawlerApi.getBoards).mockResolvedValue(boardCatalogResponse);
  vi.mocked(crawlerApi.refreshRankBoard).mockResolvedValue(refreshResponse);
  vi.mocked(crawlerApi.getRankPage).mockResolvedValue(pageResponse);
  vi.mocked(analysisApi.getRankLatest).mockResolvedValue(rankAnalysisResponse);
  vi.mocked(dataApi.getRankVisual).mockResolvedValue(rankVisualResponse);

  const wrapper = mount(RankView, { global: { plugins: [router, ElementPlus] } });
  await flushPromises();

  expect(crawlerApi.getBoards).toHaveBeenCalledWith({ platform: 'fanqie' });
  expect(wrapper.text()).toContain('都市脑洞');
  expect(wrapper.text()).toContain('重新分析');
});
```

```ts
test('login only shows project title and feature bullets, then triggers bootstrap', async () => {
  vi.mocked(systemApi.loginBootstrap).mockResolvedValue(bootstrapResponse);
  // mount + submit
  expect(wrapper.text()).not.toContain('JWT');
  expect(systemApi.loginBootstrap).toHaveBeenCalled();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm run test -- src/views/login/__tests__/LoginView.spec.ts src/views/rank/__tests__/RankView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts --run`

Expected: FAIL，因为登录页仍有大段说明，扫榜页仍写死 `male-hot-a` 等分类，系统/Prompt 配置页也还没有新增配置项与 JSON 契约字段。

- [ ] **Step 3: Write minimal implementation**

```ts
export interface RankBoardSelection {
  platform: 'fanqie';
  channelCode: string;
  channelName: string;
  boardCode: string;
  boardName: string;
}

export interface RankPageResult {
  snapshotId: number;
  snapshotTime: string;
  total: number;
  page: number;
  pageSize: number;
  items: RankBookItem[];
}
```

```vue
<el-select v-model="filters.channelCode" @change="handleChannelChange" />
<el-select v-model="filters.boardCode" @change="reloadBoardContext" />
<el-pagination
  :current-page="pager.page"
  :page-size="pager.pageSize"
  :total="pager.total"
  @current-change="handlePageChange"
/>
<el-button @click="reanalyzeBoard">重新分析</el-button>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm run test -- src/views/login/__tests__/LoginView.spec.ts src/views/rank/__tests__/RankView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts --run`

Expected: PASS，登录页展示收敛；扫榜页支持频道/榜单切换、分页、自动分析状态与剩余重分析次数；管理员页面可配置缓存期、次数限制和榜单 JSON 输出模板。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/login/LoginView.vue frontend/src/views/rank/RankView.vue frontend/src/views/config/system/SystemConfigView.vue frontend/src/views/config/prompt/PromptConfigView.vue frontend/src/api/crawler.ts frontend/src/api/analysis.ts frontend/src/api/data.ts frontend/src/api/system.ts frontend/src/types/crawler.ts frontend/src/types/data.ts frontend/src/types/config.ts frontend/src/types/system.ts frontend/src/views/login/__tests__/LoginView.spec.ts frontend/src/views/rank/__tests__/RankView.spec.ts frontend/src/views/config/system/__tests__/SystemConfigView.spec.ts frontend/src/views/config/prompt/__tests__/PromptConfigView.spec.ts
git commit -m "feat: rebuild rank page around shared board snapshots"
```

## Final Verification

- [ ] Run crawler regression: `python -m pytest tests/test_fanqie_crawler.py -q`
  Expected: 全部 PASS，目录发现和完整榜单抓取稳定

- [ ] Run backend targeted integration: `mvn -Dtest=CrawlerPhase3IntegrationTest,Phase5BackendIntegrationTest,SystemControllerTest test`
  Expected: BUILD SUCCESS，目录、快照、分页、登录补抓、榜单分析、配置接口均通过

- [ ] Run frontend targeted tests: `npm run test -- src/views/login/__tests__/LoginView.spec.ts src/views/rank/__tests__/RankView.spec.ts src/views/config/system/__tests__/SystemConfigView.spec.ts src/views/config/prompt/__tests__/PromptConfigView.spec.ts --run`
  Expected: PASS

- [ ] Run frontend type check: `npm run type-check`
  Expected: no errors

- [ ] Manual smoke in three terminals:

```bash
# terminal 1
cd crawler
uvicorn app.main:app --host 0.0.0.0 --port 9001

# terminal 2
cd backend
mvn spring-boot:run

# terminal 3
cd frontend
npm run dev -- --host
```

Expected: 登录后会先触发启用榜单 bootstrap；进入扫榜页默认加载已启用榜单；切换榜单只做数据库分页；分析在缓存期内复用，过期后自动重算，人工重分析次数按配置递减。
