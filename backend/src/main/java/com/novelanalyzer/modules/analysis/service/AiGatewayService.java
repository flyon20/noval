package com.novelanalyzer.modules.analysis.service;

import dev.langchain4j.model.input.PromptTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AiGatewayService {

    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public AiGatewayService(RestTemplate aiRestTemplate, AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiRestTemplate = aiRestTemplate;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public AiInvokeResult analyze(PromptConfigEntity promptConfig, String text, String analysisType) {
        String renderedPrompt = renderPrompt(promptConfig.getPromptContent(), text, analysisType);
        AiInvokeResult difyResult = invokeDify(promptConfig, renderedPrompt, analysisType);
        if (difyResult != null) {
            return difyResult;
        }
        return buildFallbackResult(promptConfig, analysisType, renderedPrompt);
    }

    private String renderPrompt(String template, String text, String analysisType) {
        String safeTemplate = template == null || template.isBlank() ? "{{content}}" : template;
        return PromptTemplate.from(safeTemplate)
            .apply(Map.of("content", text, "analysisType", analysisType))
            .text();
    }

    @SuppressWarnings("unchecked")
    private AiInvokeResult invokeDify(PromptConfigEntity promptConfig, String renderedPrompt, String analysisType) {
        String workflowId = promptConfig.getDifyWorkflowId();
        String apiKey = resolveDifyApiKey(promptConfig.getDifyApiKeyRef());
        if (workflowId == null || workflowId.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("inputs", Map.of(
                "content", renderedPrompt,
                "analysisType", analysisType,
                "workflowId", workflowId
            ));
            body.put("response_mode", "blocking");
            body.put("user", "novel-analyzer");

            ResponseEntity<Map> response = aiRestTemplate.postForEntity(
                aiProperties.getDifyBaseUrl() + "/workflows/run",
                new HttpEntity<>(body, headers),
                Map.class
            );
            Map<String, Object> payload = response.getBody();
            if (payload == null) {
                return null;
            }

            Map<String, Object> data = asMap(payload.get("data"));
            Map<String, Object> outputs = asMap(data.get("outputs"));
            String content = firstNonBlank(
                asString(outputs.get("text")),
                asString(outputs.get("result")),
                asString(outputs.get("answer")),
                asString(data.get("answer"))
            );
            if (content == null || content.isBlank()) {
                return null;
            }
            Map<String, Object> resultJson = buildStructuredResult(outputs, content, analysisType, "dify:" + workflowId);

            Integer tokenUsed = Optional.ofNullable(data.get("total_tokens"))
                .map(Object::toString)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                })
                .orElse(Math.max(120, renderedPrompt.length() / 2));

            return AiInvokeResult.of(
                "dify:" + workflowId,
                content,
                tokenUsed,
                resultJson
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private AiInvokeResult buildFallbackResult(PromptConfigEntity promptConfig, String analysisType, String renderedPrompt) {
        String modelName = promptConfig.getModelName() == null || promptConfig.getModelName().isBlank()
            ? aiProperties.getFallbackModel()
            : promptConfig.getModelName();
        String content = analysisType + " analysis result\n"
            + "model: " + modelName + "\n"
            + "summary: " + shortText(renderedPrompt, 400);
        Map<String, Object> resultJson = Map.of(
            "analysisType", analysisType,
            "modelName", modelName,
            "summary", shortText(renderedPrompt, 200),
            "content", content
        );
        int tokenUsed = Math.max(120, renderedPrompt.length() / 2);
        return AiInvokeResult.of(modelName, content, tokenUsed, resultJson);
    }

    private String resolveDifyApiKey(String keyRef) {
        if (keyRef == null || keyRef.isBlank()) {
            String globalKeyRef = aiProperties.getDifyApiKeyRef();
            if (globalKeyRef == null || globalKeyRef.isBlank()) {
                return System.getenv("DIFY_API_KEY");
            }
            return System.getenv(globalKeyRef);
        }
        return System.getenv(keyRef);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String shortText(String input, int max) {
        if (input == null) {
            return "";
        }
        String clean = input.replaceAll("\\s+", " ").trim();
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, max) + "...";
    }

    private Map<String, Object> buildStructuredResult(Map<String, Object> outputs,
                                                      String content,
                                                      String analysisType,
                                                      String modelName) {
        if (!outputs.isEmpty()) {
            Map<String, Object> normalized = new HashMap<>(outputs);
            normalized.putIfAbsent("analysisType", analysisType);
            normalized.putIfAbsent("modelName", modelName);
            normalized.putIfAbsent("summary", shortText(content, 200));
            return normalized;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {
            });
            parsed.putIfAbsent("analysisType", analysisType);
            parsed.putIfAbsent("modelName", modelName);
            return parsed;
        } catch (Exception ex) {
            return Map.of(
                "analysisType", analysisType,
                "modelName", modelName,
                "summary", shortText(content, 200),
                "content", content
            );
        }
    }
}
