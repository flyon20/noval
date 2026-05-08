package com.novelanalyzer.modules.config;

import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.service.DefaultPromptContractCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPromptContractCatalogTest {

    private final DefaultPromptContractCatalog catalog = new DefaultPromptContractCatalog();

    @Test
    void shouldKeepSingleBookAnalysisContractsLightweight() {
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setPromptType("deconstruct");

        boolean changed = catalog.applyMissingDefaults(entity);

        assertThat(changed).isTrue();
        assertThat(entity.getInputJsonSchema()).contains("\"chapters\"");
        assertThat(entity.getInputExampleJson()).contains("\"bookName\"");
        assertThat(entity.getOutputJsonSchema()).isNull();
        assertThat(entity.getOutputExampleJson()).isNull();
        assertThat(entity.getPostProcessType()).isNull();
        assertThat(entity.getParseConfigJson()).isNull();
    }

    @Test
    void shouldKeepTrendAnalysisContractsStrictlyStructured() {
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setPromptType("theme");

        boolean changed = catalog.applyMissingDefaults(entity);

        assertThat(changed).isTrue();
        assertThat(entity.getInputJsonSchema()).contains("\"snapshots\"");
        assertThat(entity.getOutputJsonSchema())
            .contains("\"historicalWordCloud\"")
            .contains("\"systemArchetypes\"")
            .contains("\"microInnovationSignals\"")
            .contains("\"systemPresence\"")
            .contains("\"systemPersona\"")
            .contains("\"payoffMechanism\"")
            .contains("\"antiRoutineDesign\"")
            .contains("\"avoidedPoisonPoints\"")
            .contains("\"microTags\"");
        assertThat(entity.getPostProcessType()).isEqualTo("json_extract");
        assertThat(entity.getParseConfigJson()).contains("\"parser\": \"json\"");
    }

    @Test
    void shouldRefreshOutdatedTrendContractsWhenCoreThemeFieldsAreMissing() {
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setPromptType("theme");
        entity.setOutputJsonSchema("""
            {
              "type": "object",
              "properties": {
                "themeDistribution": { "type": "array" },
                "themeTable": { "type": "array" }
              }
            }
            """);
        entity.setOutputExampleJson("""
            {
              "themeDistribution": [{ "theme": "都市脑洞", "count": 3, "ratio": 50.0 }]
            }
            """);

        boolean changed = catalog.applyMissingDefaults(entity);

        assertThat(changed).isTrue();
        assertThat(entity.getOutputJsonSchema())
            .contains("\"systemPresence\"")
            .contains("\"antiRoutineDesign\"")
            .contains("\"avoidedPoisonPoints\"");
        assertThat(entity.getOutputExampleJson())
            .contains("\"systemPresence\"")
            .contains("\"antiRoutineDesign\"")
            .contains("\"microTags\"");
    }
}
