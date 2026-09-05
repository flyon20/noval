package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.DocumentInput;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.DocumentKind;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDocumentParserTest {

    private final ProjectDocumentParser parser = new ProjectDocumentParser();

    @Test
    void classifiesRepresentativeNovelMaterialWithoutModelFallback() {
        List<ExpectedKind> samples = List.of(
            new ExpectedKind("前十章正文试写-让你做五毛特效你请诸天打工.md", DocumentKind.NOVEL_TEXT),
            new ExpectedKind("前十章正文修订版-让你做五毛特效你请诸天打工.md", DocumentKind.NOVEL_TEXT),
            new ExpectedKind("全书大纲-让你做五毛特效你请诸天打工.md", DocumentKind.OUTLINE),
            new ExpectedKind("前十章设计-让你做五毛特效你请诸天打工.md", DocumentKind.CHAPTER_OUTLINE),
            new ExpectedKind("角色设定-让你做五毛特效你请诸天打工.md", DocumentKind.CHARACTER_PROFILE),
            new ExpectedKind("金手指-万界渲染台.md", DocumentKind.WORLD_SETTING),
            new ExpectedKind("特效行业术语与工作流程-小说可用版.md", DocumentKind.REFERENCE),
            new ExpectedKind("最新榜单综合评估-2026-07-19-当前选题与备选方向.md", DocumentKind.REFERENCE)
        );

        for (ExpectedKind sample : samples) {
            ParsedDocument result = parse(sample.fileName(), "# " + sample.fileName() + "\n\n资料内容");
            assertThat(result.kind()).as(sample.fileName()).isEqualTo(sample.kind());
            assertThat(result.requiresModelFallback()).as(sample.fileName()).isFalse();
        }
    }

    @Test
    void splitsMixedComparisonDocumentIntoNovelAndReferenceSections() {
        ParsedDocument result = parse(
            "对比版-第1章-开文清晰度校准.md",
            "# 对比版第1章\n\n## 第1章 三千块\n正文内容。\n\n## 对照说明（写给作者，不进正文）\n修改说明。"
        );

        assertThat(result.kind()).isEqualTo(DocumentKind.MIXED);
        assertThat(result.requiresUserConfirmation()).isTrue();
        assertThat(result.sections()).extracting(section -> section.kind())
            .containsExactly(DocumentKind.NOVEL_TEXT, DocumentKind.REFERENCE);
    }

    @Test
    void detectsChapterHeadingsAndKeepsTheirOrder() {
        ParsedDocument result = parse(
            "整本小说.md",
            "# 作品名\n\n## 第一章 开局\n第一章正文。\n\n## 第2章 反转\n第二章正文。"
        );

        assertThat(result.kind()).isEqualTo(DocumentKind.NOVEL_TEXT);
        assertThat(result.sections()).extracting(section -> section.title())
            .containsExactly("第一章 开局", "第2章 反转");
        assertThat(result.sections()).allMatch(section -> section.kind() == DocumentKind.NOVEL_TEXT);
    }

    @Test
    void keepsMultipleLevelOneChapterHeadingsInsteadOfDroppingTheDocument() {
        ParsedDocument result = parse(
            "chapters.md",
            "# 第1章 开始\n第一章正文\n# 第2章 转折\n第二章正文"
        );

        assertThat(result.sections()).extracting(section -> section.title())
            .containsExactly("第1章 开始", "第2章 转折");
    }

    @Test
    void decodesUtf8BomAndGb18030OnTheBackend() {
        byte[] utf8 = ("\uFEFF# 全书大纲\r\n\r\n故事内容").getBytes(StandardCharsets.UTF_8);
        ParsedDocument utf8Result = parser.parse(new DocumentInput("全书大纲.md", "全书大纲.md", utf8, null));
        assertThat(utf8Result.normalizedContent()).startsWith("# 全书大纲\n");

        byte[] gb18030 = "# 角色设定\r\n\r\n主角资料".getBytes(Charset.forName("GB18030"));
        ParsedDocument gbResult = parser.parse(new DocumentInput("角色设定.txt", "角色设定.txt", gb18030, null));
        assertThat(gbResult.kind()).isEqualTo(DocumentKind.CHARACTER_PROFILE);
        assertThat(gbResult.normalizedContent()).contains("主角资料");
    }

    @Test
    void ignoresDevelopmentArtifactsAndRejectsEscapingPaths() {
        ParsedDocument ignored = parser.parse(new DocumentInput(
            "noval/node_modules/pkg/readme.md",
            "readme.md",
            "ignored".getBytes(StandardCharsets.UTF_8),
            null
        ));
        assertThat(ignored.ignored()).isTrue();
        assertThat(ignored.sections()).isEmpty();

        assertThatThrownBy(() -> parser.parse(new DocumentInput(
            "../secret.md",
            "secret.md",
            "secret".getBytes(StandardCharsets.UTF_8),
            null
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("escapes upload root");
    }

    @Test
    void declaredKindOverridesHeuristicsDeterministically() {
        DocumentInput input = new DocumentInput(
            "notes/unknown.md",
            "unknown.md",
            "# 任意标题\n\n## 第一章 看似正文\n任意内容".getBytes(StandardCharsets.UTF_8),
            DocumentKind.FORESHADOWING_NOTE
        );

        ParsedDocument first = parser.parse(input);
        ParsedDocument second = parser.parse(input);

        assertThat(first).isEqualTo(second);
        assertThat(first.kind()).isEqualTo(DocumentKind.FORESHADOWING_NOTE);
        assertThat(first.confidence()).isEqualTo(1.0d);
        assertThat(first.requiresModelFallback()).isFalse();
        assertThat(first.requiresUserConfirmation()).isFalse();
        assertThat(first.sections()).allMatch(section -> section.kind() == DocumentKind.FORESHADOWING_NOTE);
    }

    private ParsedDocument parse(String fileName, String content) {
        return parser.parse(new DocumentInput(
            fileName,
            fileName,
            content.getBytes(StandardCharsets.UTF_8),
            null
        ));
    }

    private record ExpectedKind(String fileName, DocumentKind kind) {
    }
}
