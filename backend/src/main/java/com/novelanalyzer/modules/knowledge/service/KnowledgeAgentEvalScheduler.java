package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@EnableScheduling
public class KnowledgeAgentEvalScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeAgentEvalScheduler.class);

    private final KnowledgeAgentEvalService knowledgeAgentEvalService;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeAgentEvalScheduler(KnowledgeAgentEvalService knowledgeAgentEvalService,
                                       KnowledgeProperties knowledgeProperties) {
        this.knowledgeAgentEvalService = knowledgeAgentEvalService;
        this.knowledgeProperties = knowledgeProperties;
    }

    @Scheduled(cron = "${app.knowledge.eval.stale-recovery-cron:0 */5 * * * ?}")
    public void recoverStaleEvalRuns() {
        KnowledgeProperties.Eval eval = knowledgeProperties.getEval();
        if (eval == null || !eval.isQueueEnabled()) {
            return;
        }
        long staleAfterSeconds = Math.max(60, eval.getVisibilityTimeoutSeconds());
        try {
            int recovered = knowledgeAgentEvalService.recoverStaleRuns(Duration.ofSeconds(staleAfterSeconds));
            if (recovered > 0) {
                LOGGER.info("knowledge eval stale run recovery completed: recovered={}", recovered);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge eval stale run recovery failed: {}", ex.getMessage());
        }
    }
}
