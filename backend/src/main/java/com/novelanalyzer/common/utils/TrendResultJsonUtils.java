package com.novelanalyzer.common.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrendResultJsonUtils {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern TRAILING_RANK_PATTERN = Pattern.compile("#(\\d+)$");

    private TrendResultJsonUtils() {
    }

    public static Map<String, Object> recoverThemeResultMap(ObjectMapper objectMapper,
                                                            Map<String, Object> rawResult,
                                                            String... extraCandidates) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (rawResult != null) {
            merged.putAll(rawResult);
        }

        String[] candidates = new String[4 + (extraCandidates == null ? 0 : extraCandidates.length)];
        candidates[0] = asString(merged.get("detailContent"));
        candidates[1] = asString(merged.get("trendPreview"));
        candidates[2] = asString(merged.get("content"));
        candidates[3] = asString(merged.get("raw"));
        if (extraCandidates != null && extraCandidates.length > 0) {
            System.arraycopy(extraCandidates, 0, candidates, 4, extraCandidates.length);
        }

        Map<String, Object> parsedEmbedded = parseFirstJsonMap(objectMapper, candidates);

        if (!parsedEmbedded.isEmpty()) {
            parsedEmbedded.forEach((key, value) -> {
                if (shouldReplace(merged.get(key), value)) {
                    merged.put(key, value);
                }
            });
        }

        return merged;
    }

    public static boolean hasReusableThemePayload(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return false;
        }

        boolean hasReadableSummary = hasReadableText(result.get("summary"))
            || hasReadableText(result.get("boardSummary"))
            || hasReadableText(result.get("comparisonSummary"));
        boolean hasStructuredCollections = hasItems(result.get("historicalWordCloud"))
            || hasItems(result.get("themeDistribution"))
            || hasItems(result.get("themeTable"))
            || hasItems(result.get("hotBooks"))
            || hasItems(result.get("insightCards"))
            || hasItems(result.get("snapshotComparisons"))
            || hasItems(result.get("snapshotComparison"));

        return hasReadableSummary || hasStructuredCollections;
    }

    public static String extractThemeSummary(Map<String, Object> result) {
        return firstNonBlank(
            readableText(result == null ? null : result.get("summary")),
            readableText(result == null ? null : result.get("boardSummary")),
            extractReadableText(result == null ? null : result.get("summary")),
            extractReadableText(result == null ? null : result.get("boardSummary")),
            extractReadableText(result == null ? null : result.get("comparisonSummary"))
        );
    }

    public static String extractThemeBoardSummary(Map<String, Object> result) {
        return firstNonBlank(
            readableText(result == null ? null : result.get("boardSummary")),
            extractReadableText(result == null ? null : result.get("summary")),
            readableText(result == null ? null : result.get("summary")),
            extractReadableText(result == null ? null : result.get("comparisonSummary"))
        );
    }

    public static String extractThemeTrendPreview(Map<String, Object> result) {
        return firstNonBlank(
            readableText(result == null ? null : result.get("trendPreview")),
            extractThemeBoardSummary(result),
            extractThemeSummary(result)
        );
    }

    public static String extractThemeDetailContent(Map<String, Object> result, String fallbackContent) {
        return firstNonBlank(
            readableText(result == null ? null : result.get("detailContent")),
            readableText(result == null ? null : result.get("content")),
            extractThemeBoardSummary(result),
            extractThemeSummary(result),
            readableText(result == null ? null : result.get("comparisonSummary")),
            readableText(fallbackContent)
        );
    }

    public static List<Map<String, Object>> normalizeThemeWordCloud(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("name", firstNonBlank(
                    readableText(item.get("name")),
                    readableText(item.get("word")),
                    readableText(item.get("keyword"))
                ));
                normalized.put("value", firstNonNullLong(
                    asLong(item.get("value")),
                    asLong(item.get("count")),
                    asLong(item.get("frequency"))
                ));
                return normalized;
            })
            .filter(item -> item.get("name") != null && item.get("value") != null)
            .toList();
    }

    public static List<Map<String, Object>> normalizeThemeDistribution(Object rawValue, Object themeTableFallback) {
        List<Map<String, Object>> normalized = asListOfMap(rawValue).stream()
            .map(TrendResultJsonUtils::normalizeThemeDistributionItem)
            .filter(Objects::nonNull)
            .toList();
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return normalizeThemeTable(themeTableFallback).stream()
            .map(item -> {
                Map<String, Object> derived = new LinkedHashMap<>();
                derived.put("theme", item.get("theme"));
                derived.put("count", item.get("count"));
                derived.put("ratio", item.get("ratio"));
                copyOptionalTextFields(
                    derived,
                    item,
                    "laneLevel",
                    "systemType",
                    "systemPresence",
                    "systemPersona",
                    "interactionMode",
                    "feedbackLoop",
                    "payoffMechanism",
                    "emotionAnchor",
                    "microInnovation",
                    "antiRoutineDesign",
                    "avoidedPoisonPoints"
                );
                copyOptionalStringList(derived, item, "microTags");
                return derived;
            })
            .toList();
    }

    public static List<Map<String, Object>> normalizeThemeTable(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                String theme = firstNonBlank(
                    readableText(item.get("theme")),
                    readableText(item.get("category"))
                );
                if (theme == null) {
                    return null;
                }

                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("theme", theme);
                normalized.put("count", asLong(item.get("count")));
                normalized.put("ratio", parseRatio(item.get("ratio"), item.get("percentage")));
                copyOptionalTextFields(
                    normalized,
                    item,
                    "laneLevel",
                    "systemType",
                    "systemPresence",
                    "systemPersona",
                    "interactionMode",
                    "feedbackLoop",
                    "payoffMechanism",
                    "emotionAnchor",
                    "microInnovation",
                    "antiRoutineDesign",
                    "avoidedPoisonPoints"
                );
                copyOptionalStringList(normalized, item, "microTags");
                normalized.put("trend", firstNonBlank(
                    readableText(item.get("trend")),
                    readableText(item.get("trendDirection"))
                ));
                List<Map<String, Object>> representativeBooks = normalizeThemeHotBooks(item.get("representativeBooks"));
                if (representativeBooks.isEmpty()) {
                    representativeBooks = normalizeLegacyExamples(item.get("top3Examples"), theme);
                }
                normalized.put("representativeBooks", representativeBooks);
                return normalized;
            })
            .filter(Objects::nonNull)
            .toList();
    }

    public static List<Map<String, Object>> normalizeThemeHotBooks(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> normalizeThemeHotBook(item, null))
            .filter(Objects::nonNull)
            .toList();
    }

    private static Map<String, Object> parseFirstJsonMap(ObjectMapper objectMapper, String... candidates) {
        if (candidates == null) {
            return Map.of();
        }

        for (String candidate : candidates) {
            String jsonCandidate = extractJsonObjectCandidate(candidate);
            if (jsonCandidate == null) {
                continue;
            }

            try {
                Map<String, Object> parsed = objectMapper.readValue(jsonCandidate, MAP_TYPE);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                Map<String, Object> normalizedParsed = tryParseNormalizedJsonMap(objectMapper, jsonCandidate);
                if (!normalizedParsed.isEmpty()) {
                    return normalizedParsed;
                }
            }
        }

        return Map.of();
    }

    private static Map<String, Object> tryParseNormalizedJsonMap(ObjectMapper objectMapper, String jsonCandidate) {
        String normalized = escapeControlCharactersInsideStrings(jsonCandidate);
        if (normalized.equals(jsonCandidate)) {
            return Map.of();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(normalized, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static boolean shouldReplace(Object existingValue, Object candidateValue) {
        if (candidateValue == null) {
            return false;
        }
        if (existingValue == null) {
            return true;
        }
        if (existingValue instanceof String existingText) {
            return existingText.isBlank() || extractJsonObjectCandidate(existingText) != null;
        }
        if (existingValue instanceof Collection<?> existingCollection) {
            return existingCollection.isEmpty();
        }
        if (existingValue instanceof Map<?, ?> existingMap) {
            return existingMap.isEmpty();
        }
        return false;
    }

    private static boolean hasReadableText(Object value) {
        String text = readableText(value);
        return text != null && !text.isBlank();
    }

    private static String readableText(Object value) {
        String text = asString(value);
        if (text == null || text.isBlank() || looksLikeJsonishText(text)) {
            return null;
        }
        return text;
    }

    private static boolean hasItems(Object value) {
        return value instanceof Collection<?> collection && !collection.isEmpty();
    }

    private static boolean looksLikeJsonishText(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.trim();
        return normalized.startsWith("{")
            || normalized.startsWith("[")
            || normalized.startsWith("```json")
            || normalized.startsWith("```")
            || normalized.contains("\"summary\"")
            || normalized.contains("\\\"summary\\\"")
            || normalized.contains("\":");
    }

    private static String extractJsonObjectCandidate(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String normalized = content.trim()
            .replaceFirst("^```json\\s*", "")
            .replaceFirst("^```\\s*", "")
            .replaceFirst("\\s*```$", "")
            .trim();

        int firstBrace = normalized.indexOf('{');
        int lastBrace = normalized.lastIndexOf('}');

        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return null;
        }

        String candidate = normalized.substring(firstBrace, lastBrace + 1).trim();
        return candidate.startsWith("{") && candidate.endsWith("}") ? candidate : null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMap(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private static Map<String, Object> normalizeThemeDistributionItem(Map<String, Object> item) {
        String theme = firstNonBlank(
            readableText(item.get("theme")),
            readableText(item.get("category"))
        );
        if (theme == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("theme", theme);
        normalized.put("count", asLong(item.get("count")));
        normalized.put("ratio", parseRatio(item.get("ratio"), item.get("percentage")));
        copyOptionalTextFields(
            normalized,
            item,
            "laneLevel",
            "systemType",
            "systemPresence",
            "systemPersona",
            "interactionMode",
            "feedbackLoop",
            "payoffMechanism",
            "emotionAnchor",
            "microInnovation",
            "antiRoutineDesign",
            "avoidedPoisonPoints"
        );
        copyOptionalStringList(normalized, item, "microTags");
        return normalized;
    }

    private static List<Map<String, Object>> normalizeLegacyExamples(Object rawValue, String defaultTheme) {
        if (!(rawValue instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            String example = extractReadableText(item);
            if (example == null) {
                continue;
            }
            Map<String, Object> normalizedItem = parseLegacyExample(example, defaultTheme);
            if (normalizedItem != null) {
                normalized.add(normalizedItem);
            }
        }
        return normalized;
    }

    private static Map<String, Object> parseLegacyExample(String example, String defaultTheme) {
        String trimmed = example == null ? null : example.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }
        int openParen = trimmed.indexOf('(');
        int closeParen = trimmed.lastIndexOf(')');
        String bookName = openParen > 0 ? trimmed.substring(0, openParen).trim() : trimmed;
        String extra = openParen > 0 && closeParen > openParen
            ? trimmed.substring(openParen + 1, closeParen).trim()
            : null;
        if (bookName.isBlank()) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("theme", defaultTheme);
        normalized.put("bookName", bookName);
        normalized.put("author", null);
        normalized.put("rankNo", extractRankNo(extra));
        normalized.put("rankLabel", extra);
        normalized.put("reason", extra == null || extra.isBlank() ? trimmed : extra);
        return normalized;
    }

    private static Map<String, Object> normalizeThemeHotBook(Map<String, Object> item, String defaultTheme) {
        String bookName = firstNonBlank(
            readableText(item.get("bookName")),
            readableText(item.get("title")),
            readableText(item.get("name"))
        );
        if (bookName == null) {
            return null;
        }
        String rankLabel = firstNonBlank(
            readableText(item.get("rankLabel")),
            extractReadableText(item.get("rankTrend")),
            readableText(item.get("ranking"))
        );
        Integer rankNo = firstNonNullInteger(
            asInteger(item.get("rankNo")),
            extractRankNo(rankLabel)
        );
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("theme", firstNonBlank(readableText(item.get("theme")), defaultTheme));
        normalized.put("bookName", bookName);
        normalized.put("author", readableText(item.get("author")));
        normalized.put("rankNo", rankNo);
        normalized.put("rankLabel", rankLabel);
        copyOptionalTextFields(
            normalized,
            item,
            "laneLevel",
            "systemType",
            "systemPresence",
            "systemPersona",
            "interactionMode",
            "feedbackLoop",
            "payoffMechanism",
            "emotionAnchor",
            "microInnovation",
            "antiRoutineDesign",
            "avoidedPoisonPoints"
        );
        copyOptionalStringList(normalized, item, "microTags");
        normalized.put("reason", firstNonBlank(
            readableText(item.get("reason")),
            readableText(item.get("coreEmotion")),
            readableText(item.get("sellingPointAnalysis")),
            readableText(item.get("analysis"))
        ));
        return normalized;
    }

    private static void copyOptionalTextFields(Map<String, Object> target,
                                               Map<String, Object> source,
                                               String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            String value = readableText(source.get(key));
            if (value != null) {
                target.put(key, value);
            }
        }
    }

    private static void copyOptionalStringList(Map<String, Object> target,
                                               Map<String, Object> source,
                                               String key) {
        if (target == null || source == null || key == null || key.isBlank()) {
            return;
        }
        List<String> values = normalizeStringList(source.get(key));
        if (!values.isEmpty()) {
            target.put(key, values);
        }
    }

    private static List<String> normalizeStringList(Object rawValue) {
        if (!(rawValue instanceof Collection<?> collection) || collection.isEmpty()) {
            return List.of();
        }
        return collection.stream()
            .map(TrendResultJsonUtils::readableText)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private static String extractReadableText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return readableText(text);
        }
        if (value instanceof Map<?, ?> map) {
            String fromPreferredKeys = firstNonBlank(
                extractReadableText(map.get("coreTrend")),
                extractReadableText(map.get("overview")),
                extractReadableText(map.get("headline")),
                extractReadableText(map.get("conclusion")),
                extractReadableText(map.get("summary")),
                extractReadableText(map.get("detailContent")),
                extractReadableText(map.get("content")),
                extractReadableText(map.get("text")),
                extractReadableText(map.get("value")),
                extractReadableText(map.get("analysis")),
                extractReadableText(map.get("insight"))
            );
            if (fromPreferredKeys != null) {
                return fromPreferredKeys;
            }
            for (Object nestedValue : map.values()) {
                String nested = extractReadableText(nestedValue);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String nested = extractReadableText(item);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static Double parseRatio(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            Double parsed = asDouble(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer extractRankNo(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = TRAILING_RANK_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Long firstNonNullLong(Long... values) {
        if (values == null) {
            return null;
        }
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer firstNonNullInteger(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String escapeControlCharactersInsideStrings(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(content.length() + 32);
        boolean insideString = false;
        boolean escaped = false;

        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);

            if (escaped) {
                builder.append(current);
                escaped = false;
                continue;
            }

            if (current == '\\') {
                builder.append(current);
                escaped = true;
                continue;
            }

            if (current == '"') {
                builder.append(current);
                insideString = !insideString;
                continue;
            }

            if (!insideString) {
                builder.append(current);
                continue;
            }

            switch (current) {
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }

        return builder.toString();
    }
}
