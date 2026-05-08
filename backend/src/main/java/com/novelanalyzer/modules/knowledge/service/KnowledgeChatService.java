package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeChatService {

    private final LangGraphWorkerClient langGraphWorkerClient;
    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;
    private final CrawlerService crawlerService;
    private final AsyncTaskExecutor streamTaskExecutor;

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor) {
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.crawlerService = crawlerService;
        this.streamTaskExecutor = streamTaskExecutor;
    }

    public KnowledgeChatResponseVO chat(KnowledgeChatRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        try {
            Long completedBookId = completeSelectedCandidateIfNeeded(request);
            AsyncJobSubmitResponse completedIndexJob = indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId());
            Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId);
            KnowledgeChatResponseVO response = langGraphWorkerClient.runKnowledgeChat(payload);
            attachCompletedBookId(response, completedBookId);
            attachCompletedIndexJob(response, completedIndexJob);
            maybeSubmitIndexJob(request, response, authUser.getUserId(), completedBookId);
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "knowledge candidate continuation failed");
        }
    }

    public SseEmitter streamChat(KnowledgeChatRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        streamTaskExecutor.execute(() -> {
            try {
                AuthUserHolder.set(authUser);
                emitter.send(SseEmitter.event().name("start").data(Map.of("event", "start", "traceId", "")));
                Long completedBookId = completeSelectedCandidateIfNeeded(request);
                AsyncJobSubmitResponse completedIndexJob = indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId());
                Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId);
                KnowledgeChatResponseVO response = langGraphWorkerClient.streamKnowledgeChat(
                    payload,
                    delta -> {
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(Map.of("event", "delta", "delta", delta)));
                        } catch (Exception ignored) {
                            emitter.completeWithError(ignored);
                        }
                    },
                    cancelled::get
                );
                attachCompletedBookId(response, completedBookId);
                attachCompletedIndexJob(response, completedIndexJob);
                maybeSubmitIndexJob(request, response, authUser.getUserId(), completedBookId);
                emitter.send(SseEmitter.event().name("done").data(Map.of("event", "done", "data", response)));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            } finally {
                AuthUserHolder.clear();
            }
        });
        return emitter;
    }

    private Map<String, Object> buildWorkerPayload(KnowledgeChatRequest request, Long userId, Long completedBookId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", trimToNull(request.getQuestion()));
        putIfPresent(payload, "bookName", trimToNull(request.getBookName()));
        putIfPresent(payload, "bookId", completedBookId == null ? request.getBookId() : completedBookId);
        if (completedBookId == null) {
            putIfPresent(payload, "selectedCandidate", toCandidatePayload(request.getSelectedCandidate()));
        } else if (trimToNull(request.getBookName()) == null && request.getSelectedCandidate() != null) {
            putIfPresent(payload, "bookName", trimToNull(request.getSelectedCandidate().getBookName()));
        }
        putIfPresent(payload, "mode", trimToNull(request.getMode()));
        putIfPresent(payload, "contextSummary", trimToNull(request.getContextSummary()));
        List<Map<String, Object>> history = toHistoryPayload(request.getHistory());
        if (!history.isEmpty()) {
            payload.put("history", history);
        }
        putIfPresent(payload, "userId", userId);
        payload.put("limits", request.getLimits() == null ? Map.of() : request.getLimits());
        return payload;
    }

    private List<Map<String, Object>> toHistoryPayload(List<KnowledgeChatRequest.ChatMessageDTO> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
            .limit(8)
            .map(message -> {
                Map<String, Object> item = new LinkedHashMap<>();
                String role = trimToNull(message.getRole());
                String content = trimToNull(message.getContent());
                if (!"assistant".equals(role)) {
                    role = "user";
                }
                putIfPresent(item, "role", role);
                putIfPresent(item, "content", truncate(content, 1000));
                return item;
            })
            .filter(item -> item.containsKey("content"))
            .toList();
    }

    private Map<String, Object> toCandidatePayload(KnowledgeChatRequest.CandidateDTO candidate) {
        if (candidate == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "bookId", candidate.getBookId());
        putIfPresent(payload, "platform", trimToNull(candidate.getPlatform()));
        putIfPresent(payload, "platformBookId", trimToNull(candidate.getPlatformBookId()));
        putIfPresent(payload, "bookName", trimToNull(candidate.getBookName()));
        putIfPresent(payload, "author", trimToNull(candidate.getAuthor()));
        putIfPresent(payload, "intro", trimToNull(candidate.getIntro()));
        putIfPresent(payload, "bookUrl", trimToNull(candidate.getBookUrl()));
        putIfPresent(payload, "local", candidate.getLocal());
        return payload;
    }

    private void maybeSubmitIndexJob(KnowledgeChatRequest request,
                                     KnowledgeChatResponseVO response,
                                     Long userId,
                                     Long completedBookId) {
        if (response == null || response.getActions() == null || !response.getActions().contains("index_book")) {
            return;
        }
        if (completedBookId != null) {
            return;
        }
        Long bookId = completedBookId == null ? resolveLocalBookId(request) : completedBookId;
        if (bookId == null) {
            return;
        }
        AsyncJobSubmitResponse jobResponse = knowledgeIndexJobExecutor.submitAndExecute(bookId, userId);
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("localBookId", bookId);
        resultJson.put("indexJob", jobResponse);
        response.setResultJson(resultJson);
    }

    private Long completeSelectedCandidateIfNeeded(KnowledgeChatRequest request) {
        KnowledgeChatRequest.CandidateDTO selectedCandidate = request.getSelectedCandidate();
        if (selectedCandidate == null) {
            return null;
        }
        if (selectedCandidate.getBookId() != null) {
            fetchLocalCandidateChaptersIfNeeded(selectedCandidate, request);
            return selectedCandidate.getBookId();
        }
        if (Boolean.TRUE.equals(selectedCandidate.getLocal())) {
            return null;
        }
        return crawlerService.completeExternalBookCandidate(
            trimToNull(selectedCandidate.getPlatform()),
            trimToNull(selectedCandidate.getPlatformBookId()),
            trimToNull(selectedCandidate.getBookName()),
            trimToNull(selectedCandidate.getAuthor()),
            trimToNull(selectedCandidate.getIntro()),
            trimToNull(selectedCandidate.getBookUrl()),
            resolveIndexChapterCount(request)
        );
    }

    private void fetchLocalCandidateChaptersIfNeeded(KnowledgeChatRequest.CandidateDTO selectedCandidate,
                                                     KnowledgeChatRequest request) {
        String platform = trimToNull(selectedCandidate.getPlatform());
        if (platform == null) {
            return;
        }
        CrawlerChapterRequest chapterRequest = new CrawlerChapterRequest();
        chapterRequest.setPlatform(platform);
        chapterRequest.setBookId(selectedCandidate.getBookId());
        chapterRequest.setChapterCount(resolveIndexChapterCount(request));
        crawlerService.getChapters(chapterRequest);
    }

    private AsyncJobSubmitResponse indexCompletedCandidateIfNeeded(Long completedBookId, Long userId) {
        if (completedBookId == null) {
            return null;
        }
        return knowledgeIndexJobExecutor.submitAndExecuteBlocking(completedBookId, userId);
    }

    private void attachCompletedBookId(KnowledgeChatResponseVO response, Long completedBookId) {
        if (response == null || completedBookId == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("localBookId", completedBookId);
        response.setResultJson(resultJson);
    }

    private void attachCompletedIndexJob(KnowledgeChatResponseVO response, AsyncJobSubmitResponse completedIndexJob) {
        if (response == null || completedIndexJob == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("indexJob", completedIndexJob);
        response.setResultJson(resultJson);
    }

    private Long resolveLocalBookId(KnowledgeChatRequest request) {
        if (request.getBookId() != null) {
            return request.getBookId();
        }
        KnowledgeChatRequest.CandidateDTO selectedCandidate = request.getSelectedCandidate();
        if (selectedCandidate == null) {
            return null;
        }
        if (selectedCandidate.getBookId() != null) {
            return selectedCandidate.getBookId();
        }
        if (Boolean.TRUE.equals(selectedCandidate.getLocal())) {
            return null;
        }
        return null;
    }

    private int resolveIndexChapterCount(KnowledgeChatRequest request) {
        Object value = request.getLimits() == null ? null : request.getLimits().get("chapterCount");
        if (value instanceof Number number) {
            return Math.min(Math.max(number.intValue(), 1), 10);
        }
        return 3;
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
