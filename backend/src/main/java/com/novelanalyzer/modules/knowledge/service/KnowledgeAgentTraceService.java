package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.GoldenCandidateDraftVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTracePageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceSummaryVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeAgentTraceService {

    private static final Set<String> MEMORY_TRACE_CONTAINER_KEYS = Set.of(
        "conversationSummary", "projectMemory", "userMemory", "semanticMemory",
        "memoryUsed", "diagnostics", "memoryEvidence", "layers", "memoryLayers",
        "candidatePersistence", "backendFallback", "items", "failures", "provenance", "evidence",
        "projectProfile", "threadSummary", "userProfile"
    );
    private static final Set<String> MEMORY_TRACE_NUMBER_KEYS = Set.of(
        "id", "memoryId", "projectId", "count", "loaded", "saved", "failed",
        "projectMemoryCount", "userMemoryCount", "semanticMemoryCount", "rejectedCount", "confidence"
    );
    private static final Set<String> MEMORY_TRACE_BOOLEAN_KEYS = Set.of(
        "confirmedOnly", "conversationSummary", "project", "thread", "user", "semantic"
    );
    private static final Set<String> MEMORY_TRACE_TEXT_KEYS = Set.of(
        "id", "memoryId", "projectId", "scope", "memoryType", "status", "lifecycleStatus",
        "sourceTraceId", "candidateStatus", "conflictPolicy", "candidateScope",
        "candidateType", "type", "candidateKey", "evidenceKind", "extractor", "extractorVersion",
        "factKey", "indexGeneration", "kind", "source", "sourceKind"
    );
    private static final Set<String> MEMORY_TRACE_TEXT_LIST_KEYS = Set.of(
        "rejectedStatuses", "projectMemoryKeys", "keys", "sourceIds"
    );
    private static final Set<String> MEMORY_TRACE_TOP_LEVEL_NUMBER_KEYS = Set.of(
        "memoryCandidatesPersisted", "memoryCandidatesBackendRecovered"
    );
    private static final Set<String> MEMORY_TRACE_REASONS = Set.of(
        "missing_user", "missing_user_or_conversation", "no_project_id", "client_method_missing"
    );
    private static final Pattern MEMORY_TRACE_TOKEN = Pattern.compile("^[\\p{L}\\p{N}._:@/\\-]{1,256}$");
    private static final Pattern TRACE_MODEL_TOKEN = Pattern.compile("^[\\p{L}\\p{N}._:@/\\-]{1,128}$");
    private static final Pattern MEMORY_TRACE_EXCEPTION = Pattern.compile("^[A-Z][A-Za-z0-9]*(?:Error|Exception)$");
    private static final Pattern TRACE_CREDENTIAL_VALUE = Pattern.compile(
        "(?i)(?:\\b(?:sk|rk|pk)-[a-z0-9_-]{8,}\\b|\\bAKIA[0-9A-Z]{16}\\b|\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,})"
    );
    private static final Set<String> TRACE_TOKEN_KEYS = Set.of(
        "agentname", "answermode", "answerstatus", "businessroute", "category", "checkpointstore", "documenttype",
        "domainintent", "errortype", "event", "evidence", "experts", "freshness", "intent", "kind",
        "lifecyclestatus", "memorytype", "mode", "model", "name", "node", "nodetype", "primaryintent",
        "provider", "route", "scope", "source", "sourcekind", "sourcetype", "state", "status", "tool", "toolname",
        "tools", "traceid", "type", "actualmodel", "requestedmodel", "requestedreasoningmode",
        "providertransport", "kernelstopreason", "cacheidentitymode", "requestfamily"
    );
    private static final Set<String> TRACE_PROVIDER_WIRE_KEYS = Set.of("wireapi", "from", "to");
    private static final Set<String> TRACE_IDENTIFIER_KEYS = Set.of(
        "authorizationdecisionid", "callid", "candidateid", "chapterid", "chapterno", "chapterversion", "chunkid", "contenthash", "documentid",
        "edgeid", "factid", "generationid", "id", "memoryid", "nodeid", "projectid", "runid",
        "provenanceref", "skillid", "sourceid", "sourcetraceid", "snapshottime", "traceid", "userid", "version", "workid"
    );
    private static final Set<String> TRACE_SUMMARY_KEYS = Set.of("description", "message", "notes", "reason", "summary", "title");
    private static final Set<String> TRACE_TEXT_LIST_KEYS = Set.of(
        "activatedskillids", "candidatereasons", "degradationreasons", "eligibleskillids", "evidencerefs",
        "executedruntimenodes", "layers", "materializedresourceids", "projectmemorykeys", "reasontags",
        "rejectionreasons", "requestedcapabilityids", "requiredsources", "selectedskills", "sourcetypes", "sourcepriority"
    );
    private static final Set<String> TRACE_BODY_KEYS = Set.of(
        "answer", "body", "chunktext", "content", "delta", "file", "input", "markdown", "output",
        "instructions", "payload", "prompt", "skillinstructions", "text", "upload"
    );
    private static final Set<String> TRACE_SAFE_COUNT_KEYS = Set.of(
        "durationms", "tokenused", "promptcachehittokens", "promptcachemisstokens",
        "inputtokens", "outputtokens", "reasoningtokens", "cachedinputtokens", "prompttokens",
        "completiontokens", "totaltokens", "maxinputtokens",
        "messagecount", "messagechars", "toolschemacount", "outputchars", "toolcallcount",
        "historytotalcount", "historyincludedcount", "historytotalchars", "historyincludedchars",
        "contextsummarychars", "contextsummaryincludedchars", "providerrequestcount", "kernelturn", "kernelturns"
    );
    private static final Set<String> TRACE_MODEL_KEYS = Set.of("model", "actualmodel", "requestedmodel");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeAgentTraceService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    @Autowired
    public KnowledgeAgentTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void persistFromChat(Long userId,
                                Long projectId,
                                String conversationId,
                                String question,
                                KnowledgeChatResponseVO response) {
        if (userId == null || response == null || response.getResultJson() == null) {
            return;
        }
        Map<String, Object> resultJson = sanitizeResultForTrace(response.getResultJson());
        if (!resultJson.containsKey("taskGraph") && !resultJson.containsKey("toolRuns")) {
            return;
        }
        String traceId = resolveTraceId(resultJson);
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, project_id, conversation_id, question, status, " +
                "task_graph_json, tool_runs_json, evidence_pack_json, perspective_results_json, result_json) " +
                "values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            traceId,
            userId,
            projectId,
            sanitizeTraceIdentifier(conversationId),
            sanitizeTraceQuestion(question),
            sanitizeTraceToken(response.getStatus()),
            toJson(resultJson.get("taskGraph")),
            toJson(resultJson.get("toolRuns")),
            toJson(resultJson.get("evidencePackSummary")),
            toJson(resultJson.get("perspectiveResults")),
            toJson(resultJson)
        );
    }

    public List<KnowledgeAgentTraceVO> listForAdmin() {
        requireAdmin();
        return jdbcTemplate.query(
            "select id, trace_id, user_id, project_id, conversation_id, question, status, task_graph_json, " +
                "tool_runs_json, evidence_pack_json, perspective_results_json, result_json, created_at " +
                "from ai_agent_trace order by id desc limit 100",
            mapper()
        );
    }

    public KnowledgeAgentTracePageVO listForAdmin(Integer page, Integer pageSize, String status, String keyword) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        List<Object> args = new ArrayList<>();
        String where = buildWhere(status, keyword, args);
        Long total = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_trace" + where,
            Long.class,
            args.toArray()
        );
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<KnowledgeAgentTraceSummaryVO> items = jdbcTemplate.query(
            "select id, trace_id, user_id, project_id, conversation_id, question, status, result_json, created_at " +
                "from ai_agent_trace" + where + " order by id desc limit ? offset ?",
            summaryMapper(),
            pageArgs.toArray()
        );
        KnowledgeAgentTracePageVO vo = new KnowledgeAgentTracePageVO();
        vo.setPage(safePage);
        vo.setPageSize(safePageSize);
        vo.setTotal(total == null ? 0L : total);
        vo.setHasNext((long) safePage * safePageSize < vo.getTotal());
        vo.setItems(items);
        return vo;
    }

    public KnowledgeAgentTraceVO detailForAdmin(Long id) {
        requireAdmin();
        List<KnowledgeAgentTraceVO> traces = jdbcTemplate.query(
            "select id, trace_id, user_id, project_id, conversation_id, question, status, task_graph_json, " +
                "tool_runs_json, evidence_pack_json, perspective_results_json, result_json, created_at " +
                "from ai_agent_trace where id = ?",
            mapper(),
            id
        );
        if (traces.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "trace not found");
        }
        return traces.get(0);
    }

    public GoldenCandidateDraftVO createGoldenCandidateDraft(Long traceRecordId) {
        KnowledgeAgentTraceVO trace = detailForAdmin(traceRecordId);
        Map<String, Object> result = parseResultJson(trace.getResultJson());
        GoldenCandidateDraftVO draft = new GoldenCandidateDraftVO();
        draft.setTraceRecordId(trace.getId());
        draft.setTraceId(trace.getTraceId());
        draft.setQuestion(trace.getQuestion());
        draft.setAnswer(firstStringValue(result, "answer"));
        draft.setTraceSummary(traceSummary(result));
        draft.setSelectedSkills(extractSelectedSkills(result));
        draft.setSelectedTools(extractSelectedTools(result));
        draft.setEvidenceContract(toJsonOrNull(firstPresent(result, "evidenceContract", "evidencePackSummary")));
        draft.setStatus("DRAFT");
        return draft;
    }

    private String resolveTraceId(Map<String, Object> resultJson) {
        String direct = firstTraceId(resultJson, "traceId", "trace_id");
        if (direct != null) {
            return direct;
        }
        Object trace = resultJson.get("trace");
        if (trace instanceof Map<?, ?> map) {
            String traceId = firstTraceId(map, "traceId", "trace_id");
            if (traceId != null) {
                return traceId;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String firstTraceId(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            String traceId = trimToNull(value == null ? null : String.valueOf(value));
            if (traceId != null) {
                return traceId;
            }
        }
        return null;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "trace json serialization failed");
        }
    }

    private RowMapper<KnowledgeAgentTraceVO> mapper() {
        return (rs, rowNum) -> {
            KnowledgeAgentTraceVO vo = new KnowledgeAgentTraceVO();
            vo.setId(rs.getLong("id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setUserId(rs.getLong("user_id"));
            long projectId = rs.getLong("project_id");
            vo.setProjectId(rs.wasNull() ? null : projectId);
            vo.setConversationId(rs.getString("conversation_id"));
            vo.setQuestion(rs.getString("question"));
            vo.setStatus(rs.getString("status"));
            vo.setTaskGraph(rs.getString("task_graph_json"));
            vo.setToolRuns(rs.getString("tool_runs_json"));
            vo.setEvidencePack(rs.getString("evidence_pack_json"));
            vo.setPerspectiveResults(rs.getString("perspective_results_json"));
            String resultJson = rs.getString("result_json");
            vo.setResultJson(resultJson);
            hydrateExpandedTraceSections(vo, resultJson);
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return vo;
        };
    }

    private RowMapper<KnowledgeAgentTraceSummaryVO> summaryMapper() {
        return (rs, rowNum) -> {
            KnowledgeAgentTraceSummaryVO vo = new KnowledgeAgentTraceSummaryVO();
            vo.setId(rs.getLong("id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setUserId(rs.getLong("user_id"));
            long projectId = rs.getLong("project_id");
            vo.setProjectId(rs.wasNull() ? null : projectId);
            vo.setConversationId(rs.getString("conversation_id"));
            vo.setQuestion(rs.getString("question"));
            vo.setStatus(rs.getString("status"));
            vo.setHealthSummary(extractHealthSummary(rs.getString("result_json")));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return vo;
        };
    }

    private Map<String, Object> sanitizeResultForTrace(Map<String, Object> source) {
        Object sanitized = sanitizeTraceValue(source);
        if (sanitized instanceof Map<?, ?> map) {
            return stringKeyMap(map);
        }
        return new LinkedHashMap<>();
    }

    private Object sanitizeTraceValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((rawKey, itemValue) -> {
                if (rawKey == null) return;
                String key = String.valueOf(rawKey);
                String normalized = normalizeTraceKey(key);
                if (isForbiddenTraceKey(normalized)) {
                    if ("input".equals(normalized) || "output".equals(normalized)) {
                        String hash = traceValueHash(itemValue);
                        if (hash != null) sanitized.put(normalized + "Hash", hash);
                    }
                    return;
                }
                if ("memorycandidates".equals(normalized)) {
                    sanitized.put(key, sanitizeMemoryCandidates(itemValue));
                    return;
                }
                if (MEMORY_TRACE_TOP_LEVEL_NUMBER_KEYS.contains(key)) {
                    if (itemValue instanceof Number number) sanitized.put(key, number);
                    return;
                }
                if ("memory".equals(normalized)) {
                    Object healthStatus = sanitizeMemoryField("status", itemValue);
                    if (healthStatus != null) sanitized.put(key, healthStatus);
                    return;
                }
                if (normalized.contains("memory")) {
                    if (isKnownMemoryContainerKey(key)) {
                        Object safeMemory = sanitizeMemoryContext(itemValue);
                        if (!isEmptySanitizedValue(safeMemory)) sanitized.put(key, safeMemory);
                    } else if (MEMORY_TRACE_TEXT_LIST_KEYS.contains(key)) {
                        List<String> safeValues = sanitizeMemoryTextList(itemValue);
                        if (!safeValues.isEmpty()) sanitized.put(key, safeValues);
                    }
                    return;
                }
                Object safeValue = sanitizeGeneralTraceField(key, itemValue);
                if (!isEmptySanitizedValue(safeValue) || "taskGraph".equals(key) || "toolRuns".equals(key)) {
                    sanitized.put(key, safeValue);
                }
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(50).map(this::sanitizeTraceValue).filter(item -> !isEmptySanitizedValue(item)).toList();
        }
        return null;
    }

    private Object sanitizeGeneralTraceField(String key, Object value) {
        String normalized = normalizeTraceKey(key);
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            if (TRACE_SUMMARY_KEYS.contains(normalized)) {
                return sanitizeTraceSummary(text);
            }
            if (TRACE_MODEL_KEYS.contains(normalized)) {
                return sanitizeTraceModel(text);
            }
            if (TRACE_PROVIDER_WIRE_KEYS.contains(normalized)) {
                return sanitizeProviderWire(text);
            }
            if (TRACE_TOKEN_KEYS.contains(normalized)) {
                return sanitizeTraceToken(text);
            }
            if (TRACE_IDENTIFIER_KEYS.contains(normalized)) {
                return sanitizeTraceIdentifier(text);
            }
            return null;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (value instanceof List<?> list && TRACE_TEXT_LIST_KEYS.contains(normalized)) {
                return sanitizeTraceTokenList(list);
            }
            return sanitizeTraceValue(value);
        }
        return null;
    }

    private List<String> sanitizeTraceTokenList(List<?> values) {
        return values.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(this::sanitizeTraceToken)
            .filter(Objects::nonNull)
            .limit(50)
            .toList();
    }

    private boolean isForbiddenTraceKey(String normalized) {
        if ("contenthash".equals(normalized) || "contextused".equals(normalized)
            || "contextbudget".equals(normalized) || "memorycontext".equals(normalized)
            || TRACE_SAFE_COUNT_KEYS.contains(normalized)) {
            return false;
        }
        if (normalized.contains("apikey") || normalized.contains("authorization") || normalized.contains("credential")
            || normalized.contains("cookie") || normalized.contains("password") || normalized.contains("secret")
            || normalized.contains("token")) {
            return true;
        }
        return TRACE_BODY_KEYS.contains(normalized)
            || normalized.startsWith("answer")
            || normalized.startsWith("chunk")
            || normalized.startsWith("file")
            || normalized.startsWith("input")
            || normalized.startsWith("instruction")
            || normalized.startsWith("output")
            || normalized.startsWith("payload")
            || normalized.startsWith("prompt")
            || normalized.startsWith("upload")
            || normalized.endsWith("body")
            || normalized.endsWith("content")
            || normalized.endsWith("markdown")
            || normalized.endsWith("text");
    }

    private String normalizeTraceKey(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String sanitizeTraceSummary(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        String redacted = TRACE_CREDENTIAL_VALUE.matcher(normalized).replaceAll("[redacted]");
        return redacted.substring(0, Math.min(200, redacted.length()));
    }

    private String sanitizeTraceToken(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || TRACE_CREDENTIAL_VALUE.matcher(normalized).find() || !MEMORY_TRACE_TOKEN.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private String sanitizeTraceModel(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || TRACE_CREDENTIAL_VALUE.matcher(normalized).find()
            || !TRACE_MODEL_TOKEN.matcher(normalized).matches()
            || normalized.startsWith("/") || normalized.contains("\\")
            || normalized.contains("..") || normalized.regionMatches(true, 0, "file:", 0, 5)) {
            return null;
        }
        return normalized;
    }

    private String sanitizeProviderWire(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return "responses".equals(normalized) || "chat_completions".equals(normalized)
            ? normalized
            : null;
    }

    private String sanitizeTraceIdentifier(String value) {
        return sanitizeTraceToken(value);
    }

    private String sanitizeTraceQuestion(String question) {
        String normalized = trimToNull(question);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 200 || normalized.contains("\n") || TRACE_CREDENTIAL_VALUE.matcher(normalized).find()) {
            String hash = traceValueHash(normalized);
            return hash == null ? "[redacted-content]" : "[redacted-content sha256=" + hash + " chars=" + normalized.length() + "]";
        }
        return normalized;
    }

    private String traceValueHash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(toJson(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "trace hash algorithm unavailable");
        }
    }

    private List<Map<String, Object>> sanitizeMemoryCandidates(Object value) {
        if (!(value instanceof List<?> candidates)) {
            return List.of();
        }
        Set<String> allowed = Set.of(
            "scope", "type", "memoryType", "confidence", "factKey", "candidateKey", "status",
            "lifecycleStatus", "sourceTraceId"
        );
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Object candidateValue : candidates) {
            if (!(candidateValue instanceof Map<?, ?> candidate)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            candidate.forEach((key, itemValue) -> {
                String normalizedKey = key == null ? null : String.valueOf(key);
                if (normalizedKey == null || !allowed.contains(normalizedKey)) return;
                Object safeValue = sanitizeMemoryField(normalizedKey, itemValue);
                if (safeValue != null) item.put(normalizedKey, safeValue);
            });
            if (!item.isEmpty()) sanitized.add(item);
        }
        return sanitized;
    }

    private Object sanitizeMemoryContext(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, itemValue) -> {
                String normalizedKey = key == null ? null : String.valueOf(key);
                if (normalizedKey == null) return;
                Object safeValue = sanitizeMemoryField(normalizedKey, itemValue);
                if (!isEmptySanitizedValue(safeValue)) sanitized.put(normalizedKey, safeValue);
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(item -> item instanceof Map<?, ?> || item instanceof List<?>)
                .map(this::sanitizeMemoryContext)
                .filter(item -> !isEmptySanitizedValue(item))
                .toList();
        }
        return null;
    }

    private Object sanitizeMemoryField(String key, Object value) {
        if (MEMORY_TRACE_BOOLEAN_KEYS.contains(key) && value instanceof Boolean bool) return bool;
        if (MEMORY_TRACE_NUMBER_KEYS.contains(key) && value instanceof Number number) return number;
        if ("reason".equals(key) && value instanceof String reason) {
            String normalized = trimToNull(reason);
            return normalized != null
                && (MEMORY_TRACE_REASONS.contains(normalized) || MEMORY_TRACE_EXCEPTION.matcher(normalized).matches())
                ? normalized
                : null;
        }
        if ("errorType".equals(key) && value instanceof String errorType) {
            String normalized = trimToNull(errorType);
            return normalized != null && MEMORY_TRACE_EXCEPTION.matcher(normalized).matches()
                ? normalized
                : null;
        }
        if (MEMORY_TRACE_TEXT_KEYS.contains(key) && value instanceof String text) {
            String normalized = trimToNull(text);
            return normalized != null && MEMORY_TRACE_TOKEN.matcher(normalized).matches() ? normalized : null;
        }
        if (MEMORY_TRACE_TEXT_LIST_KEYS.contains(key)) return sanitizeMemoryTextList(value);
        if (MEMORY_TRACE_CONTAINER_KEYS.contains(key)) return sanitizeMemoryContext(value);
        return null;
    }

    private List<String> sanitizeMemoryTextList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(this::trimToNull)
            .filter(Objects::nonNull)
            .filter(item -> MEMORY_TRACE_TOKEN.matcher(item).matches())
            .limit(100)
            .toList();
    }

    private boolean isKnownMemoryContainerKey(String key) {
        return MEMORY_TRACE_CONTAINER_KEYS.contains(key)
            || Set.of("memoryContext", "memoryDiagnostics").contains(key);
    }

    private boolean isEmptySanitizedValue(Object value) {
        return value == null
            || (value instanceof Map<?, ?> map && map.isEmpty())
            || (value instanceof List<?> list && list.isEmpty());
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return result;
    }

    private Map<String, String> extractHealthSummary(String resultJson) {
        Map<String, Object> result = parseResultJson(resultJson);
        Object health = result.get("health");
        Object trace = result.get("trace");
        if (trace instanceof Map<?, ?> traceMap && traceMap.get("health") instanceof Map<?, ?>) {
            health = traceMap.get("health");
        }
        Map<String, String> summary = new LinkedHashMap<>();
        putHealthValue(summary, "model", firstStringValue(health, "model"));
        putHealthValue(summary, "tools", firstStringValue(health, "tools"));
        putHealthValue(summary, "evidence", firstStringValue(health, "evidence"));
        putHealthValue(summary, "memory", firstStringValue(health, "memory"));
        putHealthValue(summary, "experts", firstStringValue(health, "experts"));
        if (!summary.containsKey("model") && Boolean.parseBoolean(String.valueOf(result.get("fallbackUsed")))) {
            summary.put("model", "fallback_used");
        }
        return summary;
    }

    private void putHealthValue(Map<String, String> summary, String key, String value) {
        if (value != null) {
            summary.put(key, value);
        }
    }

    private String buildWhere(String status, String keyword, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        String normalizedStatus = trimToNull(status);
        if (normalizedStatus != null) {
            clauses.add("status = ?");
            args.add(normalizedStatus);
        }
        String normalizedKeyword = trimToNull(keyword);
        if (normalizedKeyword != null) {
            clauses.add("(trace_id like ? or question like ? or conversation_id like ?)");
            String like = "%" + normalizedKeyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private int normalizePage(Integer page) {
        if (page == null || page <= 0) {
            return 1;
        }
        return Math.min(page, 10000);
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 50);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void hydrateExpandedTraceSections(KnowledgeAgentTraceVO vo, String resultJson) {
        Map<String, Object> result = parseResultJson(resultJson);
        if (result.isEmpty()) {
            return;
        }
        vo.setIntentDecision(toJsonOrNull(result.get("intentDecision")));
        vo.setContextUsed(toJsonOrNull(result.get("contextUsed")));
        vo.setMemoryUsed(toJsonOrNull(result.get("memoryUsed")));
        vo.setMemoryDiagnostics(toJsonOrNull(result.get("memoryDiagnostics")));
        vo.setRetrievalDiagnostics(toJsonOrNull(result.get("retrievalDiagnostics")));
        vo.setSourcePolicy(toJsonOrNull(result.get("sourcePolicy")));
        vo.setSupervisorDecision(toJsonOrNull(result.get("supervisorDecision")));
        vo.setMemoryCandidates(toJsonOrNull(result.get("memoryCandidates")));
        vo.setMcpToolCalls(toJsonOrNull(firstPresent(result, "mcpToolCalls", "toolRuns")));
        vo.setToolPermissionDecisions(toJsonOrNull(result.get("toolPermissionDecisions")));
        vo.setEvidenceContract(toJsonOrNull(result.get("evidenceContract")));
        vo.setSelectedSnapshotGroup(toJsonOrNull(result.get("selectedSnapshotGroup")));
        vo.setRejectedSnapshotGroups(toJsonOrNull(result.get("rejectedSnapshotGroups")));
        vo.setSpecialistAgentResults(toJsonOrNull(result.get("specialistAgentResults")));
        vo.setSkillMediation(toJsonOrNull(result.get("skillMediation")));
        vo.setSkillBom(toJsonOrNull(result.get("skillBom")));
        vo.setSelectedExperts(toJsonOrNull(result.get("selectedExperts")));
        vo.setExpertRouter(toJsonOrNull(result.get("expertRouter")));
        vo.setFinalAnswerBoundary(toJsonOrNull(result.get("finalAnswerBoundary")));
        vo.setSnapshotTime(firstStringValue(result.get("sourcePolicy"), "snapshotTime"));
        if (vo.getSnapshotTime() == null) {
            vo.setSnapshotTime(firstStringValue(result, "snapshotTime"));
        }
    }

    private Map<String, Object> parseResultJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(resultJson, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJsonOrNull(Object value) {
        return value == null ? null : toJson(value);
    }

    private String traceSummary(Map<String, Object> result) {
        String supervisor = firstStringValue(result.get("supervisorDecision"), "summary");
        String boundary = firstStringValue(result.get("finalAnswerBoundary"), "summary");
        List<String> parts = new ArrayList<>();
        if (supervisor != null) {
            parts.add(supervisor);
        }
        if (boundary != null) {
            parts.add(boundary);
        }
        if (!parts.isEmpty()) {
            return String.join("; ", parts);
        }
        String status = firstStringValue(result.get("supervisorDecision"), "status");
        if (status != null) {
            return "supervisorDecision=" + status;
        }
        Object taskGraph = result.get("taskGraph");
        return taskGraph == null ? null : toJsonOrNull(taskGraph);
    }

    private List<String> extractSelectedSkills(Map<String, Object> result) {
        Set<String> skills = new LinkedHashSet<>();
        Object skillBom = result.get("skillBom");
        if (skillBom instanceof Map<?, ?> bom) {
            collectStringValues(bom.get("skills"), skills, "skillId", "name");
            return new ArrayList<>(skills);
        }
        collectStringValues(result.get("selectedSkills"), skills, "skillId", "name");
        return new ArrayList<>(skills);
    }

    private List<String> extractSelectedTools(Map<String, Object> result) {
        Set<String> tools = new LinkedHashSet<>();
        collectStringValues(firstPresent(result, "mcpToolCalls", "toolRuns"), tools, "name", "tool", "toolName");
        return new ArrayList<>(tools);
    }

    private void collectStringValues(Object value, Set<String> target, String... objectKeys) {
        if (value instanceof String text && !text.isBlank()) {
            target.add(text);
        } else if (value instanceof Map<?, ?> map) {
            for (String key : objectKeys) {
                Object direct = map.get(key);
                if (direct != null && !String.valueOf(direct).isBlank()) {
                    target.add(String.valueOf(direct));
                    return;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectStringValues(item, target, objectKeys);
            }
        }
    }

    private String firstStringValue(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Object direct = map.get(key);
            if (direct != null && !String.valueOf(direct).isBlank()) {
                return String.valueOf(direct);
            }
            for (Object child : map.values()) {
                String found = firstStringValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                String found = firstStringValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private void requireAdmin() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || !user.getRoles().contains("ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin role required");
        }
    }
}
