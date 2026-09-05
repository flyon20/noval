package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectChapterImportRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeProjectWorkService {

    private static final int SCENE_TARGET_CHARS = 900;
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\R\\s*\\R+");
    private static final Runnable NO_OP_CHECKPOINT = () -> {
    };
    private static final String EXECUTION_FENCE_FROM = """
        from ai_project_ingest_job j
        where j.ingest_job_id = ? and j.generation_id = ?
          and j.status = 'PARSING' and j.stage = 'PARSING'
          and j.lease_owner = ? and j.fencing_token = ?
          and j.lease_expires_at >= current_timestamp
        """;
    private static final String EXECUTION_FENCE_EXISTS = """
        exists (
          select 1 from ai_project_ingest_job j
          where j.ingest_job_id = ? and j.generation_id = ?
            and j.status = 'PARSING' and j.stage = 'PARSING'
            and j.lease_owner = ? and j.fencing_token = ?
            and j.lease_expires_at >= current_timestamp
        )
        """;

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService projectService;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;

    @Autowired
    public KnowledgeProjectWorkService(JdbcTemplate jdbcTemplate,
                                       KnowledgeProjectService projectService,
                                       EmbeddingClient embeddingClient,
                                       QdrantClient qdrantClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
    }

    public KnowledgeProjectWorkService(JdbcTemplate jdbcTemplate, KnowledgeProjectService projectService) {
        this(jdbcTemplate, projectService, null, null);
    }

    public ProjectWorkVO createWork(Long projectId, ProjectWorkRequest request) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        String title = requireText(request == null ? null : request.getTitle(), "work title is required", 200);
        String alias = trimToNull(request == null ? null : request.getAlias(), 500);
        String genre = trimToNull(request == null ? null : request.getGenre(), 80);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    insert into ai_project_work(user_id, project_id, title, alias, genre, status)
                    values(?, ?, ?, ?, ?, 'ACTIVE')
                    """,
                new String[]{"work_id"}
            );
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setString(3, title);
            statement.setString(4, alias);
            statement.setString(5, genre);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "work id missing");
        }
        return findOwnedWorkPublic(projectId, key.longValue(), user.getUserId());
    }

    @Transactional
    public List<ProjectWorkVO> listWorks(Long projectId) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        List<ProjectWorkVO> works = queryWorks(projectId, user.getUserId());
        if (works.isEmpty()) {
            ensureDefaultWork(projectId, null);
            works = queryWorks(projectId, user.getUserId());
        }
        return works;
    }

    public List<ProjectWorkVO> listMyWorkLibrary() {
        AuthUser user = requireUser();
        return jdbcTemplate.query(
            """
                select w.work_id, w.user_id, w.project_id, w.title, w.alias, w.genre, w.status,
                    w.created_at, w.updated_at
                from ai_project_work w
                join ai_project p on p.project_id = w.project_id and p.user_id = w.user_id
                where w.user_id = ? and w.status <> 'ARCHIVED' and p.status <> 'ARCHIVED'
                order by w.updated_at desc, w.work_id desc
            """,
            workMapper(),
            user.getUserId()
        );
    }

    @Transactional
    public ProjectWorkVO ensureDefaultWork(Long projectId, String preferredTitle) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        String projectName = jdbcTemplate.queryForObject(
            "select name from ai_project where project_id = ? and user_id = ? for update",
            String.class,
            projectId,
            user.getUserId()
        );
        List<ProjectWorkVO> existing = queryWorks(projectId, user.getUserId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String title = trimToNull(preferredTitle, 200);
        if (title == null) {
            title = projectName;
        }
        if (title == null || title.isBlank()) {
            title = "未命名作品";
        }
        jdbcTemplate.update(
            "insert into ai_project_work(user_id, project_id, title, alias, genre, status) values(?, ?, ?, null, null, 'ACTIVE')",
            user.getUserId(),
            projectId,
            title
        );
        List<ProjectWorkVO> created = queryWorks(projectId, user.getUserId());
        if (created.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "default work creation failed");
        }
        return created.get(0);
    }

    private List<ProjectWorkVO> queryWorks(Long projectId, Long userId) {
        return jdbcTemplate.query(
            """
                select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                from ai_project_work
                where project_id = ? and user_id = ? and status <> 'ARCHIVED'
                order by updated_at desc, work_id desc
            """,
            workMapper(),
            projectId,
            userId
        );
    }

    public ProjectChapterVO importChapter(Long projectId, Long workId, ProjectChapterImportRequest request) {
        AuthUser user = requireUser();
        ProjectWorkVO work = findOwnedWorkPublic(projectId, workId, user.getUserId());
        int chapterNo = request == null || request.getChapterNo() == null ? 0 : request.getChapterNo();
        if (chapterNo <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter no is required");
        }
        String content = requireText(request.getContent(), "chapter content is required", Integer.MAX_VALUE);
        String hash = sha256(content);
        List<ProjectChapterVO> existing = jdbcTemplate.query(
            """
                select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                    word_count, source_type, version, status, created_at, updated_at
                from ai_project_chapter
                where work_id = ? and chapter_no = ? and content_hash = ? and status <> 'ARCHIVED'
                order by chapter_id asc
                """,
            chapterMapperPublic(),
            workId,
            chapterNo,
            hash
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        int nextVersion = nextChapterVersion(workId, chapterNo);
        String sourceType = trimToNull(request.getSourceType(), 40);
        if (sourceType == null) {
            sourceType = "upload";
        }
        String title = trimToNull(request.getTitle(), 200);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String finalSourceType = sourceType;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content,
                        content_hash, word_count, source_type, version, status)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """,
                new String[]{"chapter_id"}
            );
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setLong(3, work.getWorkId());
            statement.setInt(4, chapterNo);
            statement.setString(5, title);
            statement.setString(6, content);
            statement.setString(7, hash);
            statement.setInt(8, content.length());
            statement.setString(9, finalSourceType);
            statement.setInt(10, nextVersion);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chapter id missing");
        }
        ProjectChapterVO chapter = findOwnedChapter(projectId, workId, key.longValue(), user.getUserId());
        // Task 9: HTTP import no longer materializes parse/index artifacts synchronously.
        return chapter;
    }

    public List<ProjectChapterVO> listChapters(Long projectId, Long workId) {
        AuthUser user = requireUser();
        findOwnedWorkPublic(projectId, workId, user.getUserId());
        return listChaptersForUser(user.getUserId(), projectId, workId, null, 200);
    }

    public Map<String, Object> resolveWork(Long userId, Long projectId, Long workId, String query, int limit) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "user id is required");
        }
        if (projectId != null && workId != null) {
            ProjectWorkVO work = findOwnedWorkPublic(projectId, workId, userId);
            return resolvedWork(work);
        }
        String normalizedQuery = trimToNull(query, 200);
        if (normalizedQuery == null) {
            int safeLimit = Math.max(1, Math.min(limit, 20));
            List<ProjectWorkVO> works;
            if (projectId == null) {
                works = jdbcTemplate.query(
                    """
                        select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                        from ai_project_work
                        where user_id = ? and status <> 'ARCHIVED'
                        order by updated_at desc, work_id desc
                        limit ?
                        """,
                    workMapper(),
                    userId,
                    safeLimit
                );
            } else {
                projectService.ensureOwned(projectId, userId);
                works = jdbcTemplate.query(
                    """
                        select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                        from ai_project_work
                        where user_id = ? and project_id = ? and status <> 'ARCHIVED'
                        order by updated_at desc, work_id desc
                        limit ?
                        """,
                    workMapper(),
                    userId,
                    projectId,
                    safeLimit
                );
            }
            if (works.isEmpty()) {
                return mapRow("status", "not_found", "candidates", List.of());
            }
            if (works.size() == 1) {
                return resolvedWork(works.get(0));
            }
            return mapRow("status", "ambiguous", "candidates", works.stream().map(this::workCandidate).toList());
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        String like = "%" + normalizedQuery + "%";
        List<ProjectWorkVO> works;
        if (projectId == null) {
            works = jdbcTemplate.query(
                """
                    select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                    from ai_project_work
                    where user_id = ? and status <> 'ARCHIVED'
                      and (title = ? or alias = ? or title like ? or alias like ?)
                    order by case when title = ? or alias = ? then 0 else 1 end,
                        updated_at desc, work_id desc
                    limit ?
                    """,
                workMapper(),
                userId,
                normalizedQuery,
                normalizedQuery,
                like,
                like,
                normalizedQuery,
                normalizedQuery,
                safeLimit
            );
        } else {
            projectService.ensureOwned(projectId, userId);
            works = jdbcTemplate.query(
                """
                    select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                    from ai_project_work
                    where user_id = ? and project_id = ? and status <> 'ARCHIVED'
                      and (title = ? or alias = ? or title like ? or alias like ?)
                    order by case when title = ? or alias = ? then 0 else 1 end,
                        updated_at desc, work_id desc
                    limit ?
                    """,
                workMapper(),
                userId,
                projectId,
                normalizedQuery,
                normalizedQuery,
                like,
                like,
                normalizedQuery,
                normalizedQuery,
                safeLimit
            );
        }
        if (works.isEmpty()) {
            return mapRow("status", "not_found", "candidates", List.of());
        }
        List<ProjectWorkVO> exact = works.stream()
            .filter(work -> normalizedQuery.equalsIgnoreCase(work.getTitle())
                || normalizedQuery.equalsIgnoreCase(work.getAlias()))
            .toList();
        if (exact.size() == 1) {
            return resolvedWork(exact.get(0));
        }
        if (works.size() == 1) {
            return resolvedWork(works.get(0));
        }
        return mapRow(
            "status", "ambiguous",
            "candidates", works.stream().map(this::workCandidate).toList()
        );
    }

    public List<Map<String, Object>> listForeshadowings(Long userId,
                                                        Long projectId,
                                                        Long workId,
                                                        String status,
                                                        int limit) {
        findOwnedWorkPublic(projectId, workId, userId);
        String normalizedStatus = trimToNull(status, 30);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (normalizedStatus == null) {
            return jdbcTemplate.query(
                """
                    select f.foreshadowing_id, f.project_id, f.work_id, f.title, f.content, f.status,
                        f.planted_chapter_no, f.paid_off_chapter_no, f.importance, f.confidence
                    from ai_project_foreshadowing f
                    join ai_project_ingest_generation g
                      on g.generation_id = f.generation_id
                     and g.user_id = f.user_id and g.project_id = f.project_id and g.work_id = f.work_id
                     and g.status = 'ACTIVE'
                    join ai_project_chapter_head h
                      on h.user_id = f.user_id and h.project_id = f.project_id and h.work_id = f.work_id
                     and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                     and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                    where f.user_id = ? and f.project_id = ? and f.work_id = ?
                    order by f.planted_chapter_no asc, f.foreshadowing_id asc
                    limit ?
                    """,
                (rs, rowNum) -> mapRow(
                    "foreshadowingId", rs.getLong("foreshadowing_id"),
                    "projectId", rs.getLong("project_id"),
                    "workId", rs.getLong("work_id"),
                    "title", rs.getString("title"),
                    "content", rs.getString("content"),
                    "status", rs.getString("status"),
                    "plantedChapterNo", rs.getObject("planted_chapter_no"),
                    "paidOffChapterNo", rs.getObject("paid_off_chapter_no"),
                    "importance", rs.getString("importance"),
                    "confidence", rs.getObject("confidence")
                ),
                userId, projectId, workId, safeLimit
            );
        }
        return jdbcTemplate.query(
            """
                select f.foreshadowing_id, f.project_id, f.work_id, f.title, f.content, f.status,
                    f.planted_chapter_no, f.paid_off_chapter_no, f.importance, f.confidence
                from ai_project_foreshadowing f
                join ai_project_ingest_generation g
                  on g.generation_id = f.generation_id
                 and g.user_id = f.user_id and g.project_id = f.project_id and g.work_id = f.work_id
                 and g.status = 'ACTIVE'
                join ai_project_chapter_head h
                  on h.user_id = f.user_id and h.project_id = f.project_id and h.work_id = f.work_id
                 and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                 and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                where f.user_id = ? and f.project_id = ? and f.work_id = ? and f.status = ?
                order by f.planted_chapter_no asc, f.foreshadowing_id asc
                limit ?
                """,
            (rs, rowNum) -> mapRow(
                "foreshadowingId", rs.getLong("foreshadowing_id"),
                "projectId", rs.getLong("project_id"),
                "workId", rs.getLong("work_id"),
                "title", rs.getString("title"),
                "content", rs.getString("content"),
                "status", rs.getString("status"),
                "plantedChapterNo", rs.getObject("planted_chapter_no"),
                "paidOffChapterNo", rs.getObject("paid_off_chapter_no"),
                "importance", rs.getString("importance"),
                "confidence", rs.getObject("confidence")
            ),
            userId, projectId, workId, normalizedStatus, safeLimit
        );
    }

    public List<Map<String, Object>> lookupTimeline(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWorkPublic(projectId, workId, userId);
        return queryStructured(
            """
                select t.event_id, t.project_id, t.work_id, t.chapter_no, t.event_order,
                    t.title, t.summary, t.confidence
                from ai_project_timeline_event t
                join ai_project_ingest_generation g
                  on g.generation_id = t.generation_id
                 and g.user_id = t.user_id and g.project_id = t.project_id and g.work_id = t.work_id
                 and g.chapter_id = t.chapter_id and g.status = 'ACTIVE'
                join ai_project_chapter_head h
                  on h.user_id = t.user_id and h.project_id = t.project_id and h.work_id = t.work_id
                 and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                 and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                where t.user_id = ? and t.project_id = ? and t.work_id = ? and t.status = 'ACTIVE'
                  and (? is null or t.title like ? or t.summary like ?)
                order by t.chapter_no asc, t.event_order asc, t.event_id asc
                limit ?
                """,
            userId, projectId, workId, query, limit,
            "eventId", "event_id",
            "chapterNo", "chapter_no",
            "eventOrder", "event_order",
            "title", "title",
            "summary", "summary"
        );
    }

    public List<Map<String, Object>> lookupCharacterStates(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWorkPublic(projectId, workId, userId);
        return queryStructured(
            """
                select s.state_id, s.project_id, s.work_id, s.character_name, s.chapter_no,
                    s.state_summary, s.motivation, s.confidence
                from ai_project_character_state s
                join ai_project_ingest_generation g
                  on g.generation_id = s.generation_id
                 and g.user_id = s.user_id and g.project_id = s.project_id and g.work_id = s.work_id
                 and g.chapter_id = s.chapter_id and g.status = 'ACTIVE'
                join ai_project_chapter_head h
                  on h.user_id = s.user_id and h.project_id = s.project_id and h.work_id = s.work_id
                 and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                 and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                where s.user_id = ? and s.project_id = ? and s.work_id = ? and s.status = 'ACTIVE'
                  and (? is null or s.character_name like ? or s.state_summary like ? or s.motivation like ?)
                order by s.chapter_no asc, s.state_id asc
                limit ?
                """,
            userId, projectId, workId, query, limit,
            "stateId", "state_id",
            "characterName", "character_name",
            "chapterNo", "chapter_no",
            "stateSummary", "state_summary",
            "motivation", "motivation"
        );
    }

    public List<Map<String, Object>> lookupWorldRules(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWorkPublic(projectId, workId, userId);
        return queryStructured(
            """
                select r.rule_id, r.project_id, r.work_id, r.rule_type, r.title,
                    r.content, r.first_chapter_no, r.confidence
                from ai_project_world_rule r
                join ai_project_ingest_generation g
                  on g.generation_id = r.generation_id
                 and g.user_id = r.user_id and g.project_id = r.project_id and g.work_id = r.work_id
                 and g.status = 'ACTIVE'
                join ai_project_chapter_head h
                  on h.user_id = r.user_id and h.project_id = r.project_id and h.work_id = r.work_id
                 and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                 and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                where r.user_id = ? and r.project_id = ? and r.work_id = ? and r.status_proj = 'ACTIVE'
                  and (? is null or r.title like ? or r.content like ? or r.rule_type like ?)
                order by r.first_chapter_no asc, r.rule_id asc
                limit ?
                """,
            userId, projectId, workId, query, limit,
            "ruleId", "rule_id",
            "ruleType", "rule_type",
            "title", "title",
            "content", "content",
            "firstChapterNo", "first_chapter_no"
        );
    }

    private List<ProjectChapterVO> listChaptersForUser(Long userId, Long projectId, Long workId, String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (query == null) {
            return jdbcTemplate.query(
                """
                    select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                        word_count, source_type, version, status, created_at, updated_at
                    from ai_project_chapter
                    where user_id = ? and project_id = ? and work_id = ? and status <> 'ARCHIVED'
                    order by chapter_no asc, version asc, chapter_id asc
                    limit ?
                    """,
                chapterMapperPublic(),
                userId,
                projectId,
                workId,
                safeLimit
            );
        }
        String like = "%" + query + "%";
        return jdbcTemplate.query(
            """
                select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                    word_count, source_type, version, status, created_at, updated_at
                from ai_project_chapter
                where user_id = ? and project_id = ? and work_id = ? and status <> 'ARCHIVED'
                  and (title like ? or content like ?)
                order by chapter_no asc, version asc, chapter_id asc
                limit ?
                """,
            chapterMapperPublic(),
            userId,
            projectId,
            workId,
            like,
            like,
            safeLimit
        );
    }

    private List<Map<String, Object>> queryStructured(String sql,
                                                      Long userId,
                                                      Long projectId,
                                                      Long workId,
                                                      String query,
                                                      int limit,
                                                      String... fieldPairs) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedQuery = trimToNull(query, 200);
        String like = normalizedQuery == null ? null : "%" + normalizedQuery + "%";
        int likeCount = (int) sql.chars().filter(ch -> ch == '?').count() - 4;
        Object[] args = new Object[4 + likeCount];
        args[0] = userId;
        args[1] = projectId;
        args[2] = workId;
        args[3] = normalizedQuery;
        for (int i = 0; i < likeCount - 1; i++) {
            args[4 + i] = like;
        }
        args[args.length - 1] = safeLimit;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = mapRow(
                "projectId", rs.getLong("project_id"),
                "workId", rs.getLong("work_id"),
                "confidence", columnValue(rs, "confidence")
            );
            for (int i = 0; i + 1 < fieldPairs.length; i += 2) {
                row.put(fieldPairs[i], columnValue(rs, fieldPairs[i + 1]));
            }
            return row;
        }, args);
    }

    private Object columnValue(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return value;
    }

    private void upsertProjectVectorChunk(ProjectChapterVO chapter,
                                          Long vectorChunkId,
                                          Long sceneId,
                                          String sourceType,
                                          Long sourceId,
                                          String contentHash,
                                          String qdrantPointId,
                                          String text,
                                          Long generationId,
                                          Integer chapterVersion,
                                          Runnable ownershipCheckpoint) {
        requireVectorRuntime();
        if (text == null || text.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project vector chunk text is required");
        }
        ownershipCheckpoint.run();
        List<Double> vector = embeddingClient.embed(trimForStorage(text, 8000));
        ownershipCheckpoint.run();
        qdrantClient.ensureCollection();
        ownershipCheckpoint.run();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", chapter.getUserId());
        payload.put("project_id", chapter.getProjectId());
        payload.put("work_id", chapter.getWorkId());
        payload.put("chapter_id", chapter.getChapterId());
        payload.put("chapter_no", chapter.getChapterNo());
        putIfNotNull(payload, "generation_id", generationId);
        putIfNotNull(payload, "chapter_version", chapterVersion);
        putIfNotNull(payload, "scene_id", sceneId);
        payload.put("source_type", sourceType);
        putIfNotNull(payload, "source_id", sourceId);
        payload.put("content_hash", contentHash);
        payload.put("visibility", "private");
        payload.put("project_vector_chunk_id", vectorChunkId);
        payload.put("chunk_text_preview", trimForStorage(text, 1000));
        ownershipCheckpoint.run();
        qdrantClient.upsertPoint(qdrantPointId, vector, payload);
        ownershipCheckpoint.run();
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String projectVectorPointId(String seed) {
        return UUID.nameUUIDFromBytes(("project-vector-chunk:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> resolvedWork(ProjectWorkVO work) {
        Map<String, Object> resolved = workCandidate(work);
        resolved.put("status", "resolved");
        return resolved;
    }

    private Map<String, Object> workCandidate(ProjectWorkVO work) {
        return mapRow(
            "workId", work.getWorkId(),
            "projectId", work.getProjectId(),
            "userId", work.getUserId(),
            "title", work.getTitle(),
            "alias", work.getAlias(),
            "genre", work.getGenre(),
            "workStatus", work.getStatus()
        );
    }

    private Map<String, Object> mapRow(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    public ProjectWorkVO findOwnedWorkPublic(Long projectId, Long workId, Long userId) {
        projectService.ensureOwned(projectId, userId);
        List<ProjectWorkVO> works = jdbcTemplate.query(
            """
                select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                from ai_project_work
                where project_id = ? and work_id = ? and user_id = ? and status <> 'ARCHIVED'
                """,
            workMapper(),
            projectId,
            workId,
            userId
        );
        if (works.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "work not found");
        }
        return works.get(0);
    }

    private ProjectChapterVO findOwnedChapter(Long projectId, Long workId, Long chapterId, Long userId) {
        List<ProjectChapterVO> chapters = jdbcTemplate.query(
            """
                select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                    word_count, source_type, version, status, created_at, updated_at
                from ai_project_chapter
                where project_id = ? and work_id = ? and chapter_id = ? and user_id = ?
                """,
            chapterMapperPublic(),
            projectId,
            workId,
            chapterId,
            userId
        );
        if (chapters.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chapter not found");
        }
        return chapters.get(0);
    }

    private int nextChapterVersion(Long workId, int chapterNo) {
        Integer version = jdbcTemplate.queryForObject(
            "select coalesce(max(version), 0) + 1 from ai_project_chapter where work_id = ? and chapter_no = ?",
            Integer.class,
            workId,
            chapterNo
        );
        return version == null ? 1 : version;
    }

    public ArtifactCounts materializeGenerationArtifacts(ProjectChapterVO chapter, Long generationId) {
        return materializeGenerationArtifacts(chapter, generationId, NO_OP_CHECKPOINT);
    }

    public ArtifactCounts materializeGenerationArtifacts(ProjectChapterVO chapter,
                                                          Long generationId,
                                                          Runnable ownershipCheckpoint) {
        return materializeGenerationArtifacts(chapter, generationId, ownershipCheckpoint, null);
    }

    public ArtifactCounts materializeGenerationArtifacts(ProjectChapterVO chapter,
                                                          Long generationId,
                                                          Runnable ownershipCheckpoint,
                                                          ExecutionFence executionFence) {
        if (generationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project ingest generation is required");
        }
        Runnable checkpoint = ownershipCheckpoint == null ? NO_OP_CHECKPOINT : ownershipCheckpoint;
        checkpoint.run();
        resetRebuildableGenerationArtifacts(generationId, executionFence);
        checkpoint.run();
        return createIngestArtifacts(chapter, generationId, checkpoint, executionFence);
    }

    public void cleanupGenerationArtifacts(Long generationId) {
        if (generationId == null) {
            return;
        }
        resetGenerationArtifacts(generationId);
    }

    private void resetGenerationArtifacts(Long generationId) {
        requireQdrantRuntime();
        qdrantClient.ensureCollection();
        qdrantClient.deletePoints(Map.of("generation_id", generationId));
        resetRebuildableGenerationArtifacts(generationId, null);
        jdbcTemplate.update("delete from ai_project_vector_chunk where generation_id = ?", generationId);
        jdbcTemplate.update("delete from ai_project_scene where generation_id = ?", generationId);
    }

    private void resetRebuildableGenerationArtifacts(Long generationId, ExecutionFence executionFence) {
        deleteGenerationRows("ai_project_story_edge", generationId, false, executionFence);
        deleteGenerationRows("ai_project_story_node", generationId, false, executionFence);
        deleteGenerationRows("ai_project_search_document", generationId, false, executionFence);
        deleteGenerationRows("ai_project_extraction_candidate", generationId, true, executionFence);
        deleteGenerationRows("ai_project_character_state", generationId, false, executionFence);
        deleteGenerationRows("ai_project_world_rule", generationId, false, executionFence);
        deleteGenerationRows("ai_project_foreshadowing", generationId, false, executionFence);
        deleteGenerationRows("ai_project_timeline_event", generationId, false, executionFence);
    }

    private void deleteGenerationRows(String table,
                                      Long generationId,
                                      boolean pendingOnly,
                                      ExecutionFence executionFence) {
        String sql = "delete from " + table + " where generation_id = ?"
            + (pendingOnly ? " and status = 'PENDING'" : "");
        if (executionFence == null) {
            jdbcTemplate.update(sql, generationId);
            return;
        }
        jdbcTemplate.update(
            sql + " and " + EXECUTION_FENCE_EXISTS,
            fencedArgs(generationId, executionFence, generationId));
    }

    private void requireVectorRuntime() {
        if (embeddingClient == null || qdrantClient == null) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "project vector runtime unavailable");
        }
    }

    private void requireQdrantRuntime() {
        if (qdrantClient == null) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "project qdrant runtime unavailable");
        }
    }

    private ArtifactCounts createIngestArtifacts(ProjectChapterVO chapter,
                                                  Long generationId,
                                                  Runnable ownershipCheckpoint,
                                                  ExecutionFence executionFence) {
        if (chapter == null || chapter.getChapterId() == null) {
            return new ArtifactCounts(0, 0, 0);
        }
        ownershipCheckpoint.run();
        List<SceneSlice> scenes = splitScenes(chapter.getContent());
        ownershipCheckpoint.run();
        List<Long> sceneIds = new ArrayList<>();
        for (SceneSlice scene : scenes) {
            ownershipCheckpoint.run();
            Long sceneId = findOrInsertScene(chapter, scene, generationId, executionFence);
            sceneIds.add(sceneId);
            ownershipCheckpoint.run();
            insertVectorChunk(
                chapter,
                sceneId,
                "scene",
                sceneId,
                scene.text(),
                "project-" + chapter.getProjectId() + "-work-" + chapter.getWorkId()
                    + "-chapter-" + chapter.getChapterId() + "-scene-" + sceneId,
                generationId,
                ownershipCheckpoint,
                executionFence
            );
        }
        ownershipCheckpoint.run();
        insertVectorChunk(
            chapter,
            null,
            "chapter",
            chapter.getChapterId(),
            chapter.getContent(),
            "project-" + chapter.getProjectId() + "-work-" + chapter.getWorkId()
                + "-chapter-" + chapter.getChapterId() + "-full",
            generationId,
            ownershipCheckpoint,
            executionFence
        );
        int entityCount = 0;
        entityCount += extractWorldRules(chapter, generationId, ownershipCheckpoint, executionFence);
        entityCount += extractForeshadowings(chapter, generationId, ownershipCheckpoint, executionFence);
        entityCount += extractTimelineEvents(chapter, sceneIds, generationId, ownershipCheckpoint, executionFence);
        entityCount += extractCharacterState(chapter, generationId, ownershipCheckpoint, executionFence);
        ownershipCheckpoint.run();
        int vectorCount = scenes.size() + 1;
        return new ArtifactCounts(scenes.size(), vectorCount, entityCount);
    }

    public record ArtifactCounts(int sceneCount, int vectorCount, int entityCount) {
    }

    private boolean hasIngestArtifacts(Long chapterId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_ingest_job where chapter_id = ? and status = 'COMPLETED'",
            Integer.class,
            chapterId
        );
        return count != null && count > 0;
    }

    private List<SceneSlice> splitScenes(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return List.of(new SceneSlice(1, 0, 0, ""));
        }
        List<SceneSlice> scenes = new ArrayList<>();
        int searchFrom = 0;
        for (String paragraph : PARAGRAPH_SPLIT.split(text)) {
            String sceneText = paragraph.trim();
            if (sceneText.isEmpty()) {
                continue;
            }
            int start = Math.max(0, text.indexOf(sceneText, searchFrom));
            int end = Math.min(text.length(), start + sceneText.length());
            searchFrom = end;
            addSceneSlices(scenes, sceneText, start);
        }
        if (scenes.isEmpty()) {
            addSceneSlices(scenes, text, 0);
        }
        return scenes;
    }

    private void addSceneSlices(List<SceneSlice> scenes, String text, int baseOffset) {
        if (text.length() <= SCENE_TARGET_CHARS) {
            scenes.add(new SceneSlice(scenes.size() + 1, baseOffset, baseOffset + text.length(), text));
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + SCENE_TARGET_CHARS);
            if (end < text.length()) {
                int sentenceEnd = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end));
                if (sentenceEnd > start + 200) {
                    end = sentenceEnd + 1;
                }
            }
            String slice = text.substring(start, end).trim();
            if (!slice.isEmpty()) {
                scenes.add(new SceneSlice(scenes.size() + 1, baseOffset + start, baseOffset + end, slice));
            }
            start = end;
        }
    }

    private Long insertScene(ProjectChapterVO chapter,
                             SceneSlice scene,
                             Long generationId,
                             ExecutionFence executionFence) {
        String insertSql = executionFence == null
            ? """
                insert into ai_project_scene(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, scene_no,
                    summary, start_offset, end_offset, confidence)
                values(?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """
            : """
                insert into ai_project_scene(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, scene_no,
                    summary, start_offset, end_offset, confidence)
                select ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?
                """ + EXECUTION_FENCE_FROM;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                insertSql,
                new String[]{"scene_id"}
            );
            statement.setLong(1, chapter.getUserId());
            statement.setLong(2, chapter.getProjectId());
            statement.setLong(3, chapter.getWorkId());
            statement.setLong(4, chapter.getChapterId());
            if (generationId == null) {
                statement.setObject(5, null);
            } else {
                statement.setLong(5, generationId);
            }
            statement.setObject(6, chapter.getVersion());
            statement.setInt(7, scene.sceneNo());
            statement.setString(8, summarize(scene.text(), 280));
            statement.setInt(9, scene.startOffset());
            statement.setInt(10, scene.endOffset());
            statement.setDouble(11, 0.70d);
            if (executionFence != null) {
                bindExecutionFence(statement, 12, generationId, executionFence);
            }
            return statement;
        }, keyHolder);
        if (executionFence != null && inserted != 1) {
            throw new ExecutionLeaseLostException();
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            if (executionFence != null) {
                throw new ExecutionLeaseLostException();
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "scene id missing");
        }
        return key.longValue();
    }

    private Long findOrInsertScene(ProjectChapterVO chapter,
                                   SceneSlice scene,
                                   Long generationId,
                                   ExecutionFence executionFence) {
        List<Long> existing = jdbcTemplate.query(
            "select scene_id from ai_project_scene where generation_id = ? and chapter_id = ? and scene_no = ? order by scene_id asc limit 1",
            (rs, rowNum) -> rs.getLong("scene_id"),
            generationId, chapter.getChapterId(), scene.sceneNo());
        if (existing.isEmpty()) {
            return insertScene(chapter, scene, generationId, executionFence);
        }
        Long sceneId = existing.get(0);
        String updateSql = "update ai_project_scene set status = 'ACTIVE', chapter_version = ?, summary = ?, "
            + "start_offset = ?, end_offset = ?, confidence = ? where scene_id = ? and generation_id = ?";
        Object[] values = new Object[]{chapter.getVersion(), summarize(scene.text(), 280), scene.startOffset(),
            scene.endOffset(), 0.70d, sceneId, generationId};
        if (executionFence == null) {
            jdbcTemplate.update(updateSql, values);
        } else {
            jdbcTemplate.update(
                updateSql + " and " + EXECUTION_FENCE_EXISTS,
                fencedArgs(generationId, executionFence, values));
        }
        return sceneId;
    }

    private void insertVectorChunk(ProjectChapterVO chapter,
                                   Long sceneId,
                                   String sourceType,
                                   Long sourceId,
                                   String text,
                                   String pointId,
                                   Long generationId,
                                   Runnable ownershipCheckpoint,
                                   ExecutionFence executionFence) {
        ownershipCheckpoint.run();
        String contentHash = sha256((text == null ? "" : text) + "|" + pointId);
        String generatedQdrantPointId = projectVectorPointId(pointId + "|generation:" + generationId);
        List<VectorChunkCheckpoint> checkpoints = jdbcTemplate.query(
            "select id, status, qdrant_point_id from ai_project_vector_chunk where generation_id = ? and source_type = ? and content_hash = ? order by id asc limit 1",
            (rs, rowNum) -> new VectorChunkCheckpoint(
                rs.getLong("id"), rs.getString("status"), rs.getString("qdrant_point_id")),
            generationId, sourceType, contentHash);
        if (!checkpoints.isEmpty() && "ACTIVE".equals(checkpoints.get(0).status())) {
            ownershipCheckpoint.run();
            return;
        }
        long vectorChunkId;
        String qdrantPointId;
        if (checkpoints.isEmpty()) {
            qdrantPointId = generatedQdrantPointId;
            String insertSql = executionFence == null
                ? """
                    insert into ai_project_vector_chunk(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, scene_id,
                        source_type, source_id, content_hash, qdrant_point_id, chunk_text, visibility)
                    values(?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 'private')
                    """
                : """
                    insert into ai_project_vector_chunk(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, scene_id,
                        source_type, source_id, content_hash, qdrant_point_id, chunk_text, visibility)
                    select ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 'private'
                    """ + EXECUTION_FENCE_FROM;
            KeyHolder keyHolder = new GeneratedKeyHolder();
            int inserted = jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    insertSql,
                    new String[]{"id"}
                );
                statement.setLong(1, chapter.getUserId());
                statement.setLong(2, chapter.getProjectId());
                statement.setLong(3, chapter.getWorkId());
                statement.setLong(4, chapter.getChapterId());
                statement.setLong(5, generationId);
                statement.setObject(6, chapter.getVersion());
                if (sceneId == null) {
                    statement.setObject(7, null);
                } else {
                    statement.setLong(7, sceneId);
                }
                statement.setString(8, sourceType);
                if (sourceId == null) {
                    statement.setObject(9, null);
                } else {
                    statement.setLong(9, sourceId);
                }
                statement.setString(10, contentHash);
                statement.setString(11, qdrantPointId);
                statement.setString(12, trimForStorage(text, 4000));
                if (executionFence != null) {
                    bindExecutionFence(statement, 13, generationId, executionFence);
                }
                return statement;
            }, keyHolder);
            if (executionFence != null && inserted != 1) {
                throw new ExecutionLeaseLostException();
            }
            Number key = keyHolder.getKey();
            if (key == null) {
                if (executionFence != null) {
                    throw new ExecutionLeaseLostException();
                }
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "project vector chunk id missing");
            }
            vectorChunkId = key.longValue();
        } else {
            vectorChunkId = checkpoints.get(0).id();
            qdrantPointId = checkpoints.get(0).qdrantPointId();
            String updateSql = "update ai_project_vector_chunk set status = 'PENDING', scene_id = ?, source_type = ?, "
                + "source_id = ?, content_hash = ?, chunk_text = ? where id = ? and generation_id = ?";
            Object[] values = new Object[]{sceneId, sourceType, sourceId, contentHash, trimForStorage(text, 4000),
                vectorChunkId, generationId};
            if (executionFence == null) {
                jdbcTemplate.update(updateSql, values);
            } else {
                jdbcTemplate.update(
                    updateSql + " and " + EXECUTION_FENCE_EXISTS,
                    fencedArgs(generationId, executionFence, values));
            }
        }
        ownershipCheckpoint.run();
        upsertProjectVectorChunk(
            chapter,
            vectorChunkId,
            sceneId,
            sourceType,
            sourceId,
            contentHash,
            qdrantPointId,
            text,
            generationId,
            chapter.getVersion(),
            ownershipCheckpoint
        );
        ownershipCheckpoint.run();
        String activationSql = "update ai_project_vector_chunk set status = 'ACTIVE' "
            + "where id = ? and generation_id = ? and status = 'PENDING'";
        int activated = executionFence == null
            ? jdbcTemplate.update(activationSql, vectorChunkId, generationId)
            : jdbcTemplate.update(
                activationSql + " and " + EXECUTION_FENCE_EXISTS,
                fencedArgs(generationId, executionFence, vectorChunkId, generationId));
        if (activated != 1) {
            if (executionFence != null) {
                throw new ExecutionLeaseLostException();
            }
            throw new BusinessException(ResultCode.CONFLICT, "project vector checkpoint activation rejected");
        }
        ownershipCheckpoint.run();
    }

    private int extractWorldRules(ProjectChapterVO chapter,
                                  Long generationId,
                                  Runnable ownershipCheckpoint,
                                  ExecutionFence executionFence) {
        ownershipCheckpoint.run();
        List<String> candidates = linesMatching(
            chapter.getContent(),
            List.of("System rule", "系统规则", "设定", "规则", "金手指", "三端", "terminal")
        );
        ownershipCheckpoint.run();
        for (String candidate : candidates) {
            ownershipCheckpoint.run();
            insertGenerationProjection(
                """
                    insert into ai_project_world_rule(user_id, project_id, work_id, generation_id, chapter_version,
                        status_proj, rule_type, title, content, first_chapter_no, status, confidence)
                    values(?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 'ACTIVE', ?)
                    """,
                """
                    insert into ai_project_world_rule(user_id, project_id, work_id, generation_id, chapter_version,
                        status_proj, rule_type, title, content, first_chapter_no, status, confidence)
                    select ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 'ACTIVE', ?
                    """,
                generationId,
                executionFence,
                chapter.getUserId(),
                chapter.getProjectId(),
                chapter.getWorkId(),
                generationId,
                chapter.getVersion(),
                "system_rule",
                summarize(candidate, 120),
                candidate,
                chapter.getChapterNo(),
                0.78d
            );
        }
        return candidates.size();
    }

    private int extractForeshadowings(ProjectChapterVO chapter,
                                      Long generationId,
                                      Runnable ownershipCheckpoint,
                                      ExecutionFence executionFence) {
        ownershipCheckpoint.run();
        List<String> candidates = linesMatching(
            chapter.getContent(),
            List.of("Foreshadowing", "伏笔", "暗线", "未回收", "未解", "神秘", "unknown", "unresolved")
        );
        ownershipCheckpoint.run();
        for (String candidate : candidates) {
            ownershipCheckpoint.run();
            insertGenerationProjection(
                """
                    insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, chapter_version,
                        title, content, status, planted_chapter_no, importance, evidence_refs, confidence)
                    values(?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
                    """,
                """
                    insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, chapter_version,
                        title, content, status, planted_chapter_no, importance, evidence_refs, confidence)
                    select ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?
                    """,
                generationId,
                executionFence,
                chapter.getUserId(),
                chapter.getProjectId(),
                chapter.getWorkId(),
                generationId,
                chapter.getVersion(),
                summarize(candidate, 120),
                candidate,
                chapter.getChapterNo(),
                "medium",
                "{\"chapterId\":" + chapter.getChapterId() + "}",
                0.76d
            );
        }
        return candidates.size();
    }

    private int extractTimelineEvents(ProjectChapterVO chapter,
                                      List<Long> sceneIds,
                                      Long generationId,
                                      Runnable ownershipCheckpoint,
                                      ExecutionFence executionFence) {
        ownershipCheckpoint.run();
        List<String> candidates = linesMatching(
            chapter.getContent(),
            List.of("Timeline event", "时间线", "事件", "完成", "开启", "交付", "receives", "completes")
        );
        ownershipCheckpoint.run();
        if (candidates.isEmpty()) {
            candidates = List.of(summarize(chapter.getContent(), 220));
        }
        int order = 1;
        for (String candidate : candidates) {
            ownershipCheckpoint.run();
            Long sceneId = sceneIds.isEmpty() ? null : sceneIds.get(Math.min(sceneIds.size() - 1, order - 1));
            insertGenerationProjection(
                """
                    insert into ai_project_timeline_event(user_id, project_id, work_id, chapter_id, generation_id,
                        chapter_version, status, chapter_no, scene_id, event_order, title, summary, causal_refs, confidence)
                    values(?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                    """,
                """
                    insert into ai_project_timeline_event(user_id, project_id, work_id, chapter_id, generation_id,
                        chapter_version, status, chapter_no, scene_id, event_order, title, summary, causal_refs, confidence)
                    select ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?
                    """,
                generationId,
                executionFence,
                chapter.getUserId(),
                chapter.getProjectId(),
                chapter.getWorkId(),
                chapter.getChapterId(),
                generationId,
                chapter.getVersion(),
                chapter.getChapterNo(),
                sceneId,
                order,
                summarize(candidate, 120),
                candidate,
                "{\"chapterId\":" + chapter.getChapterId() + "}",
                0.72d
            );
            order++;
        }
        return candidates.size();
    }

    private int extractCharacterState(ProjectChapterVO chapter,
                                      Long generationId,
                                      Runnable ownershipCheckpoint,
                                      ExecutionFence executionFence) {
        ownershipCheckpoint.run();
        String content = chapter.getContent();
        if (content == null || content.isBlank()) {
            return 0;
        }
        String characterName = firstMentionedName(content);
        ownershipCheckpoint.run();
        if (characterName == null) {
            return 0;
        }
        ownershipCheckpoint.run();
        insertGenerationProjection(
            """
                insert into ai_project_character_state(user_id, project_id, work_id, character_name,
                    chapter_id, generation_id, chapter_version, status, chapter_no, state_summary, motivation, confidence)
                values(?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """,
            """
                insert into ai_project_character_state(user_id, project_id, work_id, character_name,
                    chapter_id, generation_id, chapter_version, status, chapter_no, state_summary, motivation, confidence)
                select ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?
                """,
            generationId,
            executionFence,
            chapter.getUserId(),
            chapter.getProjectId(),
            chapter.getWorkId(),
            characterName,
            chapter.getChapterId(),
            generationId,
            chapter.getVersion(),
            chapter.getChapterNo(),
            summarize(content, 220),
            "imported_chapter_context",
            0.55d
        );
        return 1;
    }

    private void insertIngestJob(ProjectChapterVO chapter, int sceneCount) {
        String resultJson = "{\"sceneCount\":" + sceneCount + ",\"vectorScope\":\"project_work\"}";
        jdbcTemplate.update(
            """
                insert into ai_project_ingest_job(user_id, project_id, work_id, chapter_id,
                    job_type, status, progress, result_json)
                values(?, ?, ?, ?, 'chapter_import_parse', 'COMPLETED', 100, ?)
                """,
            chapter.getUserId(),
            chapter.getProjectId(),
            chapter.getWorkId(),
            chapter.getChapterId(),
            resultJson
        );
    }

    private List<String> linesMatching(String content, List<String> markers) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String block : PARAGRAPH_SPLIT.split(content.trim())) {
            String candidate = block.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (matchesAnyMarker(candidate, markers)) {
                matches.add(trimForStorage(candidate, 1000));
            }
        }
        if (!matches.isEmpty()) {
            return matches;
        }
        String[] lines = content.split("\\R+");
        for (String line : lines) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && matchesAnyMarker(candidate, markers)) {
                matches.add(trimForStorage(candidate, 1000));
            }
        }
        return matches;
    }

    private boolean matchesAnyMarker(String candidate, List<String> markers) {
        String lower = candidate.toLowerCase();
        for (String marker : markers) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String firstMentionedName(String content) {
        String[] knownNames = {"林周", "Lin Zhou", "主角", "男主", "女主"};
        for (String name : knownNames) {
            if (content.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private String summarize(String value, int maxChars) {
        String text = trimForStorage(value, maxChars);
        if (text == null || text.isBlank()) {
            return "Imported chapter context";
        }
        return text;
    }

    private String trimForStorage(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String text = value.replace("\r", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private void insertGenerationProjection(String valuesSql,
                                            String selectSql,
                                            Long generationId,
                                            ExecutionFence executionFence,
                                            Object... values) {
        if (executionFence == null) {
            jdbcTemplate.update(valuesSql, values);
            return;
        }
        int inserted = jdbcTemplate.update(
            selectSql + EXECUTION_FENCE_FROM,
            fencedArgs(generationId, executionFence, values));
        if (inserted != 1) {
            throw new ExecutionLeaseLostException();
        }
    }

    private Object[] fencedArgs(Long generationId, ExecutionFence executionFence, Object... values) {
        Object[] args = new Object[values.length + 4];
        System.arraycopy(values, 0, args, 0, values.length);
        args[values.length] = executionFence.ingestJobId();
        args[values.length + 1] = generationId;
        args[values.length + 2] = executionFence.leaseOwner();
        args[values.length + 3] = executionFence.fencingToken();
        return args;
    }

    private void bindExecutionFence(PreparedStatement statement,
                                    int startIndex,
                                    Long generationId,
                                    ExecutionFence executionFence) throws SQLException {
        statement.setLong(startIndex, executionFence.ingestJobId());
        statement.setLong(startIndex + 1, generationId);
        statement.setString(startIndex + 2, executionFence.leaseOwner());
        statement.setLong(startIndex + 3, executionFence.fencingToken());
    }

    private RowMapper<ProjectWorkVO> workMapper() {
        return (rs, rowNum) -> {
            ProjectWorkVO vo = new ProjectWorkVO();
            vo.setWorkId(rs.getLong("work_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setProjectId(rs.getLong("project_id"));
            vo.setTitle(rs.getString("title"));
            vo.setAlias(rs.getString("alias"));
            vo.setGenre(rs.getString("genre"));
            vo.setStatus(rs.getString("status"));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            vo.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return vo;
        };
    }

    public RowMapper<ProjectChapterVO> chapterMapperPublic() {
        return (rs, rowNum) -> {
            ProjectChapterVO vo = new ProjectChapterVO();
            vo.setChapterId(rs.getLong("chapter_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setProjectId(rs.getLong("project_id"));
            vo.setWorkId(rs.getLong("work_id"));
            vo.setChapterNo(rs.getInt("chapter_no"));
            vo.setTitle(rs.getString("title"));
            vo.setContent(rs.getString("content"));
            vo.setContentHash(rs.getString("content_hash"));
            vo.setWordCount(rs.getInt("word_count"));
            vo.setSourceType(rs.getString("source_type"));
            vo.setVersion(rs.getInt("version"));
            vo.setStatus(rs.getString("status"));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            vo.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return vo;
        };
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private String requireText(String value, String message, int maxChars) {
        String text = trimToNull(value, maxChars);
        if (text == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return text;
    }

    private String trimToNull(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (maxChars != Integer.MAX_VALUE && trimmed.length() > maxChars) {
            return trimmed.substring(0, maxChars);
        }
        return trimmed;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "sha256 unavailable");
        }
    }

    public record ExecutionFence(Long ingestJobId, String leaseOwner, long fencingToken) {
    }

    public static final class ExecutionLeaseLostException extends RuntimeException {
        private ExecutionLeaseLostException() {
            super("project ingest execution lease lost");
        }
    }

    private record SceneSlice(int sceneNo, int startOffset, int endOffset, String text) {
    }

    private record VectorChunkCheckpoint(long id, String status, String qdrantPointId) {
    }
}
