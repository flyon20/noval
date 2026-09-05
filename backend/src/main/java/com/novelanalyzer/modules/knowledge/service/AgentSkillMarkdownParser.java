package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentSkillMarkdownParser {

    private static final int MAX_DOCUMENT_CHARS = 250_000;
    private static final int MAX_FRONT_MATTER_CHARS = 16_384;
    private static final int MAX_DESCRIPTION_CHARS = 1_000;
    private static final int MAX_INSTRUCTIONS_CHARS = 200_000;
    private static final int MAX_METADATA_CHARS = 16_384;
    private static final int MAX_METADATA_DEPTH = 4;
    private static final int MAX_METADATA_NODES = 128;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern OPENING_DELIMITER = Pattern.compile("\\A---[ \\t]*\\r?\\n");
    private static final Pattern CLOSING_DELIMITER = Pattern.compile("(?m)^---[ \\t]*\\r?$");
    private static final Set<String> ALLOWED_ROOT_KEYS = Set.of(
        "name",
        "description",
        "license",
        "compatibility",
        "metadata",
        "allowed-tools"
    );
    private static final Map<String, String> TOOL_CAPABILITY_MAP = Map.ofEntries(
        Map.entry("rank.lookup", "market.read"),
        Map.entry("rank.research_pack", "market.read"),
        Map.entry("rank.refresh", "market.refresh"),
        Map.entry("book.search", "book.read"),
        Map.entry("book.research_pack", "book.read"),
        Map.entry("knowledge.vector_search", "book.read"),
        Map.entry("project.resolve", "project.resolve"),
        Map.entry("project.retrieve", "project.retrieve"),
        Map.entry("project.foreshadowing.list", "project.continuity.read"),
        Map.entry("project.timeline_lookup", "project.continuity.read"),
        Map.entry("project.character_state_lookup", "project.continuity.read"),
        Map.entry("project.world_rule_lookup", "project.continuity.read"),
        Map.entry("memory.project_context", "memory.project.read"),
        Map.entry("skill.lookup", "skill.activate"),
        Map.entry("reader.simulate_feedback", "review.reader"),
        Map.entry("editor.risk_check", "review.editor")
    );

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public AgentSkillMarkdownParser() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.jsonMapper = new ObjectMapper();
    }

    public boolean isStandardSkill(String markdown) {
        if (markdown == null) return false;
        String source = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        return OPENING_DELIMITER.matcher(source).find();
    }

    public ParsedSkill parse(String markdown) {
        FrontMatterDocument document = splitDocument(markdown);
        Map<String, Object> frontMatter = parseFrontMatter(document.frontMatter());
        if (!ALLOWED_ROOT_KEYS.containsAll(frontMatter.keySet())) {
            throw new IllegalArgumentException("skill front matter contains unsupported fields");
        }

        String name = requiredString(frontMatter.get("name"), "name", 64);
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("skill name must use lowercase letters, numbers and hyphens");
        }
        String description = requiredString(
            frontMatter.get("description"),
            "description",
            MAX_DESCRIPTION_CHARS
        );
        String instructions = document.body().strip();
        if (instructions.isEmpty()) {
            throw new IllegalArgumentException("skill instructions body is required");
        }
        if (instructions.length() > MAX_INSTRUCTIONS_CHARS) {
            throw new IllegalArgumentException("skill instructions body is too large");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        Object customMetadata = frontMatter.get("metadata");
        if (customMetadata != null) {
            if (!(customMetadata instanceof Map<?, ?> customMap)) {
                throw new IllegalArgumentException("skill metadata must be an object");
            }
            metadata.put("metadata", normalizeMetadataMap(customMap));
        }
        putOptionalString(metadata, "license", frontMatter.get("license"), 1_000);
        putOptionalString(metadata, "compatibility", frontMatter.get("compatibility"), 1_000);
        metadata.put("legacyFormat", false);
        try {
            if (jsonMapper.writeValueAsString(metadata).length() > MAX_METADATA_CHARS) {
                throw new IllegalArgumentException("skill metadata is too large");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("skill metadata cannot be serialized", exception);
        }

        return new ParsedSkill(
            name,
            description,
            instructions,
            requestedCapabilities(frontMatter.get("allowed-tools")),
            metadata
        );
    }

    private FrontMatterDocument splitDocument(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("skill markdown is required");
        }
        String source = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        if (source.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("skill markdown is too large");
        }
        Matcher opening = OPENING_DELIMITER.matcher(source);
        if (!opening.find()) {
            throw new IllegalArgumentException("skill front matter is required at the file start");
        }
        Matcher closing = CLOSING_DELIMITER.matcher(source);
        closing.region(opening.end(), source.length());
        if (!closing.find()) {
            throw new IllegalArgumentException("skill front matter closing delimiter is required");
        }
        String frontMatter = source.substring(opening.end(), closing.start());
        if (frontMatter.length() > MAX_FRONT_MATTER_CHARS) {
            throw new IllegalArgumentException("skill front matter is too large");
        }
        return new FrontMatterDocument(frontMatter, source.substring(closing.end()));
    }

    private Map<String, Object> parseFrontMatter(String source) {
        try {
            Map<String, Object> parsed = yamlMapper.readValue(
                source,
                new TypeReference<LinkedHashMap<String, Object>>() {}
            );
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("skill front matter is invalid", exception);
        }
    }

    private String requiredString(Object value, String field, int maxChars) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("skill " + field + " is required");
        }
        String normalized = text.strip();
        if (normalized.length() > maxChars) {
            throw new IllegalArgumentException("skill " + field + " is too long");
        }
        return normalized;
    }

    private void putOptionalString(Map<String, Object> target, String key, Object value, int maxChars) {
        if (value == null) return;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("skill " + key + " must be a string");
        }
        String normalized = text.strip();
        if (normalized.isEmpty() || normalized.length() > maxChars) {
            throw new IllegalArgumentException("skill " + key + " is invalid");
        }
        target.put(key, normalized);
    }

    private Map<String, Object> normalizeMetadataMap(Map<?, ?> source) {
        AtomicInteger nodes = new AtomicInteger();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException("skill metadata keys must be bounded strings");
            }
            normalized.put(key, normalizeMetadataValue(entry.getValue(), 1, nodes));
        }
        return Collections.unmodifiableMap(normalized);
    }

    private Object normalizeMetadataValue(Object value, int depth, AtomicInteger nodes) {
        if (depth > MAX_METADATA_DEPTH || nodes.incrementAndGet() > MAX_METADATA_NODES) {
            throw new IllegalArgumentException("skill metadata exceeds structural limits");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof String text) {
            if (text.length() > 2_000) {
                throw new IllegalArgumentException("skill metadata text is too long");
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > 128) {
                    throw new IllegalArgumentException("skill metadata keys must be bounded strings");
                }
                normalized.put(key, normalizeMetadataValue(entry.getValue(), depth + 1, nodes));
            }
            return Collections.unmodifiableMap(normalized);
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > 64) {
                throw new IllegalArgumentException("skill metadata collection is too large");
            }
            List<Object> normalized = new ArrayList<>();
            for (Object item : collection) {
                normalized.add(normalizeMetadataValue(item, depth + 1, nodes));
            }
            return List.copyOf(normalized);
        }
        throw new IllegalArgumentException("skill metadata contains an unsupported value type");
    }

    private List<String> requestedCapabilities(Object value) {
        if (value == null) return List.of();
        List<String> tools = new ArrayList<>();
        if (value instanceof String text) {
            for (String item : text.split("[\\s,]+")) {
                if (!item.isBlank()) tools.add(item.strip());
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("skill allowed-tools must contain strings");
                }
                tools.add(text.strip());
            }
        } else {
            throw new IllegalArgumentException("skill allowed-tools must be a string or list");
        }
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        for (String tool : tools) {
            String capability = TOOL_CAPABILITY_MAP.get(tool);
            if (capability == null) {
                throw new IllegalArgumentException("skill allowed-tools contains an unknown tool hint: " + tool);
            }
            capabilities.add(capability);
        }
        return List.copyOf(capabilities);
    }

    private record FrontMatterDocument(String frontMatter, String body) {}

    public record ParsedSkill(
        String name,
        String description,
        String instructions,
        List<String> requestedCapabilities,
        Map<String, Object> metadata
    ) {
        public ParsedSkill {
            requestedCapabilities = requestedCapabilities == null ? List.of() : List.copyOf(requestedCapabilities);
            metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }
}
