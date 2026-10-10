package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexSubmissionIdempotencyTest {

    @Test
    void shouldUseStableActionScopedJobKeyAndReuseSuccessfulJobs() {
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexService service = new KnowledgeIndexService(
            mock(KnowledgeRepository.class),
            mock(EmbeddingClient.class),
            mock(QdrantClient.class),
            new KnowledgeProperties(),
            asyncJobService
        );
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(88L);
        response.setStatus(AsyncJobService.STATUS_SUCCESS);
        response.setReused(true);
        ArgumentCaptor<String> runningJobKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pendingJobKey = ArgumentCaptor.forClass(String.class);
        when(asyncJobService.submitOrReuseSuccessful(
            eq("KNOWLEDGE_INDEX_BOOK"),
            runningJobKey.capture(),
            eq("book:401"),
            eq("{\"bookId\":401,\"mode\":\"ALL\"}"),
            eq(7L),
            eq(300L)
        )).thenReturn(response);
        when(asyncJobService.submitOrReuseSuccessfulPending(
            eq("KNOWLEDGE_INDEX_BOOK"),
            pendingJobKey.capture(),
            eq("book:401"),
            eq("{\"bookId\":401,\"mode\":\"ALL\"}"),
            eq(7L),
            eq(300L)
        )).thenReturn(response);

        String actionKey = "chat-run:run-1:outbox:9:index-book";
        service.submitBookIndexJob(401L, 7L, "ALL", actionKey);
        service.submitBookIndexJob(401L, 7L, "ALL", actionKey);
        service.submitBookIndexPendingJob(401L, 7L, "ALL", actionKey);
        service.submitBookIndexPendingJob(401L, 7L, "ALL", actionKey);

        assertThat(runningJobKey.getAllValues())
            .hasSize(2)
            .allMatch(key -> key.matches("book:401:action:[0-9a-f]{64}"))
            .containsOnly(runningJobKey.getAllValues().get(0));
        assertThat(pendingJobKey.getAllValues())
            .containsOnly(runningJobKey.getAllValues().get(0));
        verify(asyncJobService, times(2)).submitOrReuseSuccessful(
            eq("KNOWLEDGE_INDEX_BOOK"),
            eq(runningJobKey.getAllValues().get(0)),
            eq("book:401"),
            eq("{\"bookId\":401,\"mode\":\"ALL\"}"),
            eq(7L),
            eq(300L)
        );
        verify(asyncJobService, times(2)).submitOrReuseSuccessfulPending(
            eq("KNOWLEDGE_INDEX_BOOK"),
            eq(runningJobKey.getAllValues().get(0)),
            eq("book:401"),
            eq("{\"bookId\":401,\"mode\":\"ALL\"}"),
            eq(7L),
            eq(300L)
        );
    }
}
