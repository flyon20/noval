package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.vo.StoryGraphResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeStoryGraphServiceTest {

    @Test
    void shouldTraverseEvidenceBackedEdgesInBothDirectionsWithoutCycles() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Long generationId = activeGeneration(jdbc);
        Long chapterId = activeChapter(jdbc);
        KnowledgeStoryGraphService service = new KnowledgeStoryGraphService(jdbc);
        Long alpha = service.upsertNode(node(generationId, chapterId, "PERSON", "alpha", "alpha"));
        Long beta = service.upsertNode(node(generationId, chapterId, "PERSON", "beta", "beta"));
        Long gamma = service.upsertNode(node(generationId, chapterId, "PERSON", "gamma", "gamma"));
        service.upsertRelation(edge(generationId, alpha, beta, chapterId, false));
        service.upsertRelation(edge(generationId, beta, gamma, chapterId, false));
        service.upsertRelation(edge(generationId, gamma, alpha, chapterId, false));

        StoryGraphResultVO result = service.traverse(7L, 900L, 1L, List.of("beta"), false);

        assertThat(result.getNodes()).extracting(item -> item.get("nodeId")).contains(alpha, beta, gamma);
        assertThat(result.getEdges()).hasSize(3);
        assertThat(result.getPaths()).allSatisfy(path -> {
            @SuppressWarnings("unchecked")
            List<Long> nodeIds = (List<Long>) path.get("nodeIds");
            assertThat(nodeIds).doesNotHaveDuplicates();
        });
    }

    @Test
    void shouldRejectEdgesWithoutChapterEvidenceAndHideRetiredGeneration() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Long generationId = activeGeneration(jdbc);
        Long chapterId = activeChapter(jdbc);
        KnowledgeStoryGraphService service = new KnowledgeStoryGraphService(jdbc);
        Long alpha = service.upsertNode(node(generationId, chapterId, "PERSON", "alpha", "alpha"));
        Long beta = service.upsertNode(node(generationId, chapterId, "PERSON", "beta", "beta"));

        assertThatThrownBy(() -> service.upsertRelation(new KnowledgeStoryGraphService.EdgeInput(
            7L, 900L, 1L, generationId, alpha, beta, "KNOWS", "character", null,
            null, null, 1, 1, 0.9d, true
        ))).isInstanceOf(BusinessException.class);

        jdbc.update("update ai_project_ingest_generation set status = 'RETIRED' where generation_id = ?", generationId);
        StoryGraphResultVO hidden = service.traverse(7L, 900L, 1L, List.of("alpha"), true);
        assertThat(hidden.getNodes()).isEmpty();
        assertThat(hidden.getEdges()).isEmpty();
        StoryGraphResultVO snapshot = service.snapshotForWork(7L, 900L, 1L, 20);
        assertThat(snapshot.getNodes()).isEmpty();
        assertThat(snapshot.getEdges()).isEmpty();
    }

    private KnowledgeStoryGraphService.NodeInput node(Long generationId, Long chapterId, String type, String key, String name) {
        return new KnowledgeStoryGraphService.NodeInput(7L, 900L, 1L, generationId, chapterId, type, key, name, List.of(name), 0.9d);
    }

    private KnowledgeStoryGraphService.EdgeInput edge(Long generationId, Long from, Long to, Long chapterId, boolean symmetric) {
        return new KnowledgeStoryGraphService.EdgeInput(
            7L, 900L, 1L, generationId, from, to, "KNOWS", "character", chapterId,
            null, "chapter evidence", 1, 1, 0.9d, symmetric
        );
    }

    private JdbcTemplate jdbc() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:story_graph_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table ai_project(project_id bigint primary key, user_id bigint not null, name varchar(200), description varchar(500), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.update("insert into ai_project(project_id, user_id, name, description, status) values(900, 7, 'project', 'test', 'ACTIVE')");
        runScript(jdbc, resource("sql/phase16-project-knowledge-rag-h2.sql"));
        jdbc.update("insert into ai_project_work(user_id, project_id, title, status) values(7, 900, 'work', 'ACTIVE')");
        jdbc.update("insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status) values(7, 900, 1, 1, 'chapter', 'chapter text', 'hash', 12, 'upload', 1, 'ACTIVE')");
        runScript(jdbc, resource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        return jdbc;
    }

    private Long activeGeneration(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select active_generation_id from ai_project_chapter_head where user_id = 7 and project_id = 900 and work_id = 1", Long.class);
    }

    private Long activeChapter(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select active_chapter_id from ai_project_chapter_head where user_id = 7 and project_id = 900 and work_id = 1", Long.class);
    }

    private Path resource(String path) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("src/test/resources").resolve(path),
            Path.of("backend/src/test/resources").resolve(path),
            Path.of("..", "src/test/resources").resolve(path)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        var url = getClass().getClassLoader().getResource(path);
        if (url != null) {
            return Path.of(url.toURI());
        }
        throw new java.nio.file.NoSuchFileException(path);
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        for (String stmt : sql.split(";")) {
            String executable = stmt.lines().filter(line -> !line.trim().startsWith("--")).reduce("", (left, right) -> left + "\n" + right).trim();
            if (!executable.isEmpty()) {
                jdbc.execute(executable);
            }
        }
    }
}
