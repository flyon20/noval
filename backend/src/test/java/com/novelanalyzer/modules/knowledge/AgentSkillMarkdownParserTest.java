package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.AgentSkillMarkdownParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSkillMarkdownParserTest {

    private final AgentSkillMarkdownParser parser = new AgentSkillMarkdownParser();

    @Test
    void shouldParseStandardSkillDescriptorAndMapToolHintsToCapabilities() {
        AgentSkillMarkdownParser.ParsedSkill parsed = parser.parse("""
            ---
            name: webnovel-imitation
            description: 根据目标作品特征生成受约束的仿写方案
            license: Proprietary
            compatibility: Noval 1.x
            metadata:
              owner: editorial
              maturity: production
            allowed-tools:
              - rank.lookup
              - project.retrieve
              - rank.research_pack
            ---
            # Instructions

            先提取叙事结构，再生成不复用原句的仿写结果。
            """);

        assertThat(parsed.name()).isEqualTo("webnovel-imitation");
        assertThat(parsed.description()).isEqualTo("根据目标作品特征生成受约束的仿写方案");
        assertThat(parsed.instructions()).contains("先提取叙事结构");
        assertThat(parsed.requestedCapabilities()).containsExactly("market.read", "project.retrieve");
        assertThat(parsed.metadata())
            .containsEntry("license", "Proprietary")
            .containsEntry("compatibility", "Noval 1.x")
            .containsEntry("legacyFormat", false);
        assertThat(parsed.metadata().get("metadata")).isEqualTo(
            java.util.Map.of("owner", "editorial", "maturity", "production")
        );
    }

    @Test
    void shouldRejectInvalidNameDuplicateKeysUnknownShapesAndEmptyBody() {
        assertThatThrownBy(() -> parser.parse("""
            ---
            name: WebNovel_Imitation
            description: invalid name
            ---
            body
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");

        assertThatThrownBy(() -> parser.parse("""
            ---
            name: duplicate-name
            name: duplicate-name-2
            description: duplicate
            ---
            body
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("front matter");

        assertThatThrownBy(() -> parser.parse("""
            ---
            name: invalid-structure
            description: invalid structure
            allowed-tools:
              command: rank.lookup
            ---
            body
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowed-tools");

        assertThatThrownBy(() -> parser.parse("""
            ---
            name: empty-body
            description: no instructions
            ---

            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("instructions");
    }

    @Test
    void shouldRejectUnknownFieldsOversizedDescriptionAndUnknownToolHints() {
        assertThatThrownBy(() -> parser.parse("""
            ---
            name: unknown-field
            description: unknown field
            executable: true
            ---
            body
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("front matter");

        String oversizedDescription = "x".repeat(1001);
        assertThatThrownBy(() -> parser.parse("---\nname: oversized\ndescription: "
            + oversizedDescription + "\n---\nbody"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("description");

        assertThatThrownBy(() -> parser.parse("""
            ---
            name: unknown-tool
            description: unknown tool
            allowed-tools: [shell.exec]
            ---
            body
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowed-tools");
    }
}
