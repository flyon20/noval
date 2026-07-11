package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentEvalScheduler;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentEvalService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KnowledgeAgentEvalSchedulerTest {

    @Test
    void shouldRecoverStaleEvalRunsUsingVisibilityTimeout() {
        KnowledgeAgentEvalService service = mock(KnowledgeAgentEvalService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getEval().setQueueEnabled(true);
        properties.getEval().setVisibilityTimeoutSeconds(900);
        KnowledgeAgentEvalScheduler scheduler = new KnowledgeAgentEvalScheduler(service, properties);

        scheduler.recoverStaleEvalRuns();

        verify(service).recoverStaleRuns(Duration.ofSeconds(900));
    }

    @Test
    void shouldSkipRecoveryWhenEvalQueueDisabled() {
        KnowledgeAgentEvalService service = mock(KnowledgeAgentEvalService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getEval().setQueueEnabled(false);
        KnowledgeAgentEvalScheduler scheduler = new KnowledgeAgentEvalScheduler(service, properties);

        scheduler.recoverStaleEvalRuns();

        verify(service, never()).recoverStaleRuns(Duration.ofSeconds(1800));
    }
}
