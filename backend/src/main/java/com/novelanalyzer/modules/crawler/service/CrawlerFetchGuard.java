package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CrawlerFetchGuard {

    static final String CHAPTER_USER_FETCH_IN_PROGRESS = "chapter fetch already in progress for current user";
    static final String CRAWLER_WORKER_BUSY = "crawler worker is busy";

    private final Semaphore permits;
    private final Set<Long> activeChapterUsers = ConcurrentHashMap.newKeySet();

    @Autowired
    public CrawlerFetchGuard(KnowledgeProperties properties) {
        this(properties.getResourcePolicy().getMaxCrawlerConcurrency());
    }

    CrawlerFetchGuard(int maxConcurrency) {
        this.permits = new Semaphore(Math.max(1, maxConcurrency), true);
    }

    public Lease acquireRank() {
        return acquire(null);
    }

    public Lease acquireChapter(AuthUser authUser) {
        Long guardedUserId = resolveGuardedUserId(authUser);
        if (guardedUserId != null && !activeChapterUsers.add(guardedUserId)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, CHAPTER_USER_FETCH_IN_PROGRESS);
        }
        return acquire(guardedUserId);
    }

    private Lease acquire(Long guardedUserId) {
        if (!permits.tryAcquire()) {
            releaseUser(guardedUserId);
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, CRAWLER_WORKER_BUSY);
        }
        return new Lease(permits, activeChapterUsers, guardedUserId);
    }

    private Long resolveGuardedUserId(AuthUser authUser) {
        if (authUser == null || authUser.getUserId() == null || authUser.hasAnyRole(Set.of("ADMIN"))) {
            return null;
        }
        return authUser.getUserId();
    }

    private void releaseUser(Long guardedUserId) {
        if (guardedUserId != null) {
            activeChapterUsers.remove(guardedUserId);
        }
    }

    public static final class Lease implements AutoCloseable {

        private final Semaphore permits;
        private final Set<Long> activeChapterUsers;
        private final Long guardedUserId;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(Semaphore permits, Set<Long> activeChapterUsers, Long guardedUserId) {
            this.permits = permits;
            this.activeChapterUsers = activeChapterUsers;
            this.guardedUserId = guardedUserId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            permits.release();
            if (guardedUserId != null) {
                activeChapterUsers.remove(guardedUserId);
            }
        }
    }
}
