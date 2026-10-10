package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Public host progress only; no tool arguments, observations or provider usage. */
public final class KnowledgeChatToolProgress {
    public static final int MAX_ITEMS = 64;
    private static final Pattern ITEM_ID = Pattern.compile("tool_[0-9a-f]{32}");
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final Map<String, String> MESSAGES = Map.of(
        "running", "正在调用工具", "completed", "工具调用完成", "failed", "工具调用失败",
        "cancelled", "工具调用已取消", "unknown", "工具结果待确认"
    );
    private static final ObjectMapper JSON = new ObjectMapper();

    private KnowledgeChatToolProgress() {}

    public static Map<String, Object> sanitize(Map<?, ?> source) {
        if (source == null || !Integer.valueOf(1).equals(source.get("schemaVersion"))
            || !"tool".equals(source.get("itemType"))
            || !(source.get("itemId") instanceof String id) || !ITEM_ID.matcher(id).matches()
            || !(source.get("itemStatus") instanceof String status) || !MESSAGES.containsKey(status)) {
            return Map.of();
        }
        String name = source.get("toolName") instanceof String value && TOOL_NAME.matcher(value).matches()
            ? value : "tool";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("phase", "tools");
        result.put("message", MESSAGES.get(status));
        result.put("itemId", id);
        result.put("itemType", "tool");
        result.put("itemStatus", status);
        result.put("toolName", name);
        return result;
    }

    public static String eventKey(Map<String, Object> item) {
        // Every item has at most a start and one terminal event, even across replay.
        return "tool-item:" + item.get("itemId") + ":"
            + ("running".equals(item.get("itemStatus")) ? "running" : "terminal");
    }

    public static List<Map<String, Object>> merge(List<Map<String, Object>> existing, Map<?, ?> update) {
        Map<String, Object> item = sanitize(update);
        LinkedHashMap<String, Map<String, Object>> items = new LinkedHashMap<>();
        for (Map<String, Object> raw : existing) {
            Map<String, Object> safe = sanitize(raw);
            if (!safe.isEmpty()) items.put((String) safe.get("itemId"), safe);
        }
        if (!item.isEmpty()) {
            String id = (String) item.get("itemId");
            Map<String, Object> previous = items.get(id);
            if (previous == null || "running".equals(previous.get("itemStatus"))) items.put(id, item);
        }
        while (items.size() > MAX_ITEMS) items.remove(items.keySet().iterator().next());
        return List.copyOf(items.values());
    }

    public static List<Map<String, Object>> readSnapshot(String json) {
        if (json == null || json.isBlank() || json.length() > 65_536) return List.of();
        Map<String, Object> snapshot = readObject(json);
        if (!Integer.valueOf(1).equals(snapshot.get("schemaVersion"))
            || !(snapshot.get("items") instanceof List<?> rawItems)) return List.of();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : rawItems.subList(Math.max(0, rawItems.size() - MAX_ITEMS), rawItems.size())) {
            if (raw instanceof Map<?, ?> item) items = merge(items, item);
        }
        return List.copyOf(items);
    }

    public static Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> result = JSON.readValue(json, new TypeReference<>() {});
            return result == null ? Map.of() : result;
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }
}
