package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.service.UserConfigService;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiGatewayService {

    private static final Map<String, String> DEFAULT_PROMPT_TEMPLATES = Map.of(
        "deconstruct", "请基于以下小说正文进行拆文分析，重点输出：核心卖点、开篇钩子、人物关系、冲突设计、节奏爽点与可优化点。\n\n{{content}}",
        "structure", "请基于以下小说正文进行结构分析，重点关注开篇铺垫、冲突推进、转折设置、悬念设计与章节结构。\n\n{{content}}",
        "plot", "请基于以下小说正文进行情节分析，概括关键事件、人物动机、冲突升级与后续看点。\n\n{{content}}",
        "theme", "Please analyze the following trend data and summarize core themes, changes, and representative books.\n\n{{content}}"
    );

    private static final String THEME_STRUCTURED_GUIDANCE = """
        Trend output constraints:
        1. Never use broad labels such as 都市系统, 玄幻升级, 都市, or 系统流 as the final lane name.
        2. `themeDistribution.theme` and `themeTable.theme` must use 3 or 4 Chinese segments joined by '-' and land on a concrete lane.
        3. Every `themeDistribution` and `themeTable` row must expose laneLevel, systemType, systemPresence, systemPersona, interactionMode, feedbackLoop, payoffMechanism, emotionAnchor, antiRoutineDesign, avoidedPoisonPoints, and microTags.
        4. `systemArchetypes` must distinguish concrete system type, system presence, persona, interaction mode, feedback loop, and payoff mechanism.
        5. `microInnovationSignals` must explain the anti-cliche twist, avoided poison points, and near-term viability.
        6. `historicalWordCloud` must contain concrete board-scoped fine-grained terms, not umbrella words.
        7. Keep summary fields concise and keep table/list outputs compact.
        8. `detailContent` must be plain prose without markdown tables or code fences.
        """;

    private final TokenCountEstimator tokenCountEstimator;

    public AiGatewayService(RestTemplate aiRestTemplate,
                            AiProperties aiProperties,
                            SystemConfigService systemConfigService,
                            ConfigSecretService configSecretService,
                            UserConfigService userConfigService,
                            TokenCountEstimator tokenCountEstimator,
                            ObjectMapper objectMapper) {
        this.tokenCountEstimator = tokenCountEstimator;
    }

    public int estimatePromptTokens(PromptConfigEntity promptConfig, String text, String analysisType) {
        String renderedPrompt = renderPrompt(promptConfig == null ? null : promptConfig.getPromptContent(), text, analysisType);
        return estimateTokenCountInternal(renderedPrompt);
    }

    public String resolvePromptTemplate(PromptConfigEntity promptConfig, String analysisType) {
        return normalizePromptTemplate(promptConfig == null ? null : promptConfig.getPromptContent(), analysisType);
    }

    private String renderPrompt(String template, String text, String analysisType) {
        String safeTemplate = normalizePromptTemplate(template, analysisType);
        return PromptTemplate.from(safeTemplate)
            .apply(Map.of("content", text == null ? "" : text, "analysisType", analysisType == null ? "" : analysisType))
            .text();
    }

    private String normalizePromptTemplate(String template, String analysisType) {
        if (template != null && !template.isBlank() && template.contains("{{content}}")) {
            return template;
        }
        return DEFAULT_PROMPT_TEMPLATES.getOrDefault(analysisType, "{{content}}");
    }

    private int estimateTokenCountInternal(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, tokenCountEstimator.estimateTokenCountInText(text));
    }

    private String augmentSystemPromptWithStructuredOutput(PromptConfigEntity promptConfig, String systemPrompt) {
        if (!supportsStructuredOutput(promptConfig)) {
            return systemPrompt;
        }
        StringBuilder builder = new StringBuilder(systemPrompt);
        if (hasInputJsonContract(promptConfig)) {
            builder.append("\n\ninput schema:\n").append(promptConfig.getInputJsonSchema());
            if (promptConfig.getInputExampleJson() != null && !promptConfig.getInputExampleJson().isBlank()) {
                builder.append("\ninput example:\n").append(promptConfig.getInputExampleJson());
            }
        }
        if (hasOutputJsonContract(promptConfig)) {
            builder.append("\n\noutput schema:\n").append(promptConfig.getOutputJsonSchema());
            if (promptConfig.getOutputExampleJson() != null && !promptConfig.getOutputExampleJson().isBlank()) {
                builder.append("\noutput example:\n").append(promptConfig.getOutputExampleJson());
            }
        }
        if (usesThemeStructuredContract(promptConfig)) {
            builder.append("\n\n").append(THEME_STRUCTURED_GUIDANCE);
        }
        builder.append("\n\nPlease output valid JSON only.");
        return builder.toString();
    }

    private boolean requiresJsonResponse(PromptConfigEntity promptConfig) {
        if (promptConfig == null) {
            return false;
        }
        if ("json_extract".equalsIgnoreCase(promptConfig.getPostProcessType())) {
            return true;
        }
        if (promptConfig.getParseConfigJson() != null
            && promptConfig.getParseConfigJson().toLowerCase(java.util.Locale.ROOT).contains("\"parser\":\"json\"")) {
            return true;
        }
        return hasInputJsonContract(promptConfig) || hasOutputJsonContract(promptConfig);
    }

    private boolean hasInputJsonContract(PromptConfigEntity promptConfig) {
        return promptConfig != null && promptConfig.getInputJsonSchema() != null && !promptConfig.getInputJsonSchema().isBlank();
    }

    private boolean hasOutputJsonContract(PromptConfigEntity promptConfig) {
        return promptConfig != null && promptConfig.getOutputJsonSchema() != null && !promptConfig.getOutputJsonSchema().isBlank();
    }

    private boolean supportsStructuredOutput(PromptConfigEntity promptConfig) {
        return promptConfig != null && (
            hasInputJsonContract(promptConfig)
                || hasOutputJsonContract(promptConfig)
                || requiresJsonResponse(promptConfig)
        );
    }

    private boolean usesThemeStructuredContract(PromptConfigEntity promptConfig) {
        return promptConfig != null
            && promptConfig.getPromptType() != null
            && "theme".equalsIgnoreCase(promptConfig.getPromptType().trim());
    }

    public static final class InvocationHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();

        public void attachStreamingHandle(StreamingHandle handle) {
            if (handle == null) {
                return;
            }
            streamingHandle.set(handle);
            if (isCancelled()) {
                handle.cancel();
            }
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            StreamingHandle handle = streamingHandle.get();
            if (handle != null) {
                handle.cancel();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
