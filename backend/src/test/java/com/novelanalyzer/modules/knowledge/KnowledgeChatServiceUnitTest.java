package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatServiceUnitTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
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
        assertThat((List<?>) payloadCaptor.getValue().get("history")).hasSize(2);
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
        when(workerClient.streamKnowledgeChat(any(), any(), any())).thenReturn(workerResponse);

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
        verify(workerClient).streamKnowledgeChat(payloadCaptor.capture(), any(), any());
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
        inOrder.verify(workerClient).streamKnowledgeChat(any(), any(), any());
    }
    private AsyncTaskExecutor taskExecutor() {
        return new SyncTaskExecutor()::execute;
    }
}
