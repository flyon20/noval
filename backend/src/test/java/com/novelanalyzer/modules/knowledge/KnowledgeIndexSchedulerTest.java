package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KnowledgeIndexSchedulerTest {

    @Test
    void shouldSubmitRankIncrementalOnlyWhenScheduled() {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setRankIncrementalEnabled(true);
        properties.getIndex().setRankIncrementalLimit(120);
        KnowledgeIndexScheduler scheduler = new KnowledgeIndexScheduler(executor, properties);

        scheduler.submitRankIncrementalRebuild();

        verify(executor).submitRebuild("RANK_INCREMENTAL", 120, null);
        verify(executor, never()).submitRebuild("FULL_REINDEX", 120, null);
        verify(executor, never()).submitRebuild("CHAPTER_MISSING", 120, null);
    }

    @Test
    void shouldNotSubmitChapterMissingByDefault() {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        KnowledgeIndexScheduler scheduler = new KnowledgeIndexScheduler(executor, properties);

        scheduler.submitChapterMissingRebuild();

        verify(executor, never()).submitRebuild("CHAPTER_MISSING", properties.getIndex().getChapterMissingLimit(), null);
    }

    @Test
    void shouldNotSubmitChapterMissingWhenExplicitlyDisabled() {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setChapterMissingEnabled(false);
        KnowledgeIndexScheduler scheduler = new KnowledgeIndexScheduler(executor, properties);

        scheduler.submitChapterMissingRebuild();

        verify(executor, never()).submitRebuild("CHAPTER_MISSING", properties.getIndex().getChapterMissingLimit(), null);
    }

    @Test
    void shouldSubmitChapterMissingWithConfiguredLimit() {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setChapterMissingEnabled(true);
        properties.getIndex().setChapterMissingLimit(80);
        KnowledgeIndexScheduler scheduler = new KnowledgeIndexScheduler(executor, properties);

        scheduler.submitChapterMissingRebuild();

        verify(executor).submitRebuild("CHAPTER_MISSING", 80, null);
    }
}
