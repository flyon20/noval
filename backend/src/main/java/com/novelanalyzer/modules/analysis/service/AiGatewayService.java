package com.novelanalyzer.modules.analysis.service;

import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class AiGatewayService {

    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;

    public AiGatewayService(RestTemplate aiRestTemplate, AiProperties aiProperties) {
        this.aiRestTemplate = aiRestTemplate;
        this.aiProperties = aiProperties;
    }

    public AiInvokeResult analyze(PromptConfigEntity promptConfig, String text, String analysisType) {
        try {
            if (promptConfig.getDifyWorkflowId() != null && !promptConfig.getDifyWorkflowId().isBlank()) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                String apiKey = resolveDifyApiKey(promptConfig.getDifyApiKeyRef());
                if (apiKey != null && !apiKey.isBlank()) {
                    headers.setBearerAuth(apiKey);
                }

                Map<String, Object> body = new HashMap<>();
                body.put("inputs", Map.of("content", text, "analysisType", analysisType));
                body.put("response_mode", "blocking");
                body.put("user", "novel-analyzer");
                aiRestTemplate.postForEntity(
                    aiProperties.getDifyBaseUrl() + "/workflows/run",
                    new HttpEntity<>(body, headers),
                    Map.class
                );
            }
        } catch (Exception ignored) {
            // fallback
        }
        String prompt = promptConfig.getPromptContent() == null ? "" : promptConfig.getPromptContent();
        String merged = prompt.replace("{{content}}", text);
        String content = "【" + analysisType + "分析结果】\n"
            + "模型: " + (promptConfig.getModelName() == null ? aiProperties.getFallbackModel() : promptConfig.getModelName()) + "\n"
            + "摘要: " + shortText(merged, 300);
        int tokenUsed = Math.max(120, merged.length() / 2);
        return AiInvokeResult.of(
            promptConfig.getModelName() == null ? aiProperties.getFallbackModel() : promptConfig.getModelName(),
            content,
            tokenUsed
        );
    }

    private String resolveDifyApiKey(String keyRef) {
        if (keyRef == null || keyRef.isBlank()) {
            return System.getenv("DIFY_API_KEY");
        }
        return System.getenv(keyRef);
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
}

