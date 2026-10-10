package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CrawlerChapterPersistenceServiceTest {

    @Mock
    private CrawlerRepository crawlerRepository;

    @InjectMocks
    private CrawlerChapterPersistenceService persistenceService;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldNotWriteAnyChapterWithoutLeaseOwnership() {
        assertThatThrownBy(() -> persistenceService.persistFetchedChapters(
            "fanqie",
            42L,
            List.of(chapter(1)),
            () -> false
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("chapter fetch lock ownership lost");

        verify(crawlerRepository, never()).saveOrUpdateChapter(
            anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()
        );
    }

    @Test
    void shouldRecheckLeaseOwnershipBeforeTransactionCommit() {
        AtomicBoolean healthy = new AtomicBoolean(true);
        TransactionSynchronizationManager.initSynchronization();

        persistenceService.persistFetchedChapters(
            "fanqie",
            42L,
            List.of(chapter(1)),
            healthy::get
        );
        verify(crawlerRepository).saveOrUpdateChapter(
            "fanqie",
            42L,
            1,
            "第1章",
            "chapter-content-1",
            17
        );

        healthy.set(false);
        TransactionSynchronization synchronization = TransactionSynchronizationManager
            .getSynchronizations()
            .get(0);
        assertThatThrownBy(() -> synchronization.beforeCommit(false))
            .isInstanceOf(BusinessException.class)
            .hasMessage("chapter fetch lock ownership lost");
    }

    private ExternalChapterItem chapter(int chapterNo) {
        ExternalChapterItem chapter = new ExternalChapterItem();
        chapter.setChapterNo(chapterNo);
        chapter.setChapterTitle("第" + chapterNo + "章");
        chapter.setContent("chapter-content-" + chapterNo);
        chapter.setSourceWordCount(chapter.getContent().length());
        return chapter;
    }
}
