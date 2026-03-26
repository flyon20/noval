package com.novelanalyzer.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class TrendResultJsonUtilsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRecoverNestedThemeJsonContainingLiteralNewlinesInsideStringValues() {
        String nestedThemeJson = """
            {
              "analysisType":"theme",
              "summary":"Urban-brain remains the clearest direction across the latest board snapshots.",
              "boardSummary":"This board keeps concentrating on urban-brain and system-flow hybrids.",
              "trendPreview":"Urban-brain continues to dominate this board.",
              "detailContent":"### Step 1
            Keyword extraction
            Urban-brain and system-flow keep appearing together.",
              "historicalWordCloud":[
                {"name":"urban-brain","value":24},
                {"name":"system-flow","value":15}
              ],
              "themeDistribution":[
                {"theme":"urban-brain","count":3,"ratio":50.0},
                {"theme":"system-flow","count":2,"ratio":33.3}
              ],
              "themeTable":[
                {
                  "theme":"urban-brain",
                  "count":3,
                  "ratio":50.0,
                  "trend":"rising"
                }
              ],
              "hotBooks":[
                {"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"Keeps leading the board"}
              ],
              "insightCards":[
                {"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"}
              ],
              "snapshotComparisons":[
                {"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","topThemeRatio":50.0,"leadBookName":"Brain City King","change":"holding"}
              ],
              "comparisonSummary":"Urban-brain has become the clearest board-level direction across the last three snapshots.",
              "historyAnalysisCount":3
            }
            """;
        Map<String, Object> degradedResult = new LinkedHashMap<>();
        degradedResult.put("analysisType", "theme");
        degradedResult.put("summary", "");
        degradedResult.put("boardSummary", "");
        degradedResult.put("trendPreview", nestedThemeJson);
        degradedResult.put("detailContent", nestedThemeJson);
        degradedResult.put("historicalWordCloud", List.of());
        degradedResult.put("themeDistribution", List.of());
        degradedResult.put("themeTable", List.of());
        degradedResult.put("hotBooks", List.of());
        degradedResult.put("insightCards", List.of());
        degradedResult.put("snapshotComparisons", List.of());
        degradedResult.put("comparisonSummary", "");
        degradedResult.put("historyAnalysisCount", 3);

        Map<String, Object> recovered = TrendResultJsonUtils.recoverThemeResultMap(objectMapper, degradedResult);

        assertEquals(
            "This board keeps concentrating on urban-brain and system-flow hybrids.",
            recovered.get("boardSummary")
        );
        assertEquals(
            "Urban-brain continues to dominate this board.",
            recovered.get("trendPreview")
        );
        assertEquals(2, ((List<?>) recovered.get("historicalWordCloud")).size());
        assertEquals(2, ((List<?>) recovered.get("themeDistribution")).size());
        assertEquals(1, ((List<?>) recovered.get("themeTable")).size());
        assertEquals(1, ((List<?>) recovered.get("hotBooks")).size());
        assertEquals(1, ((List<?>) recovered.get("insightCards")).size());
        assertEquals(1, ((List<?>) recovered.get("snapshotComparisons")).size());
    }

    @Test
    void shouldPreserveFineGrainedTrendFieldsDuringNormalization() {
        Map<String, Object> themeTableItem = new LinkedHashMap<>();
        themeTableItem.put("theme", "都市脑洞-直播算命-惩恶扬善");
        themeTableItem.put("laneLevel", "L4");
        themeTableItem.put("systemType", "因果回溯系统");
        themeTableItem.put("systemPresence", "strong-presence-task-driver");
        themeTableItem.put("systemPersona", "cold-judge");
        themeTableItem.put("interactionMode", "强存在感任务驱动型");
        themeTableItem.put("feedbackLoop", "solve-case -> unlock-causality-replay");
        themeTableItem.put("payoffMechanism", "live-case -> instant-payoff");
        themeTableItem.put("emotionAnchor", "降维打击+替天行道");
        themeTableItem.put("microInnovation", "直播连麦即审判");
        themeTableItem.put("antiRoutineDesign", "public adjudication replaces private fortune telling");
        themeTableItem.put("avoidedPoisonPoints", "avoids broad generic system-flow");
        themeTableItem.put("microTags", List.of("直播算命", "惩恶扬善", "因果回放"));
        themeTableItem.put("count", 4);
        themeTableItem.put("ratio", 44.4);
        themeTableItem.put("trend", "上升");
        Map<String, Object> representativeBook = new LinkedHashMap<>();
        representativeBook.put("theme", "都市脑洞-直播算命-惩恶扬善");
        representativeBook.put("bookName", "直播算命：水友你印堂发黑");
        representativeBook.put("author", "作者甲");
        representativeBook.put("rankNo", 1);
        representativeBook.put("systemType", "因果回溯系统");
        representativeBook.put("systemPresence", "strong-presence-task-driver");
        representativeBook.put("systemPersona", "cold-judge");
        representativeBook.put("interactionMode", "强存在感任务驱动型");
        representativeBook.put("feedbackLoop", "solve-case -> unlock-causality-replay");
        representativeBook.put("payoffMechanism", "live-case -> instant-payoff");
        representativeBook.put("emotionAnchor", "降维打击+替天行道");
        representativeBook.put("microInnovation", "直播连麦即审判");
        representativeBook.put("antiRoutineDesign", "public adjudication replaces private fortune telling");
        representativeBook.put("avoidedPoisonPoints", "avoids broad generic system-flow");
        representativeBook.put("microTags", List.of("直播算命", "惩恶扬善", "因果回放"));
        representativeBook.put("reason", "代表赛道头部");
        themeTableItem.put("representativeBooks", List.of(representativeBook));

        List<Map<String, Object>> normalizedTable = TrendResultJsonUtils.normalizeThemeTable(List.of(themeTableItem));
        Map<String, Object> normalizedThemeTableItem = normalizedTable.get(0);
        assertEquals("strong-presence-task-driver", normalizedThemeTableItem.get("systemPresence"));
        assertEquals("cold-judge", normalizedThemeTableItem.get("systemPersona"));
        assertEquals("public adjudication replaces private fortune telling", normalizedThemeTableItem.get("antiRoutineDesign"));
        assertEquals("avoids broad generic system-flow", normalizedThemeTableItem.get("avoidedPoisonPoints"));
        assertIterableEquals(
            List.of("直播算命", "惩恶扬善", "因果回放"),
            (List<?>) normalizedThemeTableItem.get("microTags")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedBook = (Map<String, Object>) ((List<?>) normalizedThemeTableItem.get("representativeBooks")).get(0);
        assertEquals("strong-presence-task-driver", normalizedBook.get("systemPresence"));
        assertEquals("live-case -> instant-payoff", normalizedBook.get("payoffMechanism"));
        assertEquals("public adjudication replaces private fortune telling", normalizedBook.get("antiRoutineDesign"));

        List<Map<String, Object>> normalizedDistribution = TrendResultJsonUtils.normalizeThemeDistribution(
            List.of(themeTableItem),
            null
        );
        Map<String, Object> normalizedDistributionItem = normalizedDistribution.get(0);
        assertEquals("strong-presence-task-driver", normalizedDistributionItem.get("systemPresence"));
        assertEquals("cold-judge", normalizedDistributionItem.get("systemPersona"));
        assertIterableEquals(
            List.of("直播算命", "惩恶扬善", "因果回放"),
            (List<?>) normalizedDistributionItem.get("microTags")
        );

        List<Map<String, Object>> normalizedHotBooks = TrendResultJsonUtils.normalizeThemeHotBooks(themeTableItem.get("representativeBooks"));
        Map<String, Object> normalizedHotBook = normalizedHotBooks.get(0);
        assertEquals("strong-presence-task-driver", normalizedHotBook.get("systemPresence"));
        assertEquals("cold-judge", normalizedHotBook.get("systemPersona"));
        assertEquals("public adjudication replaces private fortune telling", normalizedHotBook.get("antiRoutineDesign"));
    }
}
