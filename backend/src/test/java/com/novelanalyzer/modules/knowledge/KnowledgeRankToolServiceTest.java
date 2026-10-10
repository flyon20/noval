package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.RankLookupRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRankToolService;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgeranktooldb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=never",
        "app.auth.jwt-secret=test-jwt-secret-with-enough-length-1234567890",
        "app.crawler.internal-api-key=crawler-internal-api-key-with-enough-length-1234567890",
        "app.ai.langgraph-worker.internal-api-key=langgraph-internal-key-with-enough-length-1234567890",
        "app.knowledge.index.queue-enabled=false",
        "app.knowledge.index.rank-incremental-enabled=true",
        "app.knowledge.embedding.api-key=test-embedding-key"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase7-knowledge-schema-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class KnowledgeRankToolServiceTest {

    @Autowired
    private KnowledgeRankToolService rankToolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeProperties knowledgeProperties;

    @MockBean
    private com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;

    @MockBean(name = "knowledgeIndexTaskExecutor")
    private TaskExecutor knowledgeIndexTaskExecutor;

    @Test
    void shouldLookupLatestTopOneRankByBoardAndCategory() {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 101L, "入伍两次！我被原部队拉进黑名单", "朝朝和", "退伍入伍都市脑洞");
        insertRank(10L, "male-new", "urban-brain", 2, 102L, "第二本", "作者B", "都市脑洞第二名");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setCategory("都市脑洞");
        request.setRankNo(1);
        request.setLimit(5);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRankNo()).isEqualTo(1);
        assertThat(results.get(0).getBookName()).isEqualTo("入伍两次！我被原部队拉进黑名单");
        assertThat(results.get(0).getAuthor()).isEqualTo("朝朝和");
        assertThat(results.get(0).getSourceLabel()).contains("男频新书榜").contains("都市脑洞").contains("#1");
    }

    @Test
    void shouldLookupLatestTopNOrderedByRankNumber() {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male-new", "urban-brain", 2, 102L, "第二本", "作者B", "第二");
        insertRank(10L, "male-new", "urban-brain", 1, 101L, "第一本", "作者A", "第一");
        insertRank(10L, "male-new", "urban-brain", 3, 103L, "第三本", "作者C", "第三");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setLimit(2);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName).containsExactly("第一本", "第二本");
    }

    @Test
    void shouldSubmitRankIncrementalIndexJobsForLookupResults() {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 101L, "我下午才营业", "我是幕后煮屎人", "早餐系统");
        insertRank(10L, "male-new", "urban-brain", 2, 102L, "长生两十六亿年，被妹妹首播曝光", "军爷爱上大东北", "长生直播曝光");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setCategory("都市脑洞");
        request.setLimit(2);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName)
            .containsExactly("我下午才营业", "长生两十六亿年，被妹妹首播曝光");
        ArgumentCaptor<Runnable> dispatch = ArgumentCaptor.forClass(Runnable.class);
        verify(knowledgeIndexTaskExecutor).execute(dispatch.capture());
        verifyNoInteractions(knowledgeIndexJobExecutor);

        dispatch.getValue().run();

        verify(knowledgeIndexJobExecutor).submitAndExecute(101L, null, "RANK_INCREMENTAL");
        verify(knowledgeIndexJobExecutor).submitAndExecute(102L, null, "RANK_INCREMENTAL");
    }

    @Test
    void shouldOnlyDispatchUniqueBooksFromLatestSnapshot() {
        LocalDateTime now = LocalDateTime.now();
        insertRankBoardWithSnapshot(
            "fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L, now.minusDays(7)
        );
        insertRankWithCategory(10L, "都市脑洞", "male-new", "urban-brain", 1, 101L, "共同作品", "作者A", "旧简介");
        insertRankWithCategory(10L, "都市脑洞", "male-new", "urban-brain", 2, 102L, "历史作品", "作者B", "旧简介");
        insertRankBoardSnapshot(11L, now);
        insertRankWithCategory(11L, "都市脑洞", "male-new", "urban-brain", 1, 101L, "共同作品", "作者A", "新简介");
        insertRankWithCategory(11L, "都市脑洞", "male-new", "urban-brain", 2, 103L, "当前作品", "作者C", "新简介");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setFreshness("time_window");
        request.setAllowHistorical(true);
        request.setTimeWindowDays(30);
        request.setLimit(4);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).hasSize(4);
        ArgumentCaptor<Runnable> dispatch = ArgumentCaptor.forClass(Runnable.class);
        verify(knowledgeIndexTaskExecutor).execute(dispatch.capture());
        verifyNoInteractions(knowledgeIndexJobExecutor);

        dispatch.getValue().run();

        verify(knowledgeIndexJobExecutor).submitAndExecute(101L, null, "RANK_INCREMENTAL");
        verify(knowledgeIndexJobExecutor).submitAndExecute(103L, null, "RANK_INCREMENTAL");
        verify(knowledgeIndexJobExecutor, never()).submitAndExecute(102L, null, "RANK_INCREMENTAL");
    }

    @Test
    void shouldSkipReadThroughIndexingWhenRankIncrementalIsDisabled() {
        knowledgeProperties.getIndex().setRankIncrementalEnabled(false);
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 101L, "第一本", "作者A", "第一");
        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).hasSize(1);
        verifyNoInteractions(knowledgeIndexTaskExecutor, knowledgeIndexJobExecutor);
    }

    @Test
    void shouldLookupByCategoryWhenBoardCodeIsMissing() {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 101L, "第一本", "作者A", "第一");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setCategory("都市脑洞");
        request.setRankNo(1);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getBoardCode()).isEqualTo("urban-brain");
        assertThat(results.get(0).getBookName()).isEqualTo("第一本");
    }

    @Test
    void shouldTreatMaleNewAsMaleChannelWhenBoardDescriptionIsNewBookBoard() {
        insertRankBoardWithSnapshot("fanqie", "male", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male", "urban-brain", 1, 101L, "入伍两次！我被原部队拉进黑名单", "朝朝和", "退伍入伍都市脑洞");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setCategory("都市脑洞");
        request.setRankNo(1);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getBookName()).isEqualTo("入伍两次！我被原部队拉进黑名单");
        assertThat(results.get(0).getSourceLabel()).contains("男频新书榜").contains("都市脑洞").contains("#1");
    }

    @Test
    void shouldNotMixOtherMaleBoardsWhenResolvingMaleNewAlias() {
        LocalDateTime now = LocalDateTime.now();
        insertRankBoard(1L, "fanqie", "male", "urban-new", "都市脑洞", "男频新书榜");
        insertRankBoardSnapshotForBoard(10L, 1L, now);
        insertRankWithCategory(10L, "都市脑洞", "male", "urban-new", 1, 101L, "新书榜第一", "作者A", "新书榜简介");
        insertRankBoard(2L, "fanqie", "male", "urban-read", "都市脑洞", "男频畅销榜");
        insertRankBoardSnapshotForBoard(20L, 2L, now);
        insertRankWithCategory(20L, "都市脑洞", "male", "urban-read", 1, 201L, "畅销榜第一", "作者B", "畅销榜简介");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setCategory("都市脑洞");
        request.setLimit(10);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName)
            .containsExactly("新书榜第一");
        assertThat(results).extracting(RankLookupResultVO::getBoardCode)
            .containsOnly("urban-new");
    }

    @Test
    void shouldUseLatestBoardSnapshotWithoutMixingOlderCategoryRows() {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board", 10L, LocalDateTime.now().minusDays(1));
        insertRankWithCategory(10L, "legacy-category", "male-new", "urban-brain", 1, 101L, "Old Top One", "Author Old", "Old intro");
        insertRankBoardSnapshot(11L, LocalDateTime.now());
        insertRankWithCategory(11L, "Urban Brain", "male-new", "urban-brain", 1, 201L, "New Top One", "Author New", "New intro");
        insertRankWithCategory(11L, "Urban Brain", "male-new", "urban-brain", 2, 202L, "New Top Two", "Author Two", "New intro two");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setLimit(10);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName)
            .containsExactly("New Top One", "New Top Two");
    }

    @Test
    void shouldLookupRankSnapshotsInsideRequestedTimeWindow() {
        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 21, 10, 0);
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board", 10L, baseTime.minusDays(45));
        insertRankWithCategory(10L, "Urban Brain", "male-new", "urban-brain", 1, 101L, "Too Old Top One", "Author Old", "Too old intro");
        insertRankBoardSnapshot(11L, baseTime.minusDays(12));
        insertRankWithCategory(11L, "Urban Brain", "male-new", "urban-brain", 1, 201L, "Recent Top One", "Author Recent", "Recent intro");
        insertRankBoardSnapshot(12L, baseTime);
        insertRankWithCategory(12L, "Urban Brain", "male-new", "urban-brain", 1, 301L, "Latest Top One", "Author Latest", "Latest intro");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setFreshness("time_window");
        request.setAllowHistorical(true);
        request.setTimeWindowDays(30);
        request.setLimit(10);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName)
            .containsExactly("Latest Top One", "Recent Top One");
        assertThat(results).extracting(RankLookupResultVO::getSnapshotId)
            .containsExactly(12L, 11L);
    }

    @Test
    void shouldLookupRankSnapshotsInsideRequestedCalendarWeek() {
        insertRankBoardWithSnapshot(
            "fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board",
            10L, LocalDateTime.of(2026, 8, 2, 10, 0)
        );
        insertRankWithCategory(10L, "Urban Brain", "male-new", "urban-brain", 1, 101L, "Before Week", "Author A", "Before");
        insertRankBoardSnapshot(11L, LocalDateTime.of(2026, 8, 5, 10, 0));
        insertRankWithCategory(11L, "Urban Brain", "male-new", "urban-brain", 1, 201L, "Inside Week", "Author B", "Inside");
        insertRankBoardSnapshot(12L, LocalDateTime.of(2026, 8, 9, 23, 59));
        insertRankWithCategory(12L, "Urban Brain", "male-new", "urban-brain", 1, 301L, "Week End", "Author C", "Inside");
        insertRankBoardSnapshot(13L, LocalDateTime.of(2026, 8, 10, 0, 1));
        insertRankWithCategory(13L, "Urban Brain", "male-new", "urban-brain", 1, 401L, "After Week", "Author D", "After");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setFreshness("time_window");
        request.setAllowHistorical(true);
        request.setSnapshotStartDate(java.time.LocalDate.of(2026, 8, 3));
        request.setSnapshotEndDate(java.time.LocalDate.of(2026, 8, 9));
        request.setLimit(10);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName)
            .containsExactly("Week End", "Inside Week");
        assertThat(results).extracting(RankLookupResultVO::getSnapshotId)
            .containsExactly(12L, 11L);
    }

    @Test
    void shouldBalanceHistoricalRangeAcrossEarliestAndLatestAvailableDays() {
        insertRankBoardWithSnapshot(
            "fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board",
            10L, LocalDateTime.of(2026, 8, 3, 10, 0)
        );
        insertRankWithCategory(10L, "Urban Brain", "male-new", "urban-brain", 1, 101L, "Week Start", "Author A", "Start");
        insertRankBoardSnapshot(11L, LocalDateTime.of(2026, 8, 9, 10, 0));
        insertRankWithCategory(11L, "Urban Brain", "male-new", "urban-brain", 1, 201L, "Week End A", "Author B", "End A");
        insertRankBoard(2L, "fanqie", "female-new", "fantasy", "Fantasy", "Female new book board");
        insertRankBoardSnapshotForBoard(12L, 2L, LocalDateTime.of(2026, 8, 9, 11, 0));
        insertRankWithCategory(12L, "Fantasy", "female-new", "fantasy", 1, 301L, "Week End B", "Author C", "End B");

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setAllowHistorical(true);
        request.setSnapshotStartDate(LocalDate.of(2026, 8, 3));
        request.setSnapshotEndDate(LocalDate.of(2026, 8, 9));
        request.setLimit(2);

        List<RankLookupResultVO> results = rankToolService.lookupRank(request);

        assertThat(results).extracting(RankLookupResultVO::getBookName)
            .containsExactly("Week End B", "Week Start");
        assertThat(results).extracting(result -> result.getSnapshotTime().toLocalDate())
            .containsExactly(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 3));
    }

    @Test
    void shouldRejectIncompleteOrReversedSnapshotDateRanges() {
        RankLookupRequest incomplete = new RankLookupRequest();
        incomplete.setPlatform("fanqie");
        incomplete.setAllowHistorical(true);
        incomplete.setSnapshotStartDate(LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() -> rankToolService.lookupRank(incomplete))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provided together");

        RankLookupRequest reversed = new RankLookupRequest();
        reversed.setPlatform("fanqie");
        reversed.setAllowHistorical(true);
        reversed.setSnapshotStartDate(LocalDate.of(2026, 8, 9));
        reversed.setSnapshotEndDate(LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() -> rankToolService.lookupRank(reversed))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be after");
    }

    @Test
    void shouldRejectSnapshotDateRangeWithoutHistoricalPermission() {
        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setAllowHistorical(false);
        request.setSnapshotStartDate(LocalDate.of(2026, 8, 3));
        request.setSnapshotEndDate(LocalDate.of(2026, 8, 9));

        assertThatThrownBy(() -> rankToolService.lookupRank(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires historical lookup");
    }

    @Test
    void shouldLookupProductionNumericBoardQuicklyWhenRankCategoryStoresCompositeCode() {
        insertRankBoard(1L, "fanqie", "male-new", "262", "Urban Brain", "Male new book board");
        LocalDateTime baseTime = LocalDateTime.now().minusDays(2);
        insertRankBoardSnapshotForBoard(1001L, 1L, baseTime);
        insertRankBoardSnapshotForBoard(1002L, 1L, baseTime.plusDays(1));
        insertRankRows(1001L, "fanqie", "male-new:262", "male-new", "262", 50_000L, "Old Target", 30);
        insertRankRows(1002L, "fanqie", "male-new:262", "male-new", "262", 51_000L, "Target", 30);
        for (long board = 2; board <= 81; board++) {
            String boardCode = "irrelevant-" + board;
            insertRankBoard(board, "fanqie", "male-read", boardCode, "Irrelevant " + board, "Male read board");
            for (int snapshotIndex = 0; snapshotIndex < 3; snapshotIndex++) {
                long snapshotId = board * 10_000 + snapshotIndex;
                insertRankBoardSnapshotForBoard(snapshotId, board, baseTime.plusHours(snapshotIndex));
                insertRankRows(snapshotId, "fanqie", "male-read:" + boardCode, "male-read", boardCode, board * 100_000 + snapshotIndex * 1_000, "Irrelevant", 30);
            }
        }

        RankLookupRequest request = new RankLookupRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setCategory("Urban Brain");
        request.setLimit(10);

        List<RankLookupResultVO> results = assertTimeoutPreemptively(
            Duration.ofMillis(250),
            () -> rankToolService.lookupRank(request)
        );

        assertThat(results).hasSize(10);
        assertThat(results.get(0).getBookName()).isEqualTo("Target 1");
        assertThat(results.get(0).getBoardCode()).isEqualTo("262");
        assertThat(results.get(0).getCategory()).isEqualTo("male-new:262");
    }

    private void insertRankBoardWithSnapshot(String platform,
                                             String channelCode,
                                             String boardCode,
                                             String boardName,
                                             String description,
                                             long snapshotId) {
        insertRankBoardWithSnapshot(platform, channelCode, boardCode, boardName, description, snapshotId, LocalDateTime.now());
    }

    private void insertRankBoardWithSnapshot(String platform,
                                             String channelCode,
                                             String boardCode,
                                             String boardName,
                                             String description,
                                             long snapshotId,
                                             LocalDateTime snapshotTime) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO rank_board(id, platform, channel_code, board_code, board_name, description, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            platform,
            channelCode,
            boardCode,
            boardName,
            description,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        insertRankBoardSnapshot(snapshotId, snapshotTime);
    }

    private void insertRankBoard(long boardId,
                                 String platform,
                                 String channelCode,
                                 String boardCode,
                                 String boardName,
                                 String description) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO rank_board(id, platform, channel_code, board_code, board_name, description, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            boardId,
            platform,
            channelCode,
            boardCode,
            boardName,
            description,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private void insertRankBoardSnapshot(long snapshotId, LocalDateTime snapshotTime) {
        insertRankBoardSnapshotForBoard(snapshotId, 1L, snapshotTime);
    }

    private void insertRankBoardSnapshotForBoard(long snapshotId, long boardId, LocalDateTime snapshotTime) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO rank_snapshot(id, rank_board_id, snapshot_time, record_count, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            snapshotId,
            boardId,
            Timestamp.valueOf(snapshotTime),
            30,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private void insertRankRows(long snapshotId,
                                String platform,
                                String category,
                                String channelCode,
                                String boardCode,
                                long bookIdBase,
                                String bookNamePrefix,
                                int count) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> args = new ArrayList<>();
        for (int rankNo = 1; rankNo <= count; rankNo++) {
            long bookId = bookIdBase + rankNo;
            args.add(new Object[] {
                platform,
                category,
                channelCode,
                boardCode,
                snapshotId,
                rankNo,
                bookId,
                bookNamePrefix + " " + rankNo,
                "https://fanqienovel.com/page/" + bookId,
                "Author " + rankNo,
                "Intro " + rankNo,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                0
            });
        }
        jdbcTemplate.batchUpdate(
            """
                INSERT INTO crawl_rank(platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            args
        );
    }

    private void insertRankWithCategory(long snapshotId,
                                        String category,
                                        String channelCode,
                                        String boardCode,
                                        int rankNo,
                                        long bookId,
                                        String bookName,
                                        String author,
                                        String intro) {
        LocalDateTime now = LocalDateTime.now();
        Integer existingBookCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crawl_book WHERE id = ?",
            Integer.class,
            bookId
        );
        if (existingBookCount == null || existingBookCount == 0) {
            jdbcTemplate.update(
                """
                    INSERT INTO crawl_book(id, platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                bookId,
                "fanqie",
                String.valueOf(bookId),
                bookName,
                author,
                intro,
                "https://fanqienovel.com/page/" + bookId,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                0
            );
        }
        jdbcTemplate.update(
            """
                INSERT INTO crawl_rank(platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "fanqie",
            category,
            channelCode,
            boardCode,
            snapshotId,
            rankNo,
            bookId,
            bookName,
            "https://fanqienovel.com/page/" + bookId,
            author,
            intro,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private void insertRank(long snapshotId,
                            String channelCode,
                            String boardCode,
                            int rankNo,
                            long bookId,
                            String bookName,
                            String author,
                            String intro) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO crawl_book(id, platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            bookId,
            "fanqie",
            String.valueOf(bookId),
            bookName,
            author,
            intro,
            "https://fanqienovel.com/page/" + bookId,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        jdbcTemplate.update(
            """
                INSERT INTO crawl_rank(platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "fanqie",
            "都市脑洞",
            channelCode,
            boardCode,
            snapshotId,
            rankNo,
            bookId,
            bookName,
            "https://fanqienovel.com/page/" + bookId,
            author,
            intro,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }
}
