package com.novelanalyzer.modules.knowledge.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.knowledge.document-batch", name = "queue-enabled", havingValue = "true")
public class KnowledgeProjectDocumentBatchScheduler {

    private final KnowledgeProjectDocumentBatchService batchService;

    public KnowledgeProjectDocumentBatchScheduler(KnowledgeProjectDocumentBatchService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(
        fixedDelayString = "${KNOWLEDGE_DOCUMENT_BATCH_OUTBOX_POLL_MS:5000}",
        initialDelayString = "${KNOWLEDGE_DOCUMENT_BATCH_OUTBOX_INITIAL_DELAY_MS:10000}"
    )
    public void poll() {
        batchService.dispatchPendingOutbox();
    }
}
