package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectIngestSubmitRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectKnowledgeFeedbackRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectFeedbackService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectKnowledgeFeedbackVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import com.novelanalyzer.modules.knowledge.vo.StoryGraphResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 51 Task 14 acceptance matrix proofs runnable without Docker Desktop/MySQL 8.
 * Native MySQL first/second-run upgrade and 500-chapter host pressure remain deployment gates.
 */
class Phase51AcceptanceMatrixTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldFenceStaleWorkerMarkReadyAndAllowOwnerReady() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest req = new ProjectIngestSubmitRequest();
        req.setChapterNo(1);
        req.setContent("lease fencing body");
        ProjectIngestJobVO job = service.submit(900L, workId, req);

        KnowledgeProjectIngestService.ClaimResult claim = service.claimJob(
            job.getIngestJobId(), "worker-a", Duration.ofSeconds(30)
        );
        assertThat(claim).isNotNull();
        long token = claim.fencingToken();

        // Stale fencing token cannot mark READY.
        service.markJobReady(job.getIngestJobId(), "worker-stale", token - 1);
        ProjectIngestJobVO afterStale = service.getJob(900L, job.getIngestJobId());
        assertThat(afterStale.getStatus()).isNotEqualTo(KnowledgeProjectIngestService.JOB_READY);

        // Matching owner+token can mark READY.
        service.markJobReady(job.getIngestJobId(), "worker-a", token);
        ProjectIngestJobVO afterOwner = service.getJob(900L, job.getIngestJobId());
        assertThat(afterOwner.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_READY);
    }

    @Test
    void shouldKeepFeedbackPendingOnlyAndBoundStoryGraphSnapshot() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService ingest = service(jdbc);
        KnowledgeProjectFeedbackService feedback = new KnowledgeProjectFeedbackService(
            jdbc, new KnowledgeProjectService(jdbc)
        );
        KnowledgeStoryGraphService graph = new KnowledgeStoryGraphService(jdbc);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest req = new ProjectIngestSubmitRequest();
        req.setChapterNo(2);
        req.setContent("graph seed body about Lin Zhou in Qingyun city");
        ProjectIngestJobVO job = ingest.submit(900L, workId, req);
        executor(jdbc, ingest).execute(job.getIngestJobId());

        ProjectKnowledgeFeedbackRequest fb = new ProjectKnowledgeFeedbackRequest();
        fb.setWorkId(workId);
        fb.setFeedbackType("FIX_TIMELINE");
        fb.setTargetType("CHARACTER");
        fb.setTargetKey("lin-zhou");
        fb.setNewValueJson("{\"name\":\"林舟\"}");
        fb.setNotes("character name should be 林舟");
        ProjectKnowledgeFeedbackVO saved = feedback.submit(900L, fb);
        assertThat(saved.getReviewStatus()).isEqualTo(KnowledgeProjectFeedbackService.STATUS_PENDING);

        StoryGraphResultVO snapshot = graph.snapshotForWork(7L, 900L, workId, 5);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getNodes().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void shouldIngestTwentyChaptersWithoutDuplicateActiveGenerations() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        KnowledgeProjectIngestJobExecutor executor = executor(jdbc, service);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        int chapters = 20;
        for (int i = 1; i <= chapters; i++) {
            ProjectIngestSubmitRequest req = new ProjectIngestSubmitRequest();
            req.setChapterNo(i);
            req.setTitle("ch-" + i);
            req.setContent("chapter body " + i + " " + "content ".repeat(40));
            req.setIdempotencyKey("load-" + i);
            ProjectIngestJobVO job = service.submit(900L, workId, req);
            executor.execute(job.getIngestJobId());
            ProjectIngestJobVO ready = service.getJob(900L, job.getIngestJobId());
            assertThat(ready.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_READY);
        }

        Integer multiActive = jdbc.queryForObject(
            """
            select count(*) from (
              select chapter_no from ai_project_ingest_generation
              where work_id = ? and status = 'ACTIVE'
              group by chapter_no having count(*) > 1
            ) x
            """,
            Integer.class,
            workId
        );
        assertThat(multiActive).isEqualTo(0);
    }

    @Test
    void shouldRejectCrossUserIngestJobAccess() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);
        ProjectIngestSubmitRequest req = new ProjectIngestSubmitRequest();
        req.setChapterNo(9);
        req.setContent("private chapter");
        ProjectIngestJobVO job = service.submit(900L, workId, req);

        AuthUserHolder.set(AuthUser.of(99L, "intruder", Set.of("USER")));
        assertThatThrownBy(() -> service.getJob(900L, job.getIngestJobId()))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private KnowledgeProjectIngestJobExecutor executor(JdbcTemplate jdbc, KnowledgeProjectIngestService service) {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        return new KnowledgeProjectIngestJobExecutor(
            service,
            new KnowledgeProjectWorkService(
                jdbc, new KnowledgeProjectService(jdbc), embeddingClient, qdrantClient),
            new KnowledgeStoryGraphService(jdbc),
            new KnowledgeProperties(),
            jdbc
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private KnowledgeProjectIngestService service(JdbcTemplate jdbc) {
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbc);
        KnowledgeProjectWorkService workService = new KnowledgeProjectWorkService(jdbc, projectService);
        ObjectProvider empty = new ObjectProvider() {
            @Override public Object getObject() { return null; }
            @Override public Object getObject(Object... args) { return null; }
            @Override public Object getIfAvailable() { return null; }
            @Override public Object getIfUnique() { return null; }
        };
        return new KnowledgeProjectIngestService(
            jdbc, projectService, workService, new KnowledgeProperties(), empty, empty, empty
        );
    }

    private Long createWork(JdbcTemplate jdbc) {
        KnowledgeProjectWorkService workService = new KnowledgeProjectWorkService(jdbc, new KnowledgeProjectService(jdbc));
        ProjectWorkRequest request = new ProjectWorkRequest();
        request.setTitle("acceptance-work");
        ProjectWorkVO work = workService.createWork(900L, request);
        return work.getWorkId();
    }

    private JdbcTemplate jdbc() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:phase51accept_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table ai_project(project_id bigint primary key, user_id bigint not null, name varchar(200), description varchar(500), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.update("insert into ai_project(project_id, user_id, name, description, status) values(900, 7, 'p', 'd', 'ACTIVE')");
        runScript(jdbc, resolveResource("sql/phase16-project-knowledge-rag-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase26-project-retrieval-eval-observability-h2.sql"));
        return jdbc;
    }

    private Path resolveResource(String classpathRelative) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("src/test/resources").resolve(classpathRelative),
            Path.of("backend/src/test/resources").resolve(classpathRelative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        var url = getClass().getClassLoader().getResource(classpathRelative.replace("\\", "/"));
        if (url != null) {
            return Path.of(url.toURI());
        }
        throw new java.nio.file.NoSuchFileException(classpathRelative);
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == '\uFEFF') {
            sql = sql.substring(1);
        }
        for (String stmt : sql.split(";")) {
            StringBuilder cleaned = new StringBuilder();
            for (String line : stmt.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                cleaned.append(line).append('\n');
            }
            String executable = cleaned.toString().trim();
            if (executable.isEmpty()) {
                continue;
            }
            try {
                jdbc.execute(executable);
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (!(msg.contains("already exists") || msg.contains("duplicate"))) {
                    throw ex;
                }
            }
        }
    }
}
