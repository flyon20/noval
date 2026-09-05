package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatMessageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeConversationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

@Service
public class KnowledgeConversationReadService {

    private static final String ROLLOUT_CONFIG_KEY = "ai.conversation.read-rollout-percent";
    private static final String FALLBACK_CONFIG_KEY = "ai.conversation.legacy-fallback-enabled";

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeConversationService conversationService;
    private final IntSupplier rolloutSupplier;
    private final BooleanSupplier fallbackSupplier;

    @Autowired
    public KnowledgeConversationReadService(JdbcTemplate jdbcTemplate,
                                            KnowledgeConversationService conversationService,
                                            SystemConfigService systemConfigService) {
        this(
            jdbcTemplate,
            conversationService,
            () -> systemConfigService.getIntValueOrDefault(ROLLOUT_CONFIG_KEY, 0),
            () -> systemConfigService.getBooleanValueOrDefault(FALLBACK_CONFIG_KEY, true)
        );
    }

    KnowledgeConversationReadService(JdbcTemplate jdbcTemplate,
                                     KnowledgeConversationService conversationService,
                                     IntSupplier rolloutSupplier,
                                     BooleanSupplier fallbackSupplier) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationService = conversationService;
        this.rolloutSupplier = rolloutSupplier;
        this.fallbackSupplier = fallbackSupplier;
    }

    public List<KnowledgeConversationVO> listMine(Long projectId) {
        AuthUser user = requireUser();
        boolean useConversation = useConversationRead(user.getUserId());
        if (!useConversation) {
            return listLegacy(user.getUserId(), projectId);
        }
        List<KnowledgeConversationVO> conversations = conversationService.listMine(projectId);
        if (!fallbackSupplier.getAsBoolean()) {
            return conversations;
        }
        return merge(conversations, listLegacy(user.getUserId(), projectId));
    }

    public KnowledgeConversationVO get(String conversationId) {
        return get(conversationId, null);
    }

    public KnowledgeConversationVO get(String conversationId, Long projectId) {
        AuthUser user = requireUser();
        boolean preferConversation = useConversationRead(user.getUserId());
        if (preferConversation) {
            try {
                KnowledgeConversationVO conversation = conversationService.get(conversationId);
                if (projectId != null && !Objects.equals(projectId, conversation.getProjectId())) {
                    throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
                }
                return conversation;
            } catch (BusinessException ex) {
                if (!fallbackSupplier.getAsBoolean() || ex.getResultCode() != ResultCode.NOT_FOUND) {
                    throw ex;
                }
            }
        }
        if (!preferConversation) {
            return getLegacy(user.getUserId(), projectId, conversationId);
        }
        if (!fallbackSupplier.getAsBoolean()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
        }
        return getLegacy(user.getUserId(), projectId, conversationId);
    }

    public List<KnowledgeChatMessageVO> listMessages(String conversationId) {
        return listMessages(conversationId, null);
    }

    public List<KnowledgeChatMessageVO> listMessages(String conversationId, Long projectId) {
        return get(conversationId, projectId).getMessages();
    }

    private List<KnowledgeConversationVO> listLegacy(Long userId, Long projectId) {
        List<LegacySummary> summaries = queryLegacySummaries(userId, projectId);
        Set<ConversationScope> archivedIds = archivedConversationIds(userId, projectId);
        List<KnowledgeConversationVO> conversations = new ArrayList<>();
        for (LegacySummary summary : summaries) {
            if (!archivedIds.contains(new ConversationScope(
                summary.projectId(), summary.canonicalConversationId()
            ))) {
                conversations.add(toConversation(summary));
            }
        }
        return List.copyOf(conversations);
    }

    private List<KnowledgeConversationVO> merge(List<KnowledgeConversationVO> primary,
                                                List<KnowledgeConversationVO> fallback) {
        Map<ConversationScope, KnowledgeConversationVO> merged = new LinkedHashMap<>();
        for (KnowledgeConversationVO conversation : primary) {
            merged.put(new ConversationScope(
                conversation.getProjectId(), conversation.getConversationId()
            ), conversation);
        }
        for (KnowledgeConversationVO conversation : fallback) {
            merged.putIfAbsent(new ConversationScope(
                conversation.getProjectId(), conversation.getConversationId()
            ), conversation);
        }
        return List.copyOf(merged.values());
    }

    private KnowledgeConversationVO getLegacy(Long userId, Long projectId, String conversationId) {
        Long resolvedProjectId = resolveLegacyProjectId(userId, projectId, conversationId);
        if (isArchived(userId, resolvedProjectId, conversationId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
        }
        List<LegacyRun> runs = queryLegacyRuns(userId, resolvedProjectId, conversationId);
        if (runs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
        }
        LegacyRun latest = runs.get(0);
        String canonicalId = legacyConversationId(latest);
        KnowledgeConversationVO conversation = toConversation(latest, canonicalId, true);
        List<KnowledgeChatMessageVO> messages = new ArrayList<>();
        long syntheticId = -1L;
        for (int index = runs.size() - 1; index >= 0; index--) {
            LegacyRun run = runs.get(index);
            messages.add(toMessage(syntheticId--, run, "USER", run.question(), null));
            if (isUsableAnswer(run)) {
                String contentJson = "ANSWERED".equals(run.status())
                    ? run.resultJson()
                    : "{\"migrationStatus\":\"PARTIAL\"}";
                messages.add(toMessage(syntheticId--, run, "ASSISTANT", run.answer(), contentJson));
            }
        }
        conversation.setMessages(messages);
        return conversation;
    }

    private List<LegacyRun> queryLegacyRuns(Long userId, Long projectId, String conversationId) {
        StringBuilder sql = new StringBuilder(
            "select r.run_id, r.user_id, r.project_id, coalesce(r.legacy_conversation_id, r.conversation_id) " +
                "as conversation_id, r.question, r.answer, r.result_json, " +
                "r.status, r.progress_phase, m.canonical_conversation_id, " +
                "r.migration_activity_at as activity_time " +
                "from ai_chat_run r left join ai_conversation_legacy_map m on m.user_id = r.user_id " +
                "and m.project_scope_id = coalesce(r.project_id, -1) " +
                "and m.legacy_conversation_id = case when trim(coalesce(r.legacy_conversation_id, " +
                "r.conversation_id, '')) = '' then concat('__EMPTY__:', r.run_id) " +
                "else coalesce(r.legacy_conversation_id, r.conversation_id) end " +
                "where r.user_id = ? and r.deleted = 0"
        );
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (projectId == null) {
            sql.append(" and r.project_id is null");
        } else {
            sql.append(" and r.project_id = ?");
            args.add(projectId);
        }
        String emptyRunId = resolveSyntheticEmptyRunId(userId, projectId, conversationId);
        sql.append(" and (coalesce(r.legacy_conversation_id, r.conversation_id) = ? " +
            "or m.canonical_conversation_id = ? or r.run_id = ?)");
        args.add(conversationId);
        args.add(conversationId);
        args.add(emptyRunId);
        sql.append(" order by activity_time desc, r.run_id desc");
        return jdbcTemplate.query(sql.toString(), this::mapRun, args.toArray());
    }

    private List<LegacySummary> queryLegacySummaries(Long userId, Long projectId) {
        String projectFilter = projectId == null ? "" : " and project_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (projectId != null) {
            args.add(projectId);
        }
        args.add(userId);
        String sql = """
            select run_id, user_id, project_id, question, status, activity_time,
                legacy_conversation_key, mapped_conversation_id
            from (
                select r.run_id, r.user_id, r.project_id, r.question, r.status,
                    r.migration_activity_at as activity_time,
                    r.migration_legacy_key as legacy_conversation_key,
                    m.canonical_conversation_id as mapped_conversation_id,
                    row_number() over (partition by coalesce(r.project_id, -1), coalesce(m.canonical_conversation_id,
                        r.migration_legacy_key)
                        order by r.migration_activity_at desc, r.run_id desc) as row_no
                from ai_chat_run r
                join (
                    select coalesce(project_id, -1) as project_scope_id, migration_legacy_key,
                        max(migration_activity_at) as latest_activity
                    from ai_chat_run
                    where user_id = ? and deleted = 0
            """ + projectFilter + """
                    group by coalesce(project_id, -1), migration_legacy_key
                    order by latest_activity desc, migration_legacy_key
                    limit 200
                ) latest on latest.project_scope_id = coalesce(r.project_id, -1)
                    and latest.migration_legacy_key = r.migration_legacy_key
                    and latest.latest_activity = r.migration_activity_at
                left join ai_conversation_legacy_map m on m.user_id = r.user_id
                    and m.project_scope_id = coalesce(r.project_id, -1)
                    and m.legacy_conversation_id = r.migration_legacy_key
                where r.user_id = ? and r.deleted = 0
            ) ranked
            where row_no = 1
            order by activity_time desc, run_id desc
            limit 200
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new LegacySummary(
            rs.getString("run_id"),
            rs.getLong("user_id"),
            nullableLong(rs, "project_id"),
            summaryConversationId(
                rs.getLong("user_id"),
                nullableLong(rs, "project_id"),
                rs.getString("legacy_conversation_key"),
                rs.getString("mapped_conversation_id")
            ),
            rs.getString("question"),
            rs.getString("status"),
            rs.getTimestamp("activity_time")
        ), args.toArray());
    }

    private Long resolveLegacyProjectId(Long userId, Long projectId, String conversationId) {
        if (projectId != null) {
            return projectId;
        }
        List<Long> projectIds = jdbcTemplate.query("""
                select distinct r.project_id
                from ai_chat_run r
                left join ai_conversation_legacy_map m on m.user_id = r.user_id
                    and m.project_scope_id = coalesce(r.project_id, -1)
                    and m.legacy_conversation_id = case
                        when trim(coalesce(r.legacy_conversation_id, r.conversation_id, '')) = ''
                            then concat('__EMPTY__:', r.run_id)
                        else coalesce(r.legacy_conversation_id, r.conversation_id) end
                where r.user_id = ? and r.deleted = 0
                  and (coalesce(r.legacy_conversation_id, r.conversation_id) = ?
                       or m.canonical_conversation_id = ?)
                """, (rs, rowNum) -> nullableLong(rs, "project_id"), userId, conversationId, conversationId);
        if (projectIds.isEmpty()) {
            projectIds = syntheticEmptyMatches(userId, null, conversationId).stream()
                .map(EmptyRun::projectId)
                .distinct()
                .toList();
        }
        if (projectIds.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
        }
        if (projectIds.size() > 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "projectId is required for an ambiguous conversation");
        }
        return projectIds.get(0);
    }

    private String resolveSyntheticEmptyRunId(Long userId, Long projectId, String conversationId) {
        List<EmptyRun> matches = syntheticEmptyMatches(userId, projectId, conversationId);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "projectId is required for an ambiguous conversation");
        }
        return matches.get(0).runId();
    }

    private List<EmptyRun> syntheticEmptyMatches(Long userId, Long projectId, String conversationId) {
        if (conversationId == null || !conversationId.startsWith("conv-migrated-")) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
            "select run_id, project_id from ai_chat_run where user_id = ? and deleted = 0 " +
                "and trim(coalesce(legacy_conversation_id, conversation_id, '')) = ''"
        );
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (projectId != null) {
            sql.append(" and project_id = ?");
            args.add(projectId);
        }
        sql.append(" order by migration_activity_at desc, run_id desc limit 5000");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new EmptyRun(
            rs.getString("run_id"), nullableLong(rs, "project_id")
        ), args.toArray()).stream().filter(run -> conversationId.equals(summaryConversationId(
            userId, run.projectId(), "__EMPTY__:" + run.runId(), null
        ))).toList();
    }

    private Set<ConversationScope> archivedConversationIds(Long userId, Long projectId) {
        String sql = "select project_id, conversation_id from ai_conversation " +
            "where user_id = ? and status = 'ARCHIVED'";
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (projectId != null) {
            sql += " and project_id = ?";
            args.add(projectId);
        }
        return new HashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> new ConversationScope(
            nullableLong(rs, "project_id"), rs.getString("conversation_id")
        ), args.toArray()));
    }

    private boolean isArchived(Long userId, Long projectId, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_conversation c
                where c.user_id = ? and coalesce(c.project_id, -1) = coalesce(?, -1)
                  and c.status = 'ARCHIVED'
                  and (c.conversation_id = ? or c.conversation_id in (
                      select m.canonical_conversation_id from ai_conversation_legacy_map m
                      where m.user_id = ? and m.project_scope_id = coalesce(?, -1)
                        and m.legacy_conversation_id = ?
                  ))
                """, Integer.class,
            userId, projectId, conversationId, userId, projectId, conversationId);
        return count != null && count > 0;
    }

    private KnowledgeConversationVO toConversation(LegacyRun run,
                                                   String conversationId,
                                                   boolean includeMessages) {
        KnowledgeConversationVO vo = new KnowledgeConversationVO();
        vo.setConversationId(conversationId);
        vo.setUserId(run.userId());
        vo.setProjectId(run.projectId());
        vo.setTitle(title(run.question()));
        vo.setStatus("ACTIVE");
        vo.setLastRunId(run.runId());
        vo.setLastRunStatus(run.status());
        vo.setCreatedAt(localDateTime(run.activityTime()));
        vo.setUpdatedAt(localDateTime(run.activityTime()));
        if (!includeMessages) {
            vo.setMessages(List.of());
        }
        return vo;
    }

    private KnowledgeChatMessageVO toMessage(long messageId,
                                             LegacyRun run,
                                             String role,
                                             String content,
                                             String contentJson) {
        KnowledgeChatMessageVO vo = new KnowledgeChatMessageVO();
        vo.setMessageId(messageId);
        vo.setConversationId(legacyConversationId(run));
        vo.setUserId(run.userId());
        vo.setProjectId(run.projectId());
        vo.setRunId(run.runId());
        vo.setRole(role);
        vo.setContent(content);
        vo.setContentJson(contentJson);
        vo.setCreatedAt(localDateTime(run.activityTime()));
        return vo;
    }

    private LegacyRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        long userId = rs.getLong("user_id");
        long projectId = rs.getLong("project_id");
        Long nullableProjectId = rs.wasNull() ? null : projectId;
        return new LegacyRun(
            rs.getString("run_id"),
            userId,
            nullableProjectId,
            rs.getString("conversation_id"),
            rs.getString("question"),
            rs.getString("answer"),
            rs.getString("result_json"),
            rs.getString("status"),
            rs.getString("progress_phase"),
            rs.getString("canonical_conversation_id"),
            rs.getTimestamp("activity_time")
        );
    }

    private boolean useConversationRead(Long userId) {
        int rollout = Math.max(0, Math.min(100, rolloutSupplier.getAsInt()));
        return rollout >= 100 || (rollout > 0 && Math.floorMod(Long.hashCode(userId), 100) < rollout);
    }

    private String legacyConversationId(LegacyRun run) {
        String legacyId = trimToNull(run.conversationId());
        if (trimToNull(run.canonicalConversationId()) != null) {
            return run.canonicalConversationId();
        }
        if (legacyId != null) {
            return legacyId;
        }
        String scope = run.userId() + "|" + (run.projectId() == null ? -1 : run.projectId()) +
            "|__EMPTY__:" + run.runId();
        return "conv-migrated-" + hash(scope).substring(0, 32);
    }

    private boolean isUsableAnswer(LegacyRun run) {
        if (trimToNull(run.answer()) == null) {
            return false;
        }
        if ("ANSWERED".equals(run.status())) {
            return true;
        }
        String normalizedResult = trimToNull(run.resultJson());
        return ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status()))
            && ("answer".equals(run.progressPhase()) || "compose".equals(run.progressPhase())
                || "done".equals(run.progressPhase()))
            && normalizedResult != null
            && !"{}".equals(normalizedResult.replace(" ", ""))
            && !"null".equalsIgnoreCase(normalizedResult);
    }

    private KnowledgeConversationVO toConversation(LegacySummary summary) {
        KnowledgeConversationVO vo = new KnowledgeConversationVO();
        vo.setConversationId(summary.canonicalConversationId());
        vo.setUserId(summary.userId());
        vo.setProjectId(summary.projectId());
        vo.setTitle(title(summary.question()));
        vo.setStatus("ACTIVE");
        vo.setLastRunId(summary.runId());
        vo.setLastRunStatus(summary.status());
        vo.setCreatedAt(localDateTime(summary.activityTime()));
        vo.setUpdatedAt(localDateTime(summary.activityTime()));
        vo.setMessages(List.of());
        return vo;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String summaryConversationId(Long userId,
                                         Long projectId,
                                         String legacyKey,
                                         String mappedConversationId) {
        if (trimToNull(mappedConversationId) != null) {
            return mappedConversationId;
        }
        if (legacyKey != null && legacyKey.startsWith("__EMPTY__:")) {
            String scope = userId + "|" + (projectId == null ? -1 : projectId) + "|" + legacyKey;
            return "conv-migrated-" + hash(scope).substring(0, 32);
        }
        return legacyKey;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String title(String question) {
        String normalized = trimToNull(question);
        if (normalized == null) {
            return "Legacy conversation";
        }
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private java.time.LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private record LegacyRun(String runId,
                             Long userId,
                             Long projectId,
                             String conversationId,
                             String question,
                             String answer,
                             String resultJson,
                             String status,
                             String progressPhase,
                             String canonicalConversationId,
                             Timestamp activityTime) {
    }

    private record LegacySummary(String runId,
                                 Long userId,
                                 Long projectId,
                                 String canonicalConversationId,
                                 String question,
                                 String status,
                                 Timestamp activityTime) {
    }

    private record EmptyRun(String runId, Long projectId) {
    }

    private record ConversationScope(Long projectId, String conversationId) {
    }
}
