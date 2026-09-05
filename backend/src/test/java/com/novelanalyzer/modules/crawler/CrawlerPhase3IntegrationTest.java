package com.novelanalyzer.modules.crawler;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobLockService;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankBoard;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.service.CrawlerRankPersistenceService;
import com.novelanalyzer.modules.crawler.service.CrawlerCacheService;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:phase3db;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100"
    }
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class CrawlerPhase3IntegrationTest {

    private static final String ADMIN_PHONE = "15599316908";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CrawlerRankPersistenceService crawlerRankPersistenceService;

    @MockBean
    private PythonCrawlerClient pythonCrawlerClient;

    @MockBean
    private AsyncJobLockService asyncJobLockService;

    @MockBean
    private CrawlerCacheService crawlerCacheService;

    @MockBean
    private AgentResourcePressureService resourcePressureService;

    private final Map<String, Object> strictRankRefreshCache = new ConcurrentHashMap<>();

    @BeforeEach
    void prepareState() {
        strictRankRefreshCache.clear();
        when(crawlerCacheService.getStrict(anyString(), any())).thenAnswer(invocation ->
            strictRankRefreshCache.get(invocation.getArgument(0, String.class))
        );
        when(crawlerCacheService.putIfAbsent(anyString(), any(), anyLong())).thenAnswer(invocation ->
            strictRankRefreshCache.putIfAbsent(
                invocation.getArgument(0, String.class),
                invocation.getArgument(1)
            ) == null
        );
        when(crawlerCacheService.compareAndSet(anyString(), any(), any(), anyLong())).thenAnswer(invocation ->
            strictRankRefreshCache.replace(
                invocation.getArgument(0, String.class),
                invocation.getArgument(1),
                invocation.getArgument(2)
            )
        );
        when(crawlerCacheService.evictIfValue(anyString(), any())).thenAnswer(invocation ->
            strictRankRefreshCache.remove(
                invocation.getArgument(0, String.class),
                invocation.getArgument(1)
            )
        );
        when(asyncJobLockService.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(true);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        when(asyncJobLockService.renewStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = 1", ADMIN_PHONE);
        try {
            RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
            try {
                connection.serverCommands().flushDb();
            } finally {
                connection.close();
            }
        } catch (RedisConnectionFailureException ignored) {
            // Crawler controller tests do not depend on Redis. Keep them runnable when local Redis is absent.
        }
    }

    @Test
    void shouldSyncBoardCatalogFromCrawler() throws Exception {
        when(pythonCrawlerClient.fetchBoardCatalog("fanqie", 20)).thenReturn(List.of(
            boardItem("fanqie", "male-new", "男频新书榜", "urban-brain", "都市脑洞"),
            boardItem("fanqie", "male-read", "男频阅读榜", "urban-power", "都市高武")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(get("/api/crawler/boards")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].channelCode").value("male-new"))
            .andExpect(jsonPath("$.data[0].channelName").value("男频新书榜"))
            .andExpect(jsonPath("$.data[0].boards[0].boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data[0].boards[0].boardName").value("都市脑洞"));

        Integer boardCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM rank_board WHERE platform = ? AND deleted = 0",
            Integer.class,
            "fanqie"
        );
        assertThat(boardCount).isEqualTo(2);
    }

    @Test
    void shouldReturnPersistedBoardCatalogWithoutSyncingCrawlerAgain() throws Exception {
        insertRankBoard("fanqie", "male-new", "persisted-channel-1", "urban-brain", "persisted-board-1");
        insertRankBoard("fanqie", "male-read", "persisted-channel-2", "urban-power", "persisted-board-2");

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(get("/api/crawler/boards")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].channelCode").value("male-new"))
            .andExpect(jsonPath("$.data[0].boards[0].boardCode").value("urban-brain"));

        verify(pythonCrawlerClient, times(0)).fetchBoardCatalog("fanqie", 20);
    }

    @Test
    void shouldSaveAndReturnUserRankPreference() throws Exception {
        String token = loginAndGetToken("writer", "writer123");

        mockMvc.perform(post("/api/crawler/preference")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","rankFetchCount":40}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.platform").value("fanqie"))
            .andExpect(jsonPath("$.data.channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data.rankFetchCount").value(40));

        mockMvc.perform(get("/api/crawler/preference")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.userId").value(2))
            .andExpect(jsonPath("$.data.channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data.rankFetchCount").value(40));
    }

    @Test
    void shouldRejectForceRankRefreshForOrdinaryUser() throws Exception {
        String token = loginAndGetToken("writer", "writer123");

        mockMvc.perform(post("/api/crawler/rank/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE","idempotencyKey":"user-force-denied"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        verify(pythonCrawlerClient, times(0)).fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldDefaultBoardRefreshToThirtyBooksWhenRankFetchCountMissing() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 30, 20))
            .thenReturn(rankItems(45));

        String token = loginAndGetToken("admin", "admin123");
        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE","idempotencyKey":"default-thirty-books"}
                    """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(30));

        Integer persistedRankCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM crawl_rank WHERE channel_code = ? AND board_code = ? AND deleted = 0",
            Integer.class,
            "male-new",
            "urban-brain"
        );
        assertThat(persistedRankCount).isEqualTo(30);

        Integer snapshotRecordCount = jdbcTemplate.queryForObject(
            """
                SELECT record_count
                FROM rank_snapshot
                WHERE rank_board_id = (
                    SELECT id FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0
                )
                ORDER BY id DESC
                LIMIT 1
                """,
            Integer.class,
            "fanqie",
            "male-new",
            "urban-brain"
        );
        assertThat(snapshotRecordCount).isEqualTo(30);
    }

    @Test
    void shouldRespectRequestedRankFetchCountWhenRefreshingBoard() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 40, 20))
            .thenReturn(rankItems(45));

        String token = loginAndGetToken("admin", "admin123");
        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE","rankFetchCount":40,"idempotencyKey":"requested-forty-books"}
                    """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(40));

        Integer snapshotRecordCount = jdbcTemplate.queryForObject(
            """
                SELECT record_count
                FROM rank_snapshot
                WHERE rank_board_id = (
                    SELECT id FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0
                )
                ORDER BY id DESC
                LIMIT 1
                """,
            Integer.class,
            "fanqie",
            "male-new",
            "urban-brain"
        );
        assertThat(snapshotRecordCount).isEqualTo(40);
    }

    @Test
    void shouldRollbackWholeBoardSnapshotWhenOneRankItemCannotBePersisted() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");
        Integer snapshotsBefore = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class);
        Integer ranksBefore = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class);
        Integer booksBefore = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_book", Integer.class);
        ExternalRankItem invalid = rankItem(
            2,
            "X".repeat(201),
            "Author 02",
            "https://fanqienovel.com/page/rollback-02"
        );
        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 30, 20))
            .thenReturn(List.of(
                rankItem(1, "Rollback Book 01", "Author 01", "https://fanqienovel.com/page/rollback-01"),
                invalid
            ));

        String token = loginAndGetToken("admin", "admin123");
        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE","idempotencyKey":"rollback-invalid-rank-item"}
                    """)
            .andExpect(status().is5xxServerError());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class))
            .isEqualTo(snapshotsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class))
            .isEqualTo(ranksBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_book", Integer.class))
            .isEqualTo(booksBefore);
    }

    @Test
    void shouldRollbackSecondSnapshotBatchWhenIdempotencyResultWasAlreadyCommitted() {
        long boardId = insertRankBoard(
            "fanqie",
            "idempotency-channel",
            "Idempotency Channel",
            "idempotency-board",
            "Idempotency Board"
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(boardId);
        CrawlerRankRequest request = rankPersistenceRequest("idempotency-channel", "idempotency-board");
        ExternalRankItem item = rankItem(
            1,
            "Idempotency Book",
            "Idempotency Author",
            "https://fanqienovel.com/page/idempotency-book"
        );
        item.setPlatformBookId("idempotency-book-1");
        CrawlerRankPersistenceService.IdempotencyContext context =
            new CrawlerRankPersistenceService.IdempotencyContext("a".repeat(64), "b".repeat(64));

        long firstToken = crawlerRankPersistenceService.claimFencingToken(boardId);
        RankSnapshotEntity first = crawlerRankPersistenceService.persistBoardSnapshot(
            request,
            board,
            "FORCE",
            "idempotency-category",
            List.of(item),
            LocalDateTime.now(),
            () -> true,
            firstToken,
            context
        );
        Integer snapshotsAfterFirst = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class);
        Integer ranksAfterFirst = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class);

        long secondToken = crawlerRankPersistenceService.claimFencingToken(boardId);
        assertThatThrownBy(() -> crawlerRankPersistenceService.persistBoardSnapshot(
            request,
            board,
            "FORCE",
            "idempotency-category",
            List.of(item),
            LocalDateTime.now(),
            () -> true,
            secondToken,
            context
        ))
            .isInstanceOfSatisfying(
                CrawlerRankPersistenceService.RankRefreshAlreadyCommittedException.class,
                ex -> assertThat(ex.getResult().getSnapshotId()).isEqualTo(first.getId())
            );

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class))
            .isEqualTo(snapshotsAfterFirst);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class))
            .isEqualTo(ranksAfterFirst);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM crawler_rank_refresh_commit WHERE idempotency_hash = ?",
            Integer.class,
            context.idempotencyHash()
        )).isEqualTo(1);
    }

    @Test
    void shouldRejectStaleFencingTokenWithoutPersistingSnapshot() {
        long boardId = insertRankBoard(
            "fanqie",
            "fence-channel",
            "Fence Channel",
            "fence-board",
            "Fence Board"
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(boardId);
        CrawlerRankRequest request = rankPersistenceRequest("fence-channel", "fence-board");
        ExternalRankItem item = rankItem(
            1,
            "Fence Book",
            "Fence Author",
            "https://fanqienovel.com/page/fence-book"
        );
        item.setPlatformBookId("fence-book-1");
        Integer snapshotsBefore = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class);
        long staleToken = crawlerRankPersistenceService.claimFencingToken(boardId);
        crawlerRankPersistenceService.claimFencingToken(boardId);

        assertThatThrownBy(() -> crawlerRankPersistenceService.persistBoardSnapshot(
            request,
            board,
            "FORCE",
            "fence-category",
            List.of(item),
            LocalDateTime.now(),
            () -> true,
            staleToken,
            null
        ))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .hasMessageContaining("fencing token is stale");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class))
            .isEqualTo(snapshotsBefore);
    }

    @Test
    void shouldRollbackSnapshotWhenOwnershipIsLostInBeforeCommitCheck() {
        long boardId = insertRankBoard(
            "fanqie",
            "commit-fence-channel",
            "Commit Fence Channel",
            "commit-fence-board",
            "Commit Fence Board"
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(boardId);
        CrawlerRankRequest request = rankPersistenceRequest("commit-fence-channel", "commit-fence-board");
        Integer snapshotsBefore = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class);
        Integer tasksBefore = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawler_task", Integer.class);
        long fencingToken = crawlerRankPersistenceService.claimFencingToken(boardId);
        AtomicInteger ownershipChecks = new AtomicInteger();

        assertThatThrownBy(() -> crawlerRankPersistenceService.persistBoardSnapshot(
            request,
            board,
            "FORCE",
            "commit-fence-category",
            List.of(),
            LocalDateTime.now(),
            () -> ownershipChecks.incrementAndGet() <= 4,
            fencingToken,
            null
        ))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .hasMessageContaining("ownership lost");

        assertThat(ownershipChecks.get()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rank_snapshot", Integer.class))
            .isEqualTo(snapshotsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawler_task", Integer.class))
            .isEqualTo(tasksBefore);
    }

    @Test
    void shouldReturnConflictWhenBoardLockHasNoNewerSnapshot() throws Exception {
        long boardId = insertRankBoard(
            "fanqie",
            "busy-channel",
            "Busy Channel",
            "busy-board",
            "Busy Board"
        );
        insertBoardSnapshot(boardId, LocalDateTime.now().minusDays(10), 1);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(false);
        String token = loginAndGetToken("admin", "admin123");

        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"busy-channel","boardCode":"busy-board","refreshMode":"AUTO","idempotencyKey":"busy-board-refresh"}
                    """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(CrawlerService.RANK_REFRESH_IN_PROGRESS));
    }

    @Test
    void shouldCacheRankAndPersistData() throws Exception {
        when(pythonCrawlerClient.fetchRank("fanqie", "male-read", "1141", 30, 20)).thenReturn(List.of(
            rankItem(1, "示例书1", "作者1", "https://fanqienovel.com/page/abc1"),
            rankItem(2, "示例书2", "作者2", "https://fanqienovel.com/page/abc2")
        ));

        String token = loginAndGetToken("admin", "admin123");
        String requestBody = "{\"platform\":\"fanqie\",\"category\":\"male-hot-a\"}";

        performAsyncLegacyRank(token, requestBody)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2));

        performAsyncLegacyRank(token, requestBody)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2));

        verify(pythonCrawlerClient, times(1)).fetchRank("fanqie", "male-read", "1141", 30, 20);
        Integer rankCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class);
        Integer bookCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_book", Integer.class);
        assertThat(rankCount).isEqualTo(2);
        assertThat(bookCount).isEqualTo(2);
    }

    @Test
    void shouldFetchBookAndChapters() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        when(pythonCrawlerClient.fetchRank("fanqie", "male-read", "1140", 30, 20)).thenReturn(List.of(
            rankItem(1, "示例书3", "作者3", "https://fanqienovel.com/page/abc3")
        ));
        when(pythonCrawlerClient.fetchBook(anyString(), anyString(), anyInt())).thenReturn(bookDetail("https://fanqienovel.com/page/abc3"));
        when(pythonCrawlerClient.fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(
            chapterItem(1, "第一章"),
            chapterItem(2, "第二章"),
            chapterItem(3, "第三章")
        ));

        String token = loginAndGetToken("admin", "admin123");
        performAsyncLegacyRank(
            token,
            "{\"platform\":\"fanqie\",\"category\":\"male-hot-b\"}"
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        Long bookId = jdbcTemplate.queryForObject("SELECT id FROM crawl_book LIMIT 1", Long.class);
        assertThat(bookId).isNotNull();

        mockMvc.perform(get("/api/crawler/book/" + bookId)
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.bookId").value(bookId));

        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3));

        Integer chapterCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_chapter", Integer.class);
        assertThat(chapterCount).isEqualTo(3);
    }

    @Test
    void shouldRefreshWholeBoardAndPageSnapshotWithoutRecrawling() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 30, 20))
            .thenReturn(rankItems(30));

        String token = loginAndGetToken("admin", "admin123");
        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE","idempotencyKey":"whole-board-force"}
                    """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.snapshotId").isNumber())
            .andExpect(jsonPath("$.data.total").value(30))
            .andExpect(jsonPath("$.data.reused").value(false));

        mockMvc.perform(get("/api/crawler/rank/page")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain")
                .param("page", "2")
                .param("pageSize", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(5))
            .andExpect(jsonPath("$.data.total").value(30))
            .andExpect(jsonPath("$.data.items.length()").value(5))
            .andExpect(jsonPath("$.data.items[0].rankNo").value(6))
            .andExpect(jsonPath("$.data.items[0].bookName").value("Book 06"));

        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"AUTO","idempotencyKey":"whole-board-auto-reuse"}
                    """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(30))
            .andExpect(jsonPath("$.data.reused").value(true));

        verify(pythonCrawlerClient, times(1)).fetchRank("fanqie", "male-new", "urban-brain", 30, 20);
    }

    @Test
    void shouldReturnDatabaseSnapshotWhenForceRefreshQuotaExceeded() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        LocalDateTime snapshotTime = LocalDateTime.now().minusHours(8);
        long bookId = insertBook("fanqie", "force-db-1", "Forced DB Book", "Forced DB Author", "Forced DB Intro",
            "https://fanqienovel.com/page/force-db-1", snapshotTime);
        long boardId = insertRankBoard("fanqie", "male-read", "男频阅读榜", "urban-power", "都市高武");
        long snapshotId = insertBoardSnapshot(boardId, snapshotTime, 1);
        insertRankSnapshot("fanqie", "male-hot-b", "male-read", "urban-power", snapshotId, snapshotTime, bookId,
            "Forced DB Book", "https://fanqienovel.com/page/force-db-1", "Forced DB Author", "Forced DB Intro", 1);
        insertCrawlerTask("rank_refresh", "fanqie",
            "{\"platform\":\"fanqie\",\"channelCode\":\"male-read\",\"boardCode\":\"urban-power\",\"refreshMode\":\"FORCE\"}",
            2, LocalDateTime.now().minusHours(10), LocalDateTime.now().minusHours(10).plusMinutes(1));
        insertCrawlerTask("rank_refresh", "fanqie",
            "{\"platform\":\"fanqie\",\"channelCode\":\"male-read\",\"boardCode\":\"urban-power\",\"refreshMode\":\"FORCE\"}",
            2, LocalDateTime.now().minusHours(6), LocalDateTime.now().minusHours(6).plusMinutes(1));

        when(pythonCrawlerClient.fetchRank("fanqie", "male-read", "urban-power", 30, 20)).thenReturn(List.of(
            rankItem(1, "Crawler Forced Book", "Crawler Author", "https://fanqienovel.com/page/crawler-force")
        ));

        String token = loginAndGetToken("admin", "admin123");
        performAsyncRankRefresh(token, """
                    {"platform":"fanqie","channelCode":"male-read","boardCode":"urban-power","refreshMode":"FORCE","forceReason":"manual","idempotencyKey":"force-quota-limited"}
                    """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.reused").value(true))
            .andExpect(jsonPath("$.data.refreshLimited").value(true));

        verify(pythonCrawlerClient, times(0)).fetchRank("fanqie", "male-read", "urban-power", 30, 20);
    }

    @Test
    void shouldRepairBookLinkWhenStoredLinkInvalid() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        LocalDateTime lastCrawlTime = LocalDateTime.now().minusDays(10);
        long bookId = insertBook("fanqie", "123456", "Repair Target", "Repair Author", "Repair Intro",
            "https://fanqienovel.com/page/invalid-old", lastCrawlTime);

        when(pythonCrawlerClient.fetchBook("fanqie", "https://fanqienovel.com/page/invalid-old", 20))
            .thenThrow(new RuntimeException("invalid link"));
        when(pythonCrawlerClient.fetchBook("fanqie", "https://fanqienovel.com/page/123456", 20))
            .thenReturn(bookDetail("https://fanqienovel.com/page/123456"));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(get("/api/crawler/book/" + bookId)
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.bookUrl").value("https://fanqienovel.com/page/123456"));
    }

    @Test
    void shouldRefetchChaptersWhenPersistedChaptersAreStaleDescendingSnapshot() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        LocalDateTime lastCrawlTime = LocalDateTime.now().minusHours(2);
        long bookId = insertBook(
            "fanqie",
            "repair-chapter-1",
            "Repair Chapter Target",
            "Repair Author",
            "Repair Intro",
            "https://fanqienovel.com/page/repair-chapter-1",
            lastCrawlTime
        );
        insertChapter(bookId, 1, "第128章 潜入", "old chapter 128", lastCrawlTime);
        insertChapter(bookId, 2, "第127章 计策", "old chapter 127", lastCrawlTime);
        insertChapter(bookId, 3, "第126章 粮草大营", "old chapter 126", lastCrawlTime);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/repair-chapter-1", 3, 1, 20, 3))
            .thenReturn(List.of(
                chapterItem(1, "第1章 开局"),
                chapterItem(2, "第2章 相遇"),
                chapterItem(3, "第3章 启程")
            ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("第1章 开局"))
            .andExpect(jsonPath("$.data[2].chapterTitle").value("第3章 启程"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/repair-chapter-1", 3, 1, 20, 3);
    }

    @Test
    void shouldReuseStoredPrefixChaptersWithoutRecrawlingForSmallerRequest() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "prefix-reuse-1",
            "Prefix Reuse Book",
            "Reuse Author",
            "Reuse Intro",
            "https://fanqienovel.com/page/prefix-reuse-1",
            crawlTime
        );
        insertChapter(bookId, 1, "第1章 开局", "chapter 1", crawlTime);
        insertChapter(bookId, 2, "第2章 相遇", "chapter 2", crawlTime);
        insertChapter(bookId, 3, "第3章 启程", "chapter 3", crawlTime);
        insertChapter(bookId, 4, "第4章 破局", "chapter 4", crawlTime);
        insertChapter(bookId, 5, "第5章 收束", "chapter 5", crawlTime);

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("第1章 开局"))
            .andExpect(jsonPath("$.data[2].chapterTitle").value("第3章 启程"));

        verify(pythonCrawlerClient, times(0))
            .fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void shouldOnlyFetchMissingChapterRangeWhenExtendingStoredPrefix() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "prefix-extend-1",
            "Prefix Extend Book",
            "Extend Author",
            "Extend Intro",
            "https://fanqienovel.com/page/prefix-extend-1",
            crawlTime
        );
        insertChapter(bookId, 1, "第1章 开局", "chapter 1", crawlTime);
        insertChapter(bookId, 2, "第2章 相遇", "chapter 2", crawlTime);
        insertChapter(bookId, 3, "第3章 启程", "chapter 3", crawlTime);
        insertChapter(bookId, 4, "第4章 破局", "chapter 4", crawlTime);
        insertChapter(bookId, 5, "第5章 收束", "chapter 5", crawlTime);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/prefix-extend-1", 5, 6, 20, 3))
            .thenReturn(List.of(
                chapterItem(6, "第6章 转折"),
                chapterItem(7, "第7章 深入"),
                chapterItem(8, "第8章 对峙"),
                chapterItem(9, "第9章 反转"),
                chapterItem(10, "第10章 定局")
            ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":10}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(10))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("第1章 开局"))
            .andExpect(jsonPath("$.data[9].chapterTitle").value("第10章 定局"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/prefix-extend-1", 5, 6, 20, 3);
    }

    @Test
    void shouldRepairLegacyChaptersWithoutSourceWordCount() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "legacy-chapter-1",
            "Legacy Chapter Book",
            "Legacy Author",
            "Legacy Intro",
            "https://fanqienovel.com/page/legacy-chapter-1",
            crawlTime
        );
        insertChapter(bookId, 1, "Chapter 1", "legacy truncated chapter", crawlTime, null);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/legacy-chapter-1", 1, 1, 20, 3))
            .thenReturn(List.of(chapterItem(1, "Chapter 1 repaired", 2452)));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("Chapter 1 repaired"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/legacy-chapter-1", 1, 1, 20, 3);
    }

    @Test
    void shouldRepairPersistedChaptersWhenStoredContentIsShorterThanSourceWordCount() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "short-chapter-1",
            "Short Chapter Book",
            "Short Author",
            "Short Intro",
            "https://fanqienovel.com/page/short-chapter-1",
            crawlTime
        );
        insertChapter(bookId, 1, "Chapter 1", "short content", crawlTime, 2452);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/short-chapter-1", 1, 1, 20, 3))
            .thenReturn(List.of(chapterItem(1, "Chapter 1 repaired", 2452)));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("Chapter 1 repaired"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/short-chapter-1", 1, 1, 20, 3);
    }

    @Test
    void shouldForceRefreshChaptersAndReturnUsageStatsForNormalUser() throws Exception {
        insertSystemConfig("crawler.chapter.force-refresh.user-max-times", "3");
        insertSystemConfig("crawler.rank.refresh-days", "5");
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "force-refresh-user-1",
            "Force Refresh User Book",
            "User Author",
            "User Intro",
            "https://fanqienovel.com/page/force-refresh-user-1",
            crawlTime
        );
        insertChapter(bookId, 1, "第1章 旧内容", "old chapter 1", crawlTime);
        insertChapter(bookId, 2, "第2章 旧内容", "old chapter 2", crawlTime);
        insertChapter(bookId, 3, "第3章 旧内容", "old chapter 3", crawlTime);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/force-refresh-user-1", 3, 1, 20, 3))
            .thenReturn(List.of(
                chapterItem(1, "第1章 新内容"),
                chapterItem(2, "第2章 新内容"),
                chapterItem(3, "第3章 新内容")
            ));

        String token = loginAndGetToken("writer", "writer123");
        mockMvc.perform(post("/api/crawler/chapters/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.maxAllowedRefreshTimes").value(3))
            .andExpect(jsonPath("$.data.usedRefreshTimes").value(1))
            .andExpect(jsonPath("$.data.remainingRefreshTimes").value(2))
            .andExpect(jsonPath("$.data.windowDays").value(5))
            .andExpect(jsonPath("$.data.chapters[0].chapterTitle").value("第1章 新内容"))
            .andExpect(jsonPath("$.data.chapters[0].crawlTime").isNotEmpty())
            .andExpect(jsonPath("$.data.chapters[2].chapterTitle").value("第3章 新内容"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/force-refresh-user-1", 3, 1, 20, 3);
    }

    @Test
    void shouldRejectForceRefreshWhenNormalUserExceedsConfiguredLimit() throws Exception {
        insertSystemConfig("crawler.chapter.force-refresh.user-max-times", "1");
        insertSystemConfig("crawler.rank.refresh-days", "5");
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "force-refresh-limit-1",
            "Force Refresh Limit Book",
            "Limit Author",
            "Limit Intro",
            "https://fanqienovel.com/page/force-refresh-limit-1",
            crawlTime
        );
        insertCrawlerTask(
            "chapter_force_refresh",
            "fanqie",
            "{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3,\"userId\":2,\"username\":\"writer\"}",
            2,
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(2).plusMinutes(1)
        );

        String token = loginAndGetToken("writer", "writer123");
        mockMvc.perform(post("/api/crawler/chapters/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));

        verify(pythonCrawlerClient, times(0))
            .fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void shouldUseFixedAdminRefreshLimitOfTwenty() throws Exception {
        insertSystemConfig("crawler.chapter.force-refresh.user-max-times", "1");
        insertSystemConfig("crawler.rank.refresh-days", "5");
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "force-refresh-admin-1",
            "Force Refresh Admin Book",
            "Admin Author",
            "Admin Intro",
            "https://fanqienovel.com/page/force-refresh-admin-1",
            crawlTime
        );

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/force-refresh-admin-1", 1, 1, 20, 3))
            .thenReturn(List.of(chapterItem(1, "第1章 管理员刷新")));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.maxAllowedRefreshTimes").value(20))
            .andExpect(jsonPath("$.data.usedRefreshTimes").value(1))
            .andExpect(jsonPath("$.data.remainingRefreshTimes").value(19))
            .andExpect(jsonPath("$.data.chapters[0].chapterTitle").value("第1章 管理员刷新"));
    }

    private ResultActions performAsyncRankRefresh(String token, String requestBody) throws Exception {
        MvcResult asyncResult = mockMvc.perform(post("/api/crawler/rank/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(request().asyncStarted())
            .andReturn();
        return mockMvc.perform(asyncDispatch(asyncResult));
    }

    private ResultActions performAsyncLegacyRank(String token, String requestBody) throws Exception {
        MvcResult asyncResult = mockMvc.perform(post("/api/crawler/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(request().asyncStarted())
            .andReturn();
        return mockMvc.perform(asyncDispatch(asyncResult));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        String phone = switch (username) {
            case "admin" -> ADMIN_PHONE;
            case "writer" -> "13800138001";
            case "15599316908" -> "15599316908";
            default -> username;
        };
        MvcResult result = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private ExternalRankItem rankItem(int rankNo, String bookName, String author, String url) {
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(rankNo);
        item.setBookName(bookName);
        item.setAuthor(author);
        item.setIntro("intro-" + bookName);
        item.setBookUrl(url);
        item.setPlatformBookId("pid-" + rankNo);
        return item;
    }

    private CrawlerRankRequest rankPersistenceRequest(String channelCode, String boardCode) {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setChannelCode(channelCode);
        request.setBoardCode(boardCode);
        request.setRefreshMode("FORCE");
        return request;
    }

    private List<ExternalRankItem> rankItems(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(index -> rankItem(
                index,
                "Book " + String.format("%02d", index),
                "Author " + String.format("%02d", index),
                "https://fanqienovel.com/page/board-" + String.format("%02d", index)
            ))
            .toList();
    }

    private ExternalBookDetail bookDetail(String url) {
        ExternalBookDetail detail = new ExternalBookDetail();
        detail.setBookName("详情书名");
        detail.setAuthor("详情作者");
        detail.setIntro("详情简介");
        detail.setBookUrl(url);
        detail.setPlatformBookId("pid-detail");
        return detail;
    }

    private ExternalChapterItem chapterItem(int no, String title) {
        return chapterItem(no, title, null);
    }

    private ExternalChapterItem chapterItem(int no, String title, Integer sourceWordCount) {
        ExternalChapterItem item = new ExternalChapterItem();
        item.setChapterNo(no);
        item.setChapterTitle(title);
        item.setContent(title + " 内容");
        item.setSourceWordCount(sourceWordCount);
        return item;
    }
    private ExternalRankBoard boardItem(String platform,
                                        String channelCode,
                                        String channelName,
                                        String boardCode,
                                        String boardName) {
        ExternalRankBoard item = new ExternalRankBoard();
        item.setPlatform(platform);
        item.setChannelCode(channelCode);
        item.setChannelName(channelName);
        item.setBoardCode(boardCode);
        item.setBoardName(boardName);
        return item;
    }

    private void insertSystemConfig(String key, String value) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS system_config (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                config_key VARCHAR(100) NOT NULL,
                config_value CLOB,
                config_type VARCHAR(50),
                description VARCHAR(200),
                is_editable TINYINT DEFAULT 1,
                create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                deleted TINYINT DEFAULT 0
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO system_config(config_key, config_value, config_type, description, is_editable, deleted) VALUES (?, ?, ?, ?, ?, ?)",
            key, value, "crawler", key, 1, 0
        );
    }

    private long insertBook(String platform,
                            String platformBookId,
                            String bookName,
                            String author,
                            String intro,
                            String bookUrl,
                            LocalDateTime lastCrawlTime) {
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            platformBookId,
            bookName,
            author,
            intro,
            bookUrl,
            Timestamp.valueOf(lastCrawlTime),
            Timestamp.valueOf(lastCrawlTime),
            Timestamp.valueOf(lastCrawlTime),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM crawl_book WHERE platform = ? AND book_url = ?",
            Long.class,
            platform,
            bookUrl
        );
        assertThat(id).isNotNull();
        return id;
    }

    private long insertRankBoard(String platform,
                                 String channelCode,
                                 String channelName,
                                 String boardCode,
                                 String boardName) {
        jdbcTemplate.update(
            "INSERT INTO rank_board(platform, channel_code, board_code, board_name, description, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            channelCode,
            boardCode,
            boardName,
            channelName,
            Timestamp.valueOf(LocalDateTime.now()),
            Timestamp.valueOf(LocalDateTime.now()),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0",
            Long.class,
            platform,
            channelCode,
            boardCode
        );
        assertThat(id).isNotNull();
        return id;
    }

    private long insertBoardSnapshot(long rankBoardId, LocalDateTime snapshotTime, int recordCount) {
        jdbcTemplate.update(
            "INSERT INTO rank_snapshot(rank_board_id, snapshot_time, record_count, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?)",
            rankBoardId,
            Timestamp.valueOf(snapshotTime),
            recordCount,
            Timestamp.valueOf(snapshotTime),
            Timestamp.valueOf(snapshotTime),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_snapshot WHERE rank_board_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
            Long.class,
            rankBoardId
        );
        assertThat(id).isNotNull();
        return id;
    }

    private void insertRankSnapshot(String platform,
                                    String category,
                                    String channelCode,
                                    String boardCode,
                                    Long snapshotId,
                                    LocalDateTime crawlTime,
                                    long bookId,
                                    String bookName,
                                    String bookUrl,
                                    String author,
                                    String intro,
                                    int rankNo) {
        jdbcTemplate.update(
            "INSERT INTO crawl_rank(platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            category,
            channelCode,
            boardCode,
            snapshotId,
            rankNo,
            bookId,
            bookName,
            bookUrl,
            author,
            intro,
            Timestamp.valueOf(crawlTime),
            Timestamp.valueOf(crawlTime),
            0
        );
    }

    private void insertCrawlerTask(String taskType,
                                   String platform,
                                   String requestJson,
                                   int status,
                                   LocalDateTime startTime,
                                   LocalDateTime endTime) {
        jdbcTemplate.update(
            "INSERT INTO crawler_task(task_type, platform, request_json, status, start_time, end_time, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            taskType,
            platform,
            requestJson,
            status,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime)
        );
    }

    private void insertChapter(long bookId,
                               int chapterNo,
                               String chapterTitle,
                               String content,
                               LocalDateTime crawlTime) {
        insertChapter(bookId, chapterNo, chapterTitle, content, crawlTime, content.length());
    }

    private void insertChapter(long bookId,
                               int chapterNo,
                               String chapterTitle,
                               String content,
                               LocalDateTime crawlTime,
                               Integer sourceWordCount) {
        jdbcTemplate.update(
            "INSERT INTO crawl_chapter(platform, book_id, chapter_no, chapter_title, content, word_count, source_word_count, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie",
            bookId,
            chapterNo,
            chapterTitle,
            content,
            content.length(),
            sourceWordCount,
            Timestamp.valueOf(crawlTime),
            Timestamp.valueOf(crawlTime),
            0
        );
    }
}
