package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

@Service
public class CrawlerChapterPersistenceService {

    private final CrawlerRepository crawlerRepository;

    public CrawlerChapterPersistenceService(CrawlerRepository crawlerRepository) {
        this.crawlerRepository = crawlerRepository;
    }

    @Transactional
    public void persistFetchedChapters(String platform,
                                       Long bookId,
                                       List<ExternalChapterItem> chapters,
                                       BooleanSupplier ownershipHealthy) {
        registerFinalOwnershipCheck(ownershipHealthy);
        persistChapters(platform, bookId, chapters, ownershipHealthy);
    }

    @Transactional
    public void persistForcedChapters(Long userId,
                                      String username,
                                      String platform,
                                      Long bookId,
                                      Integer chapterCount,
                                      List<ExternalChapterItem> chapters,
                                      LocalDateTime startTime,
                                      BooleanSupplier ownershipHealthy) {
        registerFinalOwnershipCheck(ownershipHealthy);
        persistChapters(platform, bookId, chapters, ownershipHealthy);
        requireOwnership(ownershipHealthy);
        crawlerRepository.saveChapterRefreshTask(
            userId,
            username,
            platform,
            bookId,
            chapterCount,
            2,
            null,
            startTime,
            LocalDateTime.now()
        );
        requireOwnership(ownershipHealthy);
    }

    private void persistChapters(String platform,
                                 Long bookId,
                                 List<ExternalChapterItem> chapters,
                                 BooleanSupplier ownershipHealthy) {
        requireOwnership(ownershipHealthy);
        for (ExternalChapterItem chapter : chapters) {
            requireOwnership(ownershipHealthy);
            crawlerRepository.saveOrUpdateChapter(
                platform,
                bookId,
                chapter.getChapterNo(),
                chapter.getChapterTitle(),
                chapter.getContent(),
                chapter.getSourceWordCount()
            );
        }
        requireOwnership(ownershipHealthy);
    }

    private void registerFinalOwnershipCheck(BooleanSupplier ownershipHealthy) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                requireOwnership(ownershipHealthy);
            }
        });
    }

    private void requireOwnership(BooleanSupplier ownershipHealthy) {
        if (ownershipHealthy == null || !ownershipHealthy.getAsBoolean()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "chapter fetch lock ownership lost");
        }
    }
}
