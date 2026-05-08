package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultPromptContractCatalog {

    private static final String GENERIC_ANALYSIS_INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "platform": { "type": "string" },
            "bookId": { "type": "integer" },
            "bookName": { "type": "string" },
            "author": { "type": "string" },
            "intro": { "type": "string" },
            "chapterCount": { "type": "integer" },
            "chapters": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "chapterNo": { "type": "integer" },
                  "chapterTitle": { "type": "string" },
                  "content": { "type": "string" }
                },
                "required": ["chapterNo", "chapterTitle", "content"]
              }
            }
          },
          "required": ["platform", "bookId", "bookName", "chapterCount", "chapters"]
        }
        """;

    private static final String GENERIC_ANALYSIS_INPUT_EXAMPLE = """
        {
          "platform": "fanqie",
          "bookId": 1001,
          "bookName": "测试书籍",
          "author": "测试作者",
          "intro": "一本都市脑洞升级流小说",
          "chapterCount": 3,
          "chapters": [
            {
              "chapterNo": 1,
              "chapterTitle": "第一章 开篇钩子",
              "content": "主角开局绑定系统，立刻卷入城市异能冲突。"
            }
          ]
        }
        """;

    private static final String DECONSTRUCT_OUTPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "analysisType": { "type": "string" },
            "summary": { "type": "string" },
            "sellingPoints": {
              "type": "array",
              "items": { "type": "string" }
            }
          },
          "required": ["analysisType", "summary", "sellingPoints"]
        }
        """;

    private static final String DECONSTRUCT_OUTPUT_EXAMPLE = """
        {
          "analysisType": "deconstruct",
          "summary": "summary",
          "sellingPoints": ["hook"]
        }
        """;

    private static final String STRUCTURE_OUTPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "analysisType": { "type": "string" },
            "summary": { "type": "string" },
            "structureStages": {
              "type": "array",
              "items": { "type": "string" }
            }
          },
          "required": ["analysisType", "summary", "structureStages"]
        }
        """;

    private static final String STRUCTURE_OUTPUT_EXAMPLE = """
        {
          "analysisType": "structure",
          "summary": "summary",
          "structureStages": ["stage"]
        }
        """;

    private static final String PLOT_OUTPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "analysisType": { "type": "string" },
            "summary": { "type": "string" },
            "plotBeats": {
              "type": "array",
              "items": { "type": "string" }
            }
          },
          "required": ["analysisType", "summary", "plotBeats"]
        }
        """;

    private static final String PLOT_OUTPUT_EXAMPLE = """
        {
          "analysisType": "plot",
          "summary": "summary",
          "plotBeats": ["beat"]
        }
        """;

    private static final Map<String, PromptContractDefaults> DEFAULTS_BY_TYPE = buildDefaults();

    public Optional<PromptContractDefaults> findByType(String promptType) {
        if (promptType == null || promptType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DEFAULTS_BY_TYPE.get(promptType.trim().toLowerCase()));
    }

    public boolean applyMissingDefaults(PromptConfigEntity entity) {
        if (entity == null) {
            return false;
        }
        PromptContractDefaults defaults = DEFAULTS_BY_TYPE.get(entity.getPromptType());
        if (defaults == null) {
            return false;
        }

        boolean changed = false;
        changed |= fillIfBlank(entity::getInputJsonSchema, entity::setInputJsonSchema, defaults.inputJsonSchema());
        changed |= fillIfBlank(entity::getInputExampleJson, entity::setInputExampleJson, defaults.inputExampleJson());
        changed |= fillIfBlank(entity::getOutputJsonSchema, entity::setOutputJsonSchema, defaults.outputJsonSchema());
        changed |= fillIfBlank(entity::getOutputExampleJson, entity::setOutputExampleJson, defaults.outputExampleJson());
        changed |= fillIfBlank(entity::getPostProcessType, entity::setPostProcessType, defaults.postProcessType());
        changed |= fillIfBlank(entity::getParseConfigJson, entity::setParseConfigJson, defaults.parseConfigJson());
        changed |= upgradeOutdatedThemeContract(entity, defaults);
        return changed;
    }

    private boolean fillIfBlank(ValueReader reader, ValueWriter writer, String defaultValue) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return false;
        }
        String currentValue = reader.read();
        if (currentValue != null && !currentValue.isBlank()) {
            return false;
        }
        writer.write(defaultValue);
        return true;
    }

    private boolean upgradeOutdatedThemeContract(PromptConfigEntity entity, PromptContractDefaults defaults) {
        if (entity == null || defaults == null) {
            return false;
        }
        if (!"theme".equalsIgnoreCase(entity.getPromptType())) {
            return false;
        }
        if (!isOutdatedThemeContract(entity)) {
            return false;
        }

        boolean changed = false;
        changed |= replaceIfDifferent(entity::getOutputJsonSchema, entity::setOutputJsonSchema, defaults.outputJsonSchema());
        changed |= replaceIfDifferent(entity::getOutputExampleJson, entity::setOutputExampleJson, defaults.outputExampleJson());
        changed |= replaceIfDifferent(entity::getPostProcessType, entity::setPostProcessType, defaults.postProcessType());
        changed |= replaceIfDifferent(entity::getParseConfigJson, entity::setParseConfigJson, defaults.parseConfigJson());
        return changed;
    }

    private boolean replaceIfDifferent(ValueReader reader, ValueWriter writer, String expectedValue) {
        if (expectedValue == null || expectedValue.isBlank()) {
            return false;
        }
        String currentValue = reader.read();
        if (expectedValue.equals(currentValue)) {
            return false;
        }
        writer.write(expectedValue);
        return true;
    }

    private boolean isOutdatedThemeContract(PromptConfigEntity entity) {
        return missesAnyMarker(
            entity.getOutputJsonSchema(),
            "\"systemPresence\"",
            "\"antiRoutineDesign\"",
            "\"avoidedPoisonPoints\"",
            "\"microTags\""
        ) || missesAnyMarker(
            entity.getOutputExampleJson(),
            "\"systemPresence\"",
            "\"antiRoutineDesign\"",
            "\"microTags\""
        );
    }

    private boolean missesAnyMarker(String rawValue, String... markers) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && !rawValue.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, PromptContractDefaults> buildDefaults() {
        Map<String, PromptContractDefaults> defaults = new LinkedHashMap<>();
        defaults.put("deconstruct", new PromptContractDefaults(
            normalizeJson(GENERIC_ANALYSIS_INPUT_SCHEMA),
            normalizeJson(GENERIC_ANALYSIS_INPUT_EXAMPLE),
            null,
            null,
            null,
            null
        ));
        defaults.put("structure", new PromptContractDefaults(
            normalizeJson(GENERIC_ANALYSIS_INPUT_SCHEMA),
            normalizeJson(GENERIC_ANALYSIS_INPUT_EXAMPLE),
            null,
            null,
            null,
            null
        ));
        defaults.put("plot", new PromptContractDefaults(
            normalizeJson(GENERIC_ANALYSIS_INPUT_SCHEMA),
            normalizeJson(GENERIC_ANALYSIS_INPUT_EXAMPLE),
            null,
            null,
            null,
            null
        ));
        defaults.put("theme", new PromptContractDefaults(
            normalizeJson("""
                {
                  "type": "object",
                  "properties": {
                    "platform": { "type": "string" },
                    "channelCode": { "type": "string" },
                    "boardCode": { "type": "string" },
                    "boardName": { "type": "string" },
                    "snapshotCount": { "type": "integer" },
                    "snapshots": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "snapshotTime": { "type": "string" },
                          "recordCount": { "type": "integer" },
                          "ranks": {
                            "type": "array",
                            "items": {
                              "type": "object",
                              "properties": {
                                "rankNo": { "type": "integer" },
                                "bookId": { "type": "integer" },
                                "bookName": { "type": "string" },
                                "author": { "type": "string" },
                                "intro": { "type": "string" }
                              },
                              "required": ["rankNo", "bookId", "bookName"]
                            }
                          }
                        },
                        "required": ["snapshotTime", "recordCount", "ranks"]
                      }
                    }
                  },
                  "required": ["platform", "channelCode", "boardCode", "boardName", "snapshotCount", "snapshots"]
                }
                """),
            normalizeJson("""
                {
                  "platform": "fanqie",
                  "channelCode": "male-new",
                  "boardCode": "urban-brain",
                  "boardName": "都市脑洞",
                  "snapshotCount": 3,
                  "snapshots": [
                    {
                      "snapshotTime": "2026-03-20 11:30:00",
                      "recordCount": 30,
                      "ranks": [
                        {
                          "rankNo": 1,
                          "bookId": 1001,
                          "bookName": "直播算命：水友你印堂发黑",
                          "author": "作者甲",
                          "intro": "都市脑洞+直播算命+惩恶扬善，因果回溯系统帮助主角锁定恶人。"
                        }
                      ]
                    }
                  ]
                }
                """),
            normalizeJson("""
                {
                  "type": "object",
                  "properties": {
                    "analysisType": { "type": "string" },
                    "platform": { "type": "string" },
                    "channelCode": { "type": "string" },
                    "boardCode": { "type": "string" },
                    "boardName": { "type": "string" },
                    "summary": { "type": "string" },
                    "boardSummary": { "type": "string" },
                    "trendPreview": { "type": "string" },
                    "detailContent": { "type": "string" },
                    "historicalWordCloud": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "name": { "type": "string" },
                          "value": { "type": "number" }
                        },
                        "required": ["name", "value"]
                      }
                    },
                    "themeDistribution": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "theme": { "type": "string" },
                          "laneLevel": { "type": "string" },
                          "systemType": { "type": "string" },
                          "systemPresence": { "type": "string" },
                          "systemPersona": { "type": "string" },
                          "interactionMode": { "type": "string" },
                          "payoffMechanism": { "type": "string" },
                          "microTags": {
                            "type": "array",
                            "items": { "type": "string" }
                          },
                          "count": { "type": "number" },
                          "ratio": { "type": "number" }
                        },
                        "required": ["theme", "count", "ratio"]
                      }
                    },
                    "themeTable": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "theme": { "type": "string" },
                          "laneLevel": { "type": "string" },
                          "systemType": { "type": "string" },
                          "systemPresence": { "type": "string" },
                          "systemPersona": { "type": "string" },
                          "interactionMode": { "type": "string" },
                          "feedbackLoop": { "type": "string" },
                          "payoffMechanism": { "type": "string" },
                          "emotionAnchor": { "type": "string" },
                          "microInnovation": { "type": "string" },
                          "antiRoutineDesign": { "type": "string" },
                          "avoidedPoisonPoints": { "type": "string" },
                          "microTags": {
                            "type": "array",
                            "items": { "type": "string" }
                          },
                          "count": { "type": "number" },
                          "ratio": { "type": "number" },
                          "trend": { "type": "string" },
                          "representativeBooks": {
                            "type": "array",
                            "items": {
                              "type": "object",
                              "properties": {
                                "theme": { "type": "string" },
                                "bookName": { "type": "string" },
                                "author": { "type": "string" },
                                "rankNo": { "type": "integer" },
                                "systemType": { "type": "string" },
                                "systemPresence": { "type": "string" },
                                "systemPersona": { "type": "string" },
                                "payoffMechanism": { "type": "string" },
                                "microInnovation": { "type": "string" },
                                "antiRoutineDesign": { "type": "string" },
                                "avoidedPoisonPoints": { "type": "string" },
                                "microTags": {
                                  "type": "array",
                                  "items": { "type": "string" }
                                },
                                "reason": { "type": "string" }
                              },
                              "required": ["bookName", "rankNo", "reason"]
                            }
                          }
                        },
                        "required": [
                          "theme",
                          "laneLevel",
                          "systemType",
                          "systemPresence",
                          "interactionMode",
                          "feedbackLoop",
                          "payoffMechanism",
                          "count",
                          "ratio",
                          "trend",
                          "representativeBooks"
                        ]
                      }
                    },
                    "hotBooks": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "theme": { "type": "string" },
                          "bookName": { "type": "string" },
                          "author": { "type": "string" },
                          "rankNo": { "type": "integer" },
                          "systemType": { "type": "string" },
                          "systemPresence": { "type": "string" },
                          "systemPersona": { "type": "string" },
                          "payoffMechanism": { "type": "string" },
                          "microInnovation": { "type": "string" },
                          "antiRoutineDesign": { "type": "string" },
                          "avoidedPoisonPoints": { "type": "string" },
                          "microTags": {
                            "type": "array",
                            "items": { "type": "string" }
                          },
                          "reason": { "type": "string" }
                        },
                        "required": ["theme", "bookName", "rankNo", "reason"]
                      }
                    },
                    "systemArchetypes": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "systemType": { "type": "string" },
                          "systemPresence": { "type": "string" },
                          "systemPersona": { "type": "string" },
                          "interactionMode": { "type": "string" },
                          "feedbackLoop": { "type": "string" },
                          "payoffMechanism": { "type": "string" },
                          "count": { "type": "number" },
                          "ratio": { "type": "number" },
                          "representativeBook": { "type": "string" }
                        },
                        "required": [
                          "systemType",
                          "systemPresence",
                          "interactionMode",
                          "feedbackLoop",
                          "payoffMechanism",
                          "count",
                          "ratio"
                        ]
                      }
                    },
                    "microInnovationSignals": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "theme": { "type": "string" },
                          "innovation": { "type": "string" },
                          "whyNow": { "type": "string" },
                          "antiRoutineDesign": { "type": "string" },
                          "avoidedPoisonPoints": { "type": "string" },
                          "systemPresence": { "type": "string" },
                          "microTags": {
                            "type": "array",
                            "items": { "type": "string" }
                          },
                          "avoidedPitfall": { "type": "string" },
                          "representativeBook": { "type": "string" }
                        },
                        "required": [
                          "theme",
                          "innovation",
                          "whyNow",
                          "antiRoutineDesign",
                          "avoidedPoisonPoints"
                        ]
                      }
                    },
                    "insightCards": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "label": { "type": "string" },
                          "value": { "type": "string" },
                          "note": { "type": "string" }
                        },
                        "required": ["label", "value"]
                      }
                    },
                    "snapshotComparisons": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "snapshotTime": { "type": "string" },
                          "topTheme": { "type": "string" },
                          "topThemeRatio": { "type": "number" },
                          "leadBookName": { "type": "string" },
                          "change": { "type": "string" }
                        },
                        "required": ["snapshotTime", "topTheme", "topThemeRatio", "leadBookName", "change"]
                      }
                    },
                    "comparisonSummary": { "type": "string" },
                    "historyAnalysisCount": { "type": "integer" }
                  },
                  "required": [
                    "analysisType",
                    "summary",
                    "boardSummary",
                    "trendPreview",
                    "detailContent",
                    "historicalWordCloud",
                    "themeDistribution",
                    "themeTable",
                    "hotBooks",
                    "systemArchetypes",
                    "microInnovationSignals",
                    "insightCards",
                    "snapshotComparisons",
                    "comparisonSummary",
                    "historyAnalysisCount"
                  ]
                }
                """),
            normalizeJson("""
                {
                  "analysisType": "theme",
                  "platform": "fanqie",
                  "channelCode": "male-new",
                  "boardCode": "urban-brain",
                  "boardName": "都市脑洞",
                  "summary": "近三次榜单样本显示，热钱继续往高辨识度细赛道集中，主轴是都市脑洞-直播算命-惩恶扬善与娱乐明星-老六系统-全网黑粉。",
                  "boardSummary": "主赛道是都市脑洞-直播算命-惩恶扬善，代表热书是《直播算命：水友你印堂发黑》，因为它在该赛道内当前排名最高且题材标签最完整。",
                  "trendPreview": "泛系统流继续退潮，带强场景、强身份、强反馈机制的三级四级赛道正在抬头。",
                  "detailContent": "完整趋势分析正文。",
                  "historicalWordCloud": [
                    { "name": "都市脑洞-直播算命", "value": 22 },
                    { "name": "惩恶扬善", "value": 18 },
                    { "name": "老六系统", "value": 15 },
                    { "name": "情绪值收集", "value": 13 },
                    { "name": "卡系统BUG", "value": 11 }
                  ],
                  "themeDistribution": [
                    {
                      "theme": "都市脑洞-直播算命-惩恶扬善",
                      "laneLevel": "L4",
                      "systemType": "因果回溯系统",
                      "systemPresence": "strong-presence-task-driver",
                      "systemPersona": "cold-judge",
                      "interactionMode": "强存在感任务驱动型",
                      "payoffMechanism": "solve-case -> unlock-causality-replay",
                      "microTags": ["直播算命", "惩恶扬善", "因果回放"],
                      "count": 4,
                      "ratio": 44.4
                    },
                    {
                      "theme": "娱乐明星-老六系统-全网黑粉",
                      "laneLevel": "L4",
                      "systemType": "情绪值收集流",
                      "systemPresence": "strong-presence-banters",
                      "systemPersona": "old-six",
                      "interactionMode": "拟人化吐槽型",
                      "payoffMechanism": "collect-hate-value -> unlock-counterattack-rewards",
                      "microTags": ["娱乐圈", "老六系统", "黑粉反杀"],
                      "count": 2,
                      "ratio": 22.2
                    }
                  ],
                  "themeTable": [
                    {
                      "theme": "都市脑洞-直播算命-惩恶扬善",
                      "laneLevel": "L4",
                      "systemType": "因果回溯系统",
                      "systemPresence": "strong-presence-task-driver",
                      "systemPersona": "cold-judge",
                      "interactionMode": "强存在感任务驱动型",
                      "feedbackLoop": "破案越准，回溯权限越高",
                      "payoffMechanism": "solve-case -> unlock-causality-replay",
                      "emotionAnchor": "降维打击+替天行道",
                      "microInnovation": "把传统算命流改成直播连麦审判场景，实时兑现情绪价值",
                      "antiRoutineDesign": "fortune-telling becomes public live adjudication instead of private consulting",
                      "avoidedPoisonPoints": "avoids generic mystic bluff and weak generic system-flow repetition",
                      "microTags": ["直播算命", "惩恶扬善", "因果回放"],
                      "count": 4,
                      "ratio": 44.4,
                      "trend": "上升",
                      "representativeBooks": [
                        {
                          "theme": "都市脑洞-直播算命-惩恶扬善",
                          "bookName": "直播算命：水友你印堂发黑",
                          "author": "作者甲",
                          "rankNo": 1,
                          "systemType": "因果回溯系统",
                          "systemPresence": "strong-presence-task-driver",
                          "systemPersona": "cold-judge",
                          "payoffMechanism": "solve-case -> unlock-causality-replay",
                          "microInnovation": "直播算命+因果回放",
                          "antiRoutineDesign": "public adjudication scene replaces classic one-on-one fortune telling",
                          "avoidedPoisonPoints": "avoids empty mysticism and keeps payoff visible on-screen",
                          "microTags": ["直播算命", "惩恶扬善", "因果回放"],
                          "reason": "当前该细赛道排名最高，系统反馈链路清晰，读者爽点兑现速度快。"
                        }
                      ]
                    }
                  ],
                  "hotBooks": [
                    {
                      "theme": "都市脑洞-直播算命-惩恶扬善",
                      "bookName": "直播算命：水友你印堂发黑",
                      "author": "作者甲",
                      "rankNo": 1,
                      "systemType": "因果回溯系统",
                      "systemPresence": "strong-presence-task-driver",
                      "systemPersona": "cold-judge",
                      "payoffMechanism": "solve-case -> unlock-causality-replay",
                      "microInnovation": "直播连麦即审判",
                      "antiRoutineDesign": "turns fortune telling into a public punishment scene",
                      "avoidedPoisonPoints": "avoids broad generic system-flow and weak payoff delay",
                      "microTags": ["直播算命", "惩恶扬善", "因果回放"],
                      "reason": "它是主赛道内当前排名最高的代表热书，题材标签、系统机制、情绪锚点三者统一。"
                    }
                  ],
                  "systemArchetypes": [
                    {
                      "systemType": "因果回溯系统",
                      "systemPresence": "strong-presence-task-driver",
                      "systemPersona": "cold-judge",
                      "interactionMode": "强存在感任务驱动型",
                      "feedbackLoop": "完成惩恶任务后解锁更高权限",
                      "payoffMechanism": "complete-punishment-task -> unlock-higher-causality-authority",
                      "count": 3,
                      "ratio": 33.3,
                      "representativeBook": "直播算命：水友你印堂发黑"
                    }
                  ],
                  "microInnovationSignals": [
                    {
                      "theme": "都市脑洞-直播算命-惩恶扬善",
                      "innovation": "把算命从单次咨询改成直播连麦审判，形成高频围观场景。",
                      "whyNow": "短视频语境下，公开处刑和即时反转更容易形成追读与讨论。",
                      "antiRoutineDesign": "fortune-telling is bound to a public adjudication workflow instead of a generic oracle scene",
                      "avoidedPoisonPoints": "avoids empty mysticism and keeps each payoff visible and board-friendly",
                      "systemPresence": "strong-presence-task-driver",
                      "microTags": ["直播算命", "惩恶扬善", "因果回放"],
                      "avoidedPitfall": "避开纯装神弄鬼，转成因果回放+实锤惩恶。",
                      "representativeBook": "直播算命：水友你印堂发黑"
                    }
                  ],
                  "insightCards": [
                    {
                      "label": "主赛道",
                      "value": "都市脑洞-直播算命-惩恶扬善",
                      "note": "按近三次样本占比最高得出"
                    },
                    {
                      "label": "代表热书",
                      "value": "直播算命：水友你印堂发黑",
                      "note": "主赛道内当前排名最高"
                    }
                  ],
                  "snapshotComparisons": [
                    {
                      "snapshotTime": "2026-03-20 11:30:00",
                      "topTheme": "都市脑洞-直播算命-惩恶扬善",
                      "topThemeRatio": 44.4,
                      "leadBookName": "直播算命：水友你印堂发黑",
                      "change": "持续抬升"
                    }
                  ],
                  "comparisonSummary": "近三次样本里，宽泛系统流被持续细分，真正拿到流量的是强场景+强反馈+强情绪公约数的四级赛道组合。",
                  "historyAnalysisCount": 3
                }
                """),
            "json_extract",
            normalizeJson("""
                {
                  "parser": "json",
                  "trimMarkdownFence": true
                }
                """)
        ));
        return Map.copyOf(defaults);
    }

    private static String normalizeJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        return rawJson.trim().replace("\r\n", "\n");
    }

    public record PromptContractDefaults(
        String inputJsonSchema,
        String inputExampleJson,
        String outputJsonSchema,
        String outputExampleJson,
        String postProcessType,
        String parseConfigJson
    ) {
    }

    @FunctionalInterface
    private interface ValueReader {
        String read();
    }

    @FunctionalInterface
    private interface ValueWriter {
        void write(String value);
    }
}
