package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatMemoryStore;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentTraceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeMemoryCandidateService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatServiceUnitTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
        TraceIdHolder.clear();
    }

    @Test
    void shouldCompleteExternalCandidateBeforeWorkerAnalysisAndClampToTenChapters() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-404",
            "Long Book",
            "Author L",
            "Long intro",
            "https://fanqienovel.com/page/ext-404",
            10
        )).thenReturn(404L);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("analysis done");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("continue analysis");
        request.setLimits(Map.of("chapterCount", 99));
        KnowledgeChatRequest.CandidateDTO candidate = new KnowledgeChatRequest.CandidateDTO();
        candidate.setPlatform("fanqie");
        candidate.setPlatformBookId("ext-404");
        candidate.setBookName("Long Book");
        candidate.setAuthor("Author L");
        candidate.setIntro("Long intro");
        candidate.setBookUrl("https://fanqienovel.com/page/ext-404");
        candidate.setLocal(false);
        request.setSelectedCandidate(candidate);

        KnowledgeChatResponseVO response = service.chat(request);

        assertThat(response.getResultJson()).containsEntry("localBookId", 404L);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("bookId", 404L);
        assertThat(payloadCaptor.getValue()).containsEntry("bookName", "Long Book");
        assertThat(payloadCaptor.getValue()).doesNotContainKey("selectedCandidate");
        verify(indexJobExecutor).submitAndExecuteBlocking(404L, 7L);

        InOrder inOrder = inOrder(crawlerService, indexJobExecutor, workerClient);
        inOrder.verify(crawlerService).completeExternalBookCandidate(
            eq("fanqie"),
            eq("ext-404"),
            eq("Long Book"),
            eq("Author L"),
            eq("Long intro"),
            eq("https://fanqienovel.com/page/ext-404"),
            eq(10)
        );
        inOrder.verify(indexJobExecutor).submitAndExecuteBlocking(404L, 7L);
        inOrder.verify(workerClient).runKnowledgeChat(any());
    }

    @Test
    void shouldIndexCompletedExternalCandidateBeforeWorkerAnalysis() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-202",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-202",
            5
        )).thenReturn(202L);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("开篇卖点来自目标和冲突。[1]");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("继续分析");
        request.setLimits(Map.of("chapterCount", 5));
        KnowledgeChatRequest.CandidateDTO candidate = new KnowledgeChatRequest.CandidateDTO();
        candidate.setPlatform("fanqie");
        candidate.setPlatformBookId("ext-202");
        candidate.setBookName("External Book");
        candidate.setAuthor("Author E");
        candidate.setIntro("External intro");
        candidate.setBookUrl("https://fanqienovel.com/page/ext-202");
        candidate.setLocal(false);
        request.setSelectedCandidate(candidate);

        KnowledgeChatResponseVO response = service.chat(request);

        assertThat(response.getStatus()).isEqualTo("answered");
        assertThat(response.getResultJson()).containsEntry("localBookId", 202L);
        InOrder inOrder = inOrder(crawlerService, indexJobExecutor, workerClient);
        inOrder.verify(crawlerService).completeExternalBookCandidate(
            eq("fanqie"),
            eq("ext-202"),
            eq("External Book"),
            eq("Author E"),
            eq("External intro"),
            eq("https://fanqienovel.com/page/ext-202"),
            eq(5)
        );
        inOrder.verify(indexJobExecutor).submitAndExecuteBlocking(202L, 7L);
        inOrder.verify(workerClient).runKnowledgeChat(any());
    }

    @Test
    void shouldFetchChaptersAndIndexLocalSelectedCandidateBeforeWorkerAnalysis() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("analysis done");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("continue analysis");
        request.setLimits(Map.of("chapterCount", 5));
        KnowledgeChatRequest.CandidateDTO candidate = new KnowledgeChatRequest.CandidateDTO();
        candidate.setBookId(101L);
        candidate.setPlatform("fanqie");
        candidate.setPlatformBookId("local-101");
        candidate.setBookName("Local Book");
        candidate.setLocal(true);
        request.setSelectedCandidate(candidate);

        KnowledgeChatResponseVO response = service.chat(request);

        assertThat(response.getResultJson()).containsEntry("localBookId", 101L);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("bookId", 101L);
        assertThat(payloadCaptor.getValue()).containsEntry("bookName", "Local Book");
        assertThat(payloadCaptor.getValue()).doesNotContainKey("selectedCandidate");
        verify(crawlerService).getChapters(argThat(chapterRequest ->
            chapterRequest.getBookId().equals(101L)
                && chapterRequest.getPlatform().equals("fanqie")
                && chapterRequest.getChapterCount().equals(5)
        ));
        verify(indexJobExecutor).submitAndExecuteBlocking(101L, 7L);

        InOrder inOrder = inOrder(crawlerService, indexJobExecutor, workerClient);
        inOrder.verify(crawlerService).getChapters(any(CrawlerChapterRequest.class));
        inOrder.verify(indexJobExecutor).submitAndExecuteBlocking(101L, 7L);
        inOrder.verify(workerClient).runKnowledgeChat(any());
    }

    @Test
    void shouldPassConversationContextToWorkerPayload() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("follow up");
        request.setContextSummary("current book: Book Alpha");
        request.setPreferredSkillId("webnovel-outline-building");
        KnowledgeChatRequest.ChatMessageDTO userMessage = new KnowledgeChatRequest.ChatMessageDTO();
        userMessage.setRole("user");
        userMessage.setContent("Book Alpha setting?");
        KnowledgeChatRequest.ChatMessageDTO assistantMessage = new KnowledgeChatRequest.ChatMessageDTO();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("Book Alpha has a survival game setup.");
        request.setHistory(List.of(userMessage, assistantMessage));

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("contextSummary", "current book: Book Alpha");
        assertThat(payloadCaptor.getValue()).containsEntry("preferredSkillId", "webnovel-outline-building");
        assertThat((List<?>) payloadCaptor.getValue().get("history")).hasSize(2);
    }

    @Test
    void shouldPassHistoryBeyondTwelveMessagesUntilContextBudgetIsReached() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        List<KnowledgeChatRequest.ChatMessageDTO> history = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            KnowledgeChatRequest.ChatMessageDTO message = new KnowledgeChatRequest.ChatMessageDTO();
            message.setRole(index % 2 == 0 ? "user" : "assistant");
            message.setContent("history-message-" + (index + 1));
            history.add(message);
        }
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("continue");
        request.setHistory(history);

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadHistory = (List<Map<String, Object>>) payloadCaptor.getValue().get("history");
        assertThat(payloadHistory).hasSize(30);
        assertThat(payloadHistory.get(0)).containsEntry("content", "history-message-1");
        assertThat(payloadHistory.get(29)).containsEntry("content", "history-message-30");
    }

    @Test
    void shouldPreferWorkerCompactedSummaryForDurableConversationMemory() {
        KnowledgeChatService service = new KnowledgeChatService(
            mock(LangGraphWorkerClient.class),
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor()
        );
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("继续完成当前大纲");
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setAnswer("已经补齐前三章钩子。");
        String compactedSummary = "<!-- NOVAL_CONTEXT_STATE_V1 {\"covered\":[\"hash-1\"],\"generation\":1} -->\n长期目标与硬约束：保留底层职业。";
        response.setResultJson(Map.of(
            "contextCompaction", Map.of(
                "status", "compacted",
                "compactedSummary", compactedSummary
            )
        ));

        String summary = ReflectionTestUtils.invokeMethod(
            service,
            "buildConversationSummary",
            request,
            response,
            "runtime summary"
        );

        assertThat(summary)
            .startsWith("<!-- NOVAL_CONTEXT_STATE_V1")
            .contains("长期目标与硬约束：保留底层职业。")
            .contains("最近用户目标：继续完成当前大纲")
            .contains("上一轮结论：已经补齐前三章钩子。");
    }

    @Test
    void shouldPassStructuredContextBundleAlongsideLegacyContextToWorkerPayload() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("revise opening");
        request.setConversationId("conv-context");
        request.setProjectId(99L);
        request.setWorkId(199L);
        request.setBookId(123L);
        request.setBookName("Book Alpha");
        request.setMode("outline");
        request.setContextSummary("legacy summary");
        KnowledgeChatRequest.ChatMessageDTO userMessage = new KnowledgeChatRequest.ChatMessageDTO();
        userMessage.setRole("user");
        userMessage.setContent("old question");
        KnowledgeChatRequest.ChatMessageDTO assistantMessage = new KnowledgeChatRequest.ChatMessageDTO();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("old answer");
        request.setHistory(List.of(userMessage, assistantMessage));

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("contextSummary", "legacy summary");
        assertThat(payload).containsEntry("workId", 199L);
        assertThat((List<?>) payload.get("history")).hasSize(2);
        assertThat(payload).containsKey("contextBundle");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextBundle = (Map<String, Object>) payload.get("contextBundle");
        @SuppressWarnings("unchecked")
        Map<String, Object> systemBaseline = (Map<String, Object>) contextBundle.get("systemBaseline");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectProfile = (Map<String, Object>) contextBundle.get("projectProfile");
        @SuppressWarnings("unchecked")
        Map<String, Object> threadSummary = (Map<String, Object>) contextBundle.get("threadSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> currentTurn = (Map<String, Object>) contextBundle.get("currentTurn");
        @SuppressWarnings("unchecked")
        Map<String, Object> systemContent = (Map<String, Object>) systemBaseline.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectContent = (Map<String, Object>) projectProfile.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> threadContent = (Map<String, Object>) threadSummary.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> turnContent = (Map<String, Object>) currentTurn.get("content");
        assertThat(systemBaseline).containsEntry("scope", "system");
        assertThat(systemContent).containsEntry("domain", "webnovel");
        assertThat(projectProfile).containsEntry("scope", "project");
        assertThat(projectContent)
            .containsEntry("projectId", 99L)
            .containsEntry("workId", 199L)
            .containsEntry("bookId", 123L)
            .containsEntry("bookName", "Book Alpha");
        assertThat(threadSummary).containsEntry("scope", "thread");
        assertThat(threadContent)
            .containsEntry("conversationId", "conv-context")
            .containsEntry("summary", "legacy summary");
        assertThat(currentTurn).containsEntry("scope", "turn");
        assertThat(turnContent)
            .containsEntry("question", "revise opening")
            .containsEntry("userId", 7L)
            .containsEntry("projectId", 99L)
            .containsEntry("workId", 199L)
            .containsEntry("conversationId", "conv-context")
            .containsEntry("mode", "outline");
    }

    @Test
    void shouldPassTraceIdToWorkerPayload() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);
        TraceIdHolder.set("trace-rank-001");

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("最近男频都市脑洞题材趋势是什么？");

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("traceId", "trace-rank-001");
    }

    @Test
    void shouldPassProjectIdToWorkerPayloadAndPersistAgentTrace() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeAgentTraceService traceService = mock(KnowledgeAgentTraceService.class);
        KnowledgeProjectService projectService = mock(KnowledgeProjectService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            indexJobExecutor,
            crawlerService,
            taskExecutor(),
            null,
            traceService,
            projectService
        );
        AuthUser authUser = AuthUser.of(7L, "writer", Set.of("USER"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of(
            "taskGraph", Map.of("tasks", List.of(Map.of("type", "chapter_outline"))),
            "toolRuns", List.of()
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setProjectId(99L);
        request.setWorkId(199L);
        request.setQuestion("帮我做前三章细纲");

        service.chat(request);

        verify(projectService).ensureOwned(99L, 7L);
        verify(projectService).ensureWorkOwned(99L, 199L, 7L);
        InOrder inOrder = inOrder(projectService, workerClient);
        inOrder.verify(projectService).ensureOwned(99L, 7L);
        inOrder.verify(projectService).ensureWorkOwned(99L, 199L, 7L);
        inOrder.verify(projectService).bindConversation(eq(99L), eq(7L), anyString());
        inOrder.verify(workerClient).runKnowledgeChat(any());
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("projectId", 99L);
        assertThat(payloadCaptor.getValue()).containsEntry("workId", 199L);
        verify(traceService).persistFromChat(eq(7L), eq(99L), anyString(), eq("帮我做前三章细纲"), eq(workerResponse));
    }

    @Test
    void shouldRejectWorkIdWithoutProjectBeforeCallingWorker() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeProjectService projectService = mock(KnowledgeProjectService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            projectService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("继续当前作品");
        request.setWorkId(199L);

        assertThatThrownBy(() -> service.chat(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("projectId is required");
        verifyNoInteractions(workerClient, projectService);
    }

    @Test
    void shouldResolveReferenceWorkIdsBeforeSendingTrustedScopesToWorker() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeProjectService projectService = mock(KnowledgeProjectService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            projectService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        when(projectService.resolveReferenceWorks(7L, List.of(301L, 302L), 99L, 199L)).thenReturn(List.of(
            new KnowledgeProjectService.ReferenceWorkScope(30L, 301L, "Reference A"),
            new KnowledgeProjectService.ReferenceWorkScope(40L, 302L, "Reference B")
        ));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("compare my novels");
        request.setProjectId(99L);
        request.setWorkId(199L);
        request.setReferenceWorkIds(List.of(301L, 302L));

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().get("referenceWorks")).isEqualTo(List.of(
            Map.of("projectId", 30L, "workId", 301L, "title", "Reference A"),
            Map.of("projectId", 40L, "workId", 302L, "title", "Reference B")
        ));
    }

    @Test
    void shouldPersistMemoryCandidatesFromWorkerResult() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeAgentTraceService traceService = mock(KnowledgeAgentTraceService.class);
        KnowledgeProjectService projectService = mock(KnowledgeProjectService.class);
        KnowledgeMemoryCandidateService memoryCandidateService = mock(KnowledgeMemoryCandidateService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            indexJobExecutor,
            crawlerService,
            taskExecutor(),
            null,
            traceService,
            projectService,
            memoryCandidateService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        Map<String, Object> candidate = Map.of(
            "scope", "project",
            "type", "constraint",
            "content", "不后宫；前三章快节奏",
            "confidence", 0.82,
            "sourceTraceId", "trace-1"
        );
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of(
            "memoryCandidates", List.of(candidate),
            "trace", Map.of("traceId", "trace-1"),
            "taskGraph", Map.of("tasks", List.of()),
            "toolRuns", List.of()
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setProjectId(900L);
        request.setQuestion("不后宫，前三章快节奏");

        service.chat(request);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Map<String, Object>>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryCandidateService).persistCandidates(eq(900L), eq(7L), candidatesCaptor.capture(), eq("trace-1"));
        assertThat(candidatesCaptor.getValue()).containsExactly(candidate);
    }

    @Test
    void shouldNotPersistMemoryCandidatesTwiceWhenWorkerAlreadyPersistedThem() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeMemoryCandidateService memoryCandidateService = mock(KnowledgeMemoryCandidateService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            mock(KnowledgeProjectService.class),
            memoryCandidateService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of(
            "memoryCandidates", List.of(Map.of("scope", "project", "type", "constraint")),
            "memoryCandidatePayloads", List.of(Map.of("scope", "project", "type", "constraint", "content", "private")),
            "memoryCandidatesPersisted", 1,
            "trace", Map.of("traceId", "trace-1"),
            "taskGraph", Map.of("tasks", List.of()),
            "toolRuns", List.of()
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setProjectId(900L);
        request.setQuestion("remember this constraint");

        service.chat(request);

        verifyNoInteractions(memoryCandidateService);
        assertThat(workerResponse.getResultJson()).doesNotContainKey("memoryCandidatePayloads");
    }

    @Test
    void shouldRecoverOnlyWorkerFailedMemoryCandidatesByFactKey() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeMemoryCandidateService memoryCandidateService = mock(KnowledgeMemoryCandidateService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            mock(KnowledgeProjectService.class),
            memoryCandidateService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Map<String, Object> persisted = Map.of(
            "scope", "project", "type", "fact", "content", "already saved",
            "factKey", "fact.shared", "candidateKey", "candidate.saved"
        );
        Map<String, Object> failed = Map.ofEntries(
            Map.entry("scope", "project"),
            Map.entry("type", "fact"),
            Map.entry("content", "retry me"),
            Map.entry("factKey", "fact.shared"),
            Map.entry("candidateKey", "candidate.failed"),
            Map.entry("provenanceJson", "{\"source\":\"worker_memory_extractor\"}"),
            Map.entry("evidenceJson", "{\"sourceKind\":\"user_turn\"}"),
            Map.entry("extractorVersion", "memory-extractor-v1"),
            Map.entry("conversationId", "conv-partial"),
            Map.entry("ttlDays", 30)
        );
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.ofEntries(
            Map.entry("memoryCandidatePayloads", List.of(persisted, failed)),
            Map.entry("memoryCandidatesPersisted", 1),
            Map.entry("memoryDiagnostics", Map.of(
                "candidatePersistence", Map.of(
                    "saved", 1,
                    "failed", 1,
                    "failures", List.of(Map.of(
                        "factKey", "fact.shared",
                        "candidateKey", "candidate.failed",
                        "reason", "TimeoutError"
                    ))
                )
            )),
            Map.entry("trace", Map.of("traceId", "trace-partial")),
            Map.entry("taskGraph", Map.of("tasks", List.of())),
            Map.entry("toolRuns", List.of())
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        when(memoryCandidateService.persistCandidates(any(), any(), any(), any())).thenReturn(1);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setProjectId(900L);
        request.setQuestion("remember both facts");

        service.chat(request);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Map<String, Object>>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryCandidateService).persistCandidates(eq(900L), eq(7L), candidatesCaptor.capture(), eq("trace-partial"));
        assertThat(candidatesCaptor.getValue()).containsExactly(failed);
        assertThat(workerResponse.getResultJson())
            .containsEntry("memoryCandidatesBackendRecovered", 1)
            .containsEntry("memoryCandidatesPersisted", 2)
            .doesNotContainKey("memoryCandidatePayloads");
    }

    @Test
    void shouldNotGuessLegacyPartialFailureWhenFactKeyIsAmbiguous() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeMemoryCandidateService memoryCandidateService = mock(KnowledgeMemoryCandidateService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            mock(KnowledgeProjectService.class),
            memoryCandidateService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.ofEntries(
            Map.entry("memoryCandidatePayloads", List.of(
                Map.of("scope", "project", "type", "fact", "content", "first", "factKey", "fact.shared"),
                Map.of("scope", "project", "type", "fact", "content", "second", "factKey", "fact.shared")
            )),
            Map.entry("memoryCandidatesPersisted", 1),
            Map.entry("memoryDiagnostics", Map.of(
                "candidatePersistence", Map.of(
                    "saved", 1,
                    "failed", 1,
                    "failures", List.of(Map.of("factKey", "fact.shared", "reason", "TimeoutError"))
                )
            )),
            Map.entry("taskGraph", Map.of("tasks", List.of())),
            Map.entry("toolRuns", List.of())
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setProjectId(900L);
        request.setQuestion("remember both facts");

        service.chat(request);

        verifyNoInteractions(memoryCandidateService);
        assertThat(workerResponse.getResultJson())
            .containsEntry("memoryCandidatesPersisted", 1)
            .doesNotContainKey("memoryCandidatePayloads");
    }

    @Test
    void shouldRecoverUserMemoryCandidateWhenProjectIsNotSelected() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeMemoryCandidateService memoryCandidateService = mock(KnowledgeMemoryCandidateService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            mock(KnowledgeProjectService.class),
            memoryCandidateService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("answer");
        response.setResultJson(Map.ofEntries(
            Map.entry("memoryCandidatePayloads", List.of(Map.of(
                "scope", "user", "type", "preference", "content", "user preference",
                "factKey", "user.preference.one", "candidateKey", "candidate-user-1"
            ))),
            Map.entry("memoryDiagnostics", Map.of(
                "candidatePersistence", Map.of(
                    "saved", 0,
                    "failed", 1,
                    "failures", List.of(Map.of("candidateKey", "candidate-user-1"))
                )
            )),
            Map.entry("trace", Map.of("traceId", "trace-user-fallback")),
            Map.entry("taskGraph", Map.of("tasks", List.of())),
            Map.entry("toolRuns", List.of())
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(response);
        when(memoryCandidateService.persistCandidates(any(), any(), any(), any())).thenReturn(1);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("remember user preference");

        service.chat(request);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Map<String, Object>>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryCandidateService).persistCandidates(eq(null), eq(7L), candidatesCaptor.capture(), eq("trace-user-fallback"));
        assertThat(candidatesCaptor.getValue()).hasSize(1);
        assertThat(response.getResultJson()).doesNotContainKey("memoryCandidatePayloads");
    }

    @Test
    void shouldPreserveAnswerAndExposeOnlySafeDiagnosticsWhenBackendMemoryFallbackFails() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeMemoryCandidateService memoryCandidateService = mock(KnowledgeMemoryCandidateService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            mock(KnowledgeAgentTraceService.class),
            mock(KnowledgeProjectService.class),
            memoryCandidateService
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("complete answer");
        response.setResultJson(Map.ofEntries(
            Map.entry("memoryCandidatePayloads", List.of(Map.of(
                "scope", "project",
                "type", "fact",
                "content", "PRIVATE_MEMORY_BODY",
                "factKey", "story.fact.one",
                "candidateKey", "candidate-fallback-1"
            ))),
            Map.entry("taskGraph", Map.of("tasks", List.of())),
            Map.entry("toolRuns", List.of())
        ));
        when(workerClient.runKnowledgeChat(any())).thenReturn(response);
        when(memoryCandidateService.persistCandidates(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("PRIVATE_DATABASE_FAILURE_DETAIL"));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setProjectId(900L);
        request.setQuestion("remember this fact");

        KnowledgeChatResponseVO actual = service.chat(request);

        assertThat(actual.getStatus()).isEqualTo("answered");
        assertThat(actual.getAnswer()).isEqualTo("complete answer");
        assertThat(actual.getResultJson()).doesNotContainKey("memoryCandidatePayloads");
        assertThat(String.valueOf(actual.getResultJson()))
            .contains("backendFallback", "failed", "IllegalStateException")
            .doesNotContain("PRIVATE_MEMORY_BODY", "PRIVATE_DATABASE_FAILURE_DETAIL");
    }

    @Test
    void shouldClampChatPayloadAndWhitelistLimitsBeforeWorkerCall() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("q".repeat(70_000));
        request.setContextSummary("s".repeat(1_100_000));
        request.setLimits(Map.of(
            "chapterCount", 99,
            "evidenceLimit", 999,
            "chapterLimitPerBook", 99,
            "timeoutMillis", 999_999,
            "unexpected", 123
        ));
        KnowledgeChatRequest.ChatMessageDTO first = new KnowledgeChatRequest.ChatMessageDTO();
        first.setRole("assistant");
        first.setContent("a".repeat(80_000));
        KnowledgeChatRequest.ChatMessageDTO second = new KnowledgeChatRequest.ChatMessageDTO();
        second.setRole("system");
        second.setContent("b");
        request.setHistory(List.of(first, second));

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat((String) payload.get("question")).hasSizeLessThanOrEqualTo(64_000);
        assertThat((String) payload.get("contextSummary")).hasSizeLessThanOrEqualTo(900_000);
        assertThat((String) payload.get("question")).hasSizeGreaterThan(8_000);
        assertThat((String) payload.get("contextSummary")).hasSizeGreaterThan(24_000);
        assertThat((List<?>) payload.get("history")).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) payload.get("history");
        assertThat((String) history.get(0).get("content")).hasSizeLessThanOrEqualTo(64_000);
        assertThat((String) history.get(0).get("content")).hasSizeGreaterThan(8_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) payload.get("limits");
        assertThat(limits).containsEntry("chapterCount", 10);
        assertThat(limits).containsEntry("evidenceLimit", 20);
        assertThat(limits).containsEntry("chapterLimitPerBook", 5);
        assertThat(limits).containsEntry("timeoutMillis", 600_000);
        assertThat(limits).doesNotContainKey("unexpected");
    }

    @Test
    void shouldClampLastQuestionToLegacyMemoryColumnWidth() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatMemoryStore memoryStore = mock(KnowledgeChatMemoryStore.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor(), memoryStore);
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("q".repeat(70_000));
        request.setMode("research");

        service.chat(request);

        ArgumentCaptor<String> lastQuestionCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryStore).save(
            anyString(),
            eq(7L),
            anyString(),
            lastQuestionCaptor.capture(),
            eq("answer"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(lastQuestionCaptor.getValue()).hasSizeLessThanOrEqualTo(1_000);
        assertThat(lastQuestionCaptor.getValue()).hasSizeGreaterThan(100);
    }

    @Test
    void shouldReturnConversationIdAndReuseServerMemoryOnFollowup() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO firstWorkerResponse = new KnowledgeChatResponseVO();
        firstWorkerResponse.setStatus("answered");
        firstWorkerResponse.setAnswer("第一轮回答");
        firstWorkerResponse.setActions(List.of());
        firstWorkerResponse.setResultJson(Map.of(
            "memorySummary", "当前作品：星河旧梦\n上一轮结论：旧星门坐标是核心设定",
            "bookName", "星河旧梦",
            "intent", "single_book_research"
        ));
        KnowledgeChatResponseVO secondWorkerResponse = new KnowledgeChatResponseVO();
        secondWorkerResponse.setStatus("answered");
        secondWorkerResponse.setAnswer("第二轮回答");
        secondWorkerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(firstWorkerResponse, secondWorkerResponse);

        KnowledgeChatRequest firstRequest = new KnowledgeChatRequest();
        firstRequest.setQuestion("星河旧梦的设定是什么？");
        firstRequest.setReasoningMode("deep");
        firstRequest.setReasoningEffort("xhigh");
        KnowledgeChatResponseVO firstResponse = service.chat(firstRequest);

        String conversationId = (String) firstResponse.getResultJson().get("conversationId");
        assertThat(conversationId).isNotBlank();

        KnowledgeChatRequest secondRequest = new KnowledgeChatRequest();
        secondRequest.setQuestion("那它的卖点呢？");
        secondRequest.setConversationId(conversationId);
        secondRequest.setReasoningMode("deep");
        secondRequest.setReasoningEffort("xhigh");
        KnowledgeChatRequest.ChatMessageDTO priorUser = new KnowledgeChatRequest.ChatMessageDTO();
        priorUser.setRole("user");
        priorUser.setContent(firstRequest.getQuestion());
        KnowledgeChatRequest.ChatMessageDTO priorAssistant = new KnowledgeChatRequest.ChatMessageDTO();
        priorAssistant.setRole("assistant");
        priorAssistant.setContent(firstWorkerResponse.getAnswer());
        secondRequest.setHistory(List.of(priorUser, priorAssistant));
        service.chat(secondRequest);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient, org.mockito.Mockito.times(2)).runKnowledgeChat(payloadCaptor.capture());
        Map<String, Object> secondPayload = payloadCaptor.getAllValues().get(1);
        assertThat(secondPayload).containsEntry("conversationId", conversationId);
        for (Map<String, Object> payload : payloadCaptor.getAllValues()) {
            assertThat(payload).containsEntry("reasoningMode", "deep").containsEntry("reasoningEffort", "xhigh");
        }
        assertThat((List<?>) secondPayload.get("history")).hasSize(2);
        assertThat(secondPayload.get("contextSummary")).asString().contains("旧星门坐标");
    }

    @Test
    void shouldPreferServerMemoryBeforeFrontendContextSummary() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatMemoryStore memoryStore = mock(KnowledgeChatMemoryStore.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor(), memoryStore);
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        doReturn(java.util.Optional.of(new KnowledgeChatMemoryStore.ChatMemory(
            "conv-memory",
            7L,
            "服务端记忆：上一轮已确认男频都市脑洞榜一"
        ))).when(memoryStore).find("conv-memory", 7L);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("answer");
        workerResponse.setActions(List.of());
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setConversationId("conv-memory");
        request.setQuestion("继续做大纲");
        request.setContextSummary("前端摘要：旧浏览器里残留的总结");

        service.chat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).runKnowledgeChat(payloadCaptor.capture());
        String contextSummary = (String) payloadCaptor.getValue().get("contextSummary");
        assertThat(contextSummary).startsWith("服务端记忆：上一轮已确认男频都市脑洞榜一");
        assertThat(contextSummary).contains("前端摘要：旧浏览器里残留的总结");
    }

    @Test
    void shouldStreamKnowledgeChatAfterCandidateCompletionAndIndexing() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-808",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-808",
            5
        )).thenReturn(808L);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("stream answer[1]");
        workerResponse.setActions(List.of());
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("continue analysis");
        request.setLimits(Map.of("chapterCount", 5));
        KnowledgeChatRequest.CandidateDTO candidate = new KnowledgeChatRequest.CandidateDTO();
        candidate.setPlatform("fanqie");
        candidate.setPlatformBookId("ext-808");
        candidate.setBookName("External Book");
        candidate.setAuthor("Author E");
        candidate.setIntro("External intro");
        candidate.setBookUrl("https://fanqienovel.com/page/ext-808");
        candidate.setLocal(false);
        request.setSelectedCandidate(candidate);

        service.streamChat(request);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerClient).streamKnowledgeChat(payloadCaptor.capture(), any(), any(), any());
        assertThat(payloadCaptor.getValue()).containsEntry("bookId", 808L);
        assertThat(payloadCaptor.getValue()).containsEntry("bookName", "External Book");
        verify(indexJobExecutor).submitAndExecuteBlocking(808L, 7L);

        InOrder inOrder = inOrder(crawlerService, indexJobExecutor, workerClient);
        inOrder.verify(crawlerService).completeExternalBookCandidate(
            eq("fanqie"),
            eq("ext-808"),
            eq("External Book"),
            eq("Author E"),
            eq("External intro"),
            eq("https://fanqienovel.com/page/ext-808"),
            eq(5)
        );
        inOrder.verify(indexJobExecutor).submitAndExecuteBlocking(808L, 7L);
        inOrder.verify(workerClient).streamKnowledgeChat(any(), any(), any(), any());
    }

    @Test
    void shouldFallbackToBlockingWorkerInsideSseWhenWorkerStreamFailsBeforeFirstDelta() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(workerClient, indexJobExecutor, crawlerService, taskExecutor());
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("blocking fallback answer");
        workerResponse.setActions(List.of());
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("worker stream ended without result"));
        when(workerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("continue analysis");

        List<String> deltas = new ArrayList<>();
        service.streamChat(request, new KnowledgeChatService.StreamLifecycleListener() {
            @Override
            public void onDelta(String delta) {
                deltas.add(delta);
            }
        });

        InOrder inOrder = inOrder(workerClient);
        inOrder.verify(workerClient).streamKnowledgeChat(any(), any(), any(), any());
        inOrder.verify(workerClient).runKnowledgeChat(any());
        assertThat(deltas).containsExactly("blocking fallback answer");
    }

    @Test
    void shouldReportFallbackFailureWithoutLeakingUpstreamBodyWhenBlockingRetryAlsoFails() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient, indexJobExecutor, crawlerService, taskExecutor()
        );
        AuthUser authUser = new AuthUser();
        authUser.setUserId(7L);
        authUser.setUsername("admin");
        authUser.setRoles(Set.of("ADMIN"));
        AuthUserHolder.set(authUser);

        when(workerClient.streamKnowledgeChat(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("worker stream ended without result"));
        when(workerClient.runKnowledgeChat(any())).thenThrow(new BusinessException(
            ResultCode.INTERNAL_ERROR,
            "上游错误请求参数无效"
        ));

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("continue analysis");

        List<String> deltas = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        service.streamChat(request, new KnowledgeChatService.StreamLifecycleListener() {
            @Override
            public void onDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onFailed(Exception error) {
                failures.add(error);
            }
        });

        InOrder inOrder = inOrder(workerClient);
        inOrder.verify(workerClient).streamKnowledgeChat(any(), any(), any(), any());
        inOrder.verify(workerClient).runKnowledgeChat(any());
        assertThat(deltas).isEmpty();
        // 客户端只应看到稳定的降级失败文本，上游原始报文不外泄。
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0))
            .isInstanceOf(BusinessException.class)
            .hasMessage("knowledge chat fallback failed");
    }

    @Test
    void shouldPreserveWhitespaceOnlyDelta() throws Exception {
        KnowledgeChatService service = new KnowledgeChatService(
            mock(LangGraphWorkerClient.class),
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor()
        );
        SseEmitter emitter = mock(SseEmitter.class);
        Method sendDelta = KnowledgeChatService.class.getDeclaredMethod(
            "sendDelta", SseEmitter.class, String.class
        );
        sendDelta.setAccessible(true);

        sendDelta.invoke(service, emitter, " \n");

        verify(emitter, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldStopBlockingDurableCommitWhenExternalCancellationIsSet() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient, mock(KnowledgeIndexJobExecutor.class), mock(CrawlerService.class), taskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        AtomicBoolean cancelled = new AtomicBoolean(true);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("cancelled");

        assertThat(service.chatForDurableCommit(request, cancelled::get)).isNull();
        verify(workerClient, never()).runKnowledgeChat(any());
    }

    @Test
    void shouldStopDurableStreamBeforeWorkerWhenExternalCancellationIsSet() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient, mock(KnowledgeIndexJobExecutor.class), mock(CrawlerService.class), taskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("cancelled stream");

        service.streamChatForDurableCommit(request, new KnowledgeChatService.StreamLifecycleListener() { }, () -> true);

        verify(workerClient, never()).streamKnowledgeChat(any(), any(), any(), any());
    }

    @Test
    void shouldFailDurableStreamAfterDeltaWhenExternalCancellationWins() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient, mock(KnowledgeIndexJobExecutor.class), mock(CrawlerService.class), taskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("must not complete");
        workerResponse.setActions(List.of());
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> onDelta = invocation.getArgument(1);
            java.util.function.BooleanSupplier cancellation = invocation.getArgument(3);
            onDelta.accept("partial before cancellation");
            cancelled.set(true);
            assertThat(cancellation.getAsBoolean()).isTrue();
            return workerResponse;
        });
        List<String> deltas = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("cancel after delta");

        service.streamChatForDurableCommit(
            request,
            new KnowledgeChatService.StreamLifecycleListener() {
                @Override
                public void onDelta(String delta) {
                    deltas.add(delta);
                }

                @Override
                public void onCompleted(KnowledgeChatResponseVO response) {
                    completed.set(true);
                }

                @Override
                public void onFailed(Exception error) {
                    failed.set(true);
                    assertThat(error).hasMessageContaining("cancelled");
                }
            },
            cancelled::get
        );

        assertThat(deltas).containsExactly("partial before cancellation");
        assertThat(failed).isTrue();
        assertThat(completed).isFalse();
        verify(workerClient, never()).runKnowledgeChat(any());
    }

    @Test
    void shouldUseInjectedFallbackExecutor() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("executor fallback");
        response.setActions(List.of());
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("stream failed"));
        when(workerClient.runKnowledgeChat(any())).thenReturn(response);
        Executor fallbackExecutor = Runnable::run;
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            null,
            null,
            null,
            null,
            null,
            fallbackExecutor
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("fallback");
        service.streamChat(request);

        verify(workerClient).runKnowledgeChat(any());
    }

    @Test
    void shouldNotUseBlockingFallbackForDurableRunExecution() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeIndexJobExecutor indexJobExecutor = mock(KnowledgeIndexJobExecutor.class);
        CrawlerService crawlerService = mock(CrawlerService.class);
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient, indexJobExecutor, crawlerService, taskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("stream failed"));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("durable answer");

        assertThatThrownBy(() -> service.chatWithProgressForDurableRun(
            request,
            new KnowledgeChatService.ChatProgressListener() { },
            () -> false
        )).isInstanceOf(RuntimeException.class);
        verify(workerClient, never()).runKnowledgeChat(any());
    }

    @Test
    void shouldKeepUpstreamErrorCodeWhenBlockingFallbackFails() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("stream failed"));
        when(workerClient.runKnowledgeChat(any())).thenThrow(new BusinessException(
            ResultCode.INTERNAL_ERROR,
            "langgraph worker knowledge chat failed: upstream=400 code=unsupported_value "
                + "type=invalid_request_error param=reasoning.effort"
        ));

        List<String> failures = captureStreamFailures(workerClient, "上游 400");

        // 线上那三条 FAILED 的 error_message 只剩固定文案，故障原因整条丢失。
        // 码位必须活着走到 onFailed，它就是落库前的最后一站。
        assertThat(failures).containsExactly(
            "knowledge chat fallback failed: upstream=400 code=unsupported_value "
                + "type=invalid_request_error param=reasoning.effort"
        );
    }

    @Test
    void shouldOnlyTrustUpstreamCodesThatCameThroughTheWorkerContract() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        when(workerClient.streamKnowledgeChat(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("stream failed"));
        // 非 BusinessException 的 message 不是 worker 契约生成的，哪怕长得像码位也不认；
        // 否则任意一个第三方异常文案都能把内容塞进 ai_chat_run.error_message。
        when(workerClient.runKnowledgeChat(any()))
            .thenThrow(new IllegalStateException("upstream=400 code=leaked_from_untrusted_source"));

        List<String> failures = captureStreamFailures(workerClient, "非契约异常");

        assertThat(failures).containsExactly("knowledge chat fallback failed");
    }

    private List<String> captureStreamFailures(LangGraphWorkerClient workerClient, String question) {
        Executor fallbackExecutor = Runnable::run;
        KnowledgeChatService service = new KnowledgeChatService(
            workerClient,
            mock(KnowledgeIndexJobExecutor.class),
            mock(CrawlerService.class),
            taskExecutor(),
            null,
            null,
            null,
            null,
            null,
            null,
            fallbackExecutor
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion(question);
        List<String> failures = new ArrayList<>();
        service.streamChat(request, new KnowledgeChatService.StreamLifecycleListener() {
            @Override
            public void onFailed(Exception error) {
                failures.add(error.getMessage());
            }
        });
        return failures;
    }

    private AsyncTaskExecutor taskExecutor() {
        return new SyncTaskExecutor()::execute;
    }
}
