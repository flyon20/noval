package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProjectDocumentParser {

    private static final double DIRECT_CLASSIFICATION_THRESHOLD = 0.85d;
    private static final double MODEL_FALLBACK_THRESHOLD = 0.60d;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^[ \\t]{0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
        "(?i)^(?:正文\\s*)?(?:(?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*卷)\\s*)?"
            + "(?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*(?:章|节|回|幕)|"
            + "(?:chapter|chap|ch)[ ._\\-]*[0-9０-９]+)(?:[ \\t:：.、—-].*)?$"
    );
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
        ".git", ".svn", ".hg", "node_modules", "target", "dist", "build", "out",
        ".idea", ".vscode", "__pycache__", ".pytest_cache", ".cache", "coverage"
    );
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
        ".class", ".jar", ".exe", ".dll", ".so", ".dylib", ".pyc", ".map"
    );

    public ParsedDocument parse(DocumentInput input) {
        if (input == null || input.content() == null || input.content().length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project document content is required");
        }
        String relativePath = normalizeRelativePath(input.relativePath(), input.originalName());
        if (shouldIgnore(relativePath)) {
            return new ParsedDocument(
                relativePath,
                input.originalName(),
                "",
                DocumentKind.REFERENCE,
                1.0d,
                List.of("ignored_path"),
                List.of(),
                false,
                false,
                true
            );
        }
        String content = decode(input.content());
        if (content.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project document content is empty");
        }
        Classification documentClassification = classifyDocument(
            relativePath,
            content,
            input.declaredKind()
        );
        boolean declaredKindLocked = input.declaredKind() != null && input.declaredKind() != DocumentKind.AUTO;
        List<DocumentSection> sections = parseSections(
            content,
            documentClassification.kind(),
            declaredKindLocked
        );
        boolean mixedSections = sections.stream().map(DocumentSection::kind).distinct().count() > 1;
        DocumentKind finalKind = mixedSections ? DocumentKind.MIXED : documentClassification.kind();
        double confidence = mixedSections
            ? Math.min(documentClassification.confidence(), 0.90d)
            : documentClassification.confidence();
        List<String> reasons = new ArrayList<>(documentClassification.reasons());
        if (mixedSections) {
            reasons.add("mixed_section_kinds");
        }
        boolean requiresModelFallback = input.declaredKind() == null
            && confidence < DIRECT_CLASSIFICATION_THRESHOLD
            && confidence >= MODEL_FALLBACK_THRESHOLD;
        boolean requiresUserConfirmation = input.declaredKind() == null
            && (confidence < MODEL_FALLBACK_THRESHOLD || finalKind == DocumentKind.MIXED);
        return new ParsedDocument(
            relativePath,
            input.originalName(),
            content,
            finalKind,
            confidence,
            List.copyOf(reasons),
            sections,
            requiresModelFallback,
            requiresUserConfirmation,
            false
        );
    }

    public boolean shouldIgnore(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String segment : normalized.split("/")) {
            if (IGNORED_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return IGNORED_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }

    public String normalizeRelativePath(String relativePath, String originalName) {
        String candidate = relativePath == null || relativePath.isBlank() ? originalName : relativePath;
        if (candidate == null || candidate.isBlank() || candidate.indexOf('\0') >= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project document relative path is required");
        }
        String normalizedSeparators = candidate.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.matches("^[A-Za-z]:.*")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project document path must be relative");
        }
        Path normalized = Path.of(normalizedSeparators).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project document path escapes upload root");
        }
        String value = normalized.toString().replace('\\', '/');
        if (value.isBlank() || value.length() > 512) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project document path is invalid");
        }
        return value;
    }

    private String decode(byte[] bytes) {
        try {
            return normalizeText(decode(bytes, StandardCharsets.UTF_8));
        } catch (CharacterCodingException ex) {
            // Fall through to the supported legacy Chinese encoding.
        }
        try {
            return normalizeText(decode(bytes, Charset.forName("GB18030")));
        } catch (CharacterCodingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "project document encoding must be UTF-8 or GB18030");
        }
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private String normalizeText(String value) {
        String normalized = value.startsWith("\uFEFF") ? value.substring(1) : value;
        return normalized.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private Classification classifyDocument(String relativePath, String content, DocumentKind declaredKind) {
        if (declaredKind != null && declaredKind != DocumentKind.AUTO) {
            return new Classification(declaredKind, 1.0d, List.of("declared_kind"));
        }
        String searchable = (relativePath + "\n" + firstHeading(content)).toLowerCase(Locale.ROOT);
        if (containsAny(searchable, "对比版", "对照说明")) {
            return new Classification(DocumentKind.MIXED, 0.90d, List.of("mixed_comparison_filename"));
        }
        if (containsAny(searchable, "角色设定", "角色卡", "人物设定", "人物卡", "character profile")) {
            return new Classification(DocumentKind.CHARACTER_PROFILE, 0.98d, List.of("character_filename"));
        }
        if (containsAny(searchable, "全书大纲", "总纲", "故事大纲", "macro outline")) {
            return new Classification(DocumentKind.OUTLINE, 0.98d, List.of("outline_filename"));
        }
        if (containsAny(searchable, "前十章设计", "章纲", "章节设计", "开文执行版", "chapter outline")) {
            return new Classification(DocumentKind.CHAPTER_OUTLINE, 0.96d, List.of("chapter_outline_filename"));
        }
        if (containsAny(searchable, "金手指", "世界观", "世界设定", "力量体系", "核心规则")) {
            return new Classification(DocumentKind.WORLD_SETTING, 0.96d, List.of("world_setting_filename"));
        }
        if (containsAny(searchable, "时间线", "年表", "timeline")) {
            return new Classification(DocumentKind.TIMELINE, 0.96d, List.of("timeline_filename"));
        }
        if (containsAny(searchable, "伏笔", "回收计划", "foreshadow")) {
            return new Classification(DocumentKind.FORESHADOWING_NOTE, 0.96d, List.of("foreshadowing_filename"));
        }
        if (containsAny(searchable, "读者反馈", "读者评论", "reader feedback")) {
            return new Classification(DocumentKind.READER_FEEDBACK, 0.96d, List.of("reader_feedback_filename"));
        }
        if (containsAny(searchable, "正文试写", "正文修订", "正文", "小说正文")) {
            return new Classification(DocumentKind.NOVEL_TEXT, 0.98d, List.of("novel_text_filename"));
        }
        if (containsAny(searchable, "榜单", "校准", "评估", "复核", "行业术语", "工作流程", "参考资料", "研究笔记")) {
            return new Classification(DocumentKind.REFERENCE, 0.95d, List.of("reference_filename"));
        }
        long chapterHeadingCount = markdownHeadings(content).stream()
            .filter(heading -> isChapterHeading(heading.title()))
            .count();
        if (chapterHeadingCount >= 2) {
            return new Classification(DocumentKind.NOVEL_TEXT, 0.90d, List.of("chapter_heading_density"));
        }
        if (content.length() >= 8_000) {
            return new Classification(DocumentKind.REFERENCE, 0.68d, List.of("unclassified_long_document"));
        }
        return new Classification(DocumentKind.REFERENCE, 0.55d, List.of("unclassified_document"));
    }

    private List<DocumentSection> parseSections(String content,
                                                DocumentKind documentKind,
                                                boolean declaredKindLocked) {
        List<Heading> headings = markdownHeadings(content);
        if (headings.isEmpty()) {
            return List.of(new DocumentSection(1, null, 0, content.length(), documentKind, content));
        }
        List<DocumentSection> sections = new ArrayList<>();
        int ordinal = 1;
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            if (heading.level() == 1 && headings.size() > 1 && index == 0
                && !isChapterHeading(heading.title())) {
                continue;
            }
            int end = index + 1 < headings.size() ? headings.get(index + 1).start() : content.length();
            String sectionContent = content.substring(heading.end(), end).trim();
            if (sectionContent.isBlank() && !isChapterHeading(heading.title())) {
                continue;
            }
            DocumentKind sectionKind = declaredKindLocked
                ? documentKind
                : classifySection(heading.title(), documentKind);
            sections.add(new DocumentSection(
                ordinal++,
                heading.title(),
                heading.start(),
                end,
                sectionKind,
                sectionContent
            ));
        }
        if (sections.isEmpty()) {
            return List.of(new DocumentSection(1, firstHeading(content), 0, content.length(), documentKind, content));
        }
        return List.copyOf(sections);
    }

    private DocumentKind classifySection(String heading, DocumentKind documentKind) {
        String value = heading == null ? "" : heading.toLowerCase(Locale.ROOT);
        if (isChapterHeading(value)) {
            return DocumentKind.NOVEL_TEXT;
        }
        if (containsAny(value, "对照说明", "写给作者", "数据依据", "榜单", "校准", "评估", "复核")) {
            return DocumentKind.REFERENCE;
        }
        if (containsAny(value, "角色", "人物卡")) {
            return DocumentKind.CHARACTER_PROFILE;
        }
        if (containsAny(value, "章纲", "章节设计", "逐章执行")) {
            return DocumentKind.CHAPTER_OUTLINE;
        }
        if (containsAny(value, "伏笔", "回收")) {
            return DocumentKind.FORESHADOWING_NOTE;
        }
        return documentKind == DocumentKind.MIXED ? DocumentKind.REFERENCE : documentKind;
    }

    private List<Heading> markdownHeadings(String content) {
        List<Heading> headings = new ArrayList<>();
        Matcher matcher = MARKDOWN_HEADING.matcher(content);
        while (matcher.find()) {
            headings.add(new Heading(
                matcher.group(1).length(),
                matcher.group(2).trim(),
                matcher.start(),
                matcher.end()
            ));
        }
        return headings;
    }

    private String firstHeading(String content) {
        Matcher matcher = MARKDOWN_HEADING.matcher(content);
        return matcher.find() ? matcher.group(2).trim() : "";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isChapterHeading(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return CHAPTER_HEADING.matcher(title).matches()
            || title.matches("(?i)^(?:chapter|chap|ch)[ ._-]*\\d+.*$")
            || title.matches("^第\\s*[0-9零〇一二三四五六七八九十百千万两]+\\s*[章节回部篇].*$");
    }

    public enum DocumentKind {
        AUTO,
        NOVEL_TEXT,
        OUTLINE,
        CHAPTER_OUTLINE,
        CHARACTER_PROFILE,
        WORLD_SETTING,
        TIMELINE,
        FORESHADOWING_NOTE,
        REFERENCE,
        READER_FEEDBACK,
        MIXED
    }

    public record DocumentInput(
        String relativePath,
        String originalName,
        byte[] content,
        DocumentKind declaredKind
    ) {
    }

    public record ParsedDocument(
        String relativePath,
        String originalName,
        String normalizedContent,
        DocumentKind kind,
        double confidence,
        List<String> reasons,
        List<DocumentSection> sections,
        boolean requiresModelFallback,
        boolean requiresUserConfirmation,
        boolean ignored
    ) {
    }

    public record DocumentSection(
        int ordinal,
        String title,
        int startOffset,
        int endOffset,
        DocumentKind kind,
        String content
    ) {
    }

    private record Classification(DocumentKind kind, double confidence, List<String> reasons) {
    }

    private record Heading(int level, String title, int start, int end) {
    }
}
