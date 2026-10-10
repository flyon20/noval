package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class KnowledgeIndexScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexScheduler.class);

    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeIndexScheduler(KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                   KnowledgeProperties knowledgeProperties) {
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.knowledgeProperties = knowledgeProperties;
    }

    @Scheduled(cron = "${app.knowledge.index.rank-incremental-cron:0 20 3 * * ?}")
    public void submitRankIncrementalRebuild() {
        if (!knowledgeProperties.getIndex().isRankIncrementalEnabled()) {
            return;
        }
        int limit = Math.max(1, Math.min(knowledgeProperties.getIndex().getRankIncrementalLimit(), 500));
        try {
            knowledgeIndexJobExecutor.submitRebuild("RANK_INCREMENTAL", limit, null);
            LOGGER.info("knowledge rank incremental schedule submitted mode=RANK_INCREMENTAL limit={}", limit);
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge rank incremental schedule failed: {}", ex.getMessage());
        }
    }

    @Scheduled(cron = "${app.knowledge.index.chapter-missing-cron:0 50 3 * * ?}")
    public void submitChapterMissingRebuild() {
        if (!knowledgeProperties.getIndex().isChapterMissingEnabled()) {
            return;
        }
        int limit = Math.max(1, Math.min(knowledgeProperties.getIndex().getChapterMissingLimit(), 500));
        try {
            knowledgeIndexJobExecutor.submitRebuild("CHAPTER_MISSING", limit, null);
            LOGGER.info("knowledge chapter missing schedule submitted mode=CHAPTER_MISSING limit={}", limit);
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge chapter missing schedule failed: {}", ex.getMessage());
        }
    }

    @Scheduled(
        fixedDelayString = "${app.knowledge.index.recovery-interval-millis:30000}",
        initialDelayString = "${app.knowledge.index.recovery-initial-delay-millis:30000}"
    )
    public void recoverUnpublishedIndexJobs() {
        try {
            int recovered = knowledgeIndexJobExecutor.recoverQueuedJobs(20);
            if (recovered > 0) {
                LOGGER.info("knowledge index queue recovery republished jobs={}", recovered);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge index queue recovery failed: {}", ex.getMessage());
        }
    }
}
