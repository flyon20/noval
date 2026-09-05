package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlerFetchGuardTest {

    @Test
    void shouldAllowOnlyOneActiveChapterFetchPerOrdinaryUser() {
        CrawlerFetchGuard guard = new CrawlerFetchGuard(2);
        AuthUser user = AuthUser.of(7L, "writer", Set.of("USER"));

        try (CrawlerFetchGuard.Lease ignored = guard.acquireChapter(user)) {
            assertThatThrownBy(() -> guard.acquireChapter(user))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CrawlerFetchGuard.CHAPTER_USER_FETCH_IN_PROGRESS);
        }

        CrawlerFetchGuard.Lease reusableLease = guard.acquireChapter(user);
        reusableLease.close();
    }

    @Test
    void shouldRejectCrawlerWorkBeyondConfiguredGlobalConcurrency() {
        CrawlerFetchGuard guard = new CrawlerFetchGuard(1);

        try (CrawlerFetchGuard.Lease ignored = guard.acquireRank()) {
            assertThatThrownBy(guard::acquireRank)
                .isInstanceOf(BusinessException.class)
                .hasMessage(CrawlerFetchGuard.CRAWLER_WORKER_BUSY);
        }
    }

    @Test
    void shouldReleaseOrdinaryUserReservationWhenGlobalCrawlerCapacityIsBusy() {
        CrawlerFetchGuard guard = new CrawlerFetchGuard(1);
        AuthUser user = AuthUser.of(7L, "writer", Set.of("USER"));

        try (CrawlerFetchGuard.Lease ignored = guard.acquireRank()) {
            assertThatThrownBy(() -> guard.acquireChapter(user))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CrawlerFetchGuard.CRAWLER_WORKER_BUSY);
        }

        CrawlerFetchGuard.Lease userLease = guard.acquireChapter(user);
        userLease.close();
    }

    @Test
    void shouldSkipPerUserRestrictionForAdministratorsButKeepGlobalLimit() {
        CrawlerFetchGuard guard = new CrawlerFetchGuard(2);
        AuthUser admin = AuthUser.of(1L, "admin", Set.of("ADMIN"));

        try (CrawlerFetchGuard.Lease first = guard.acquireChapter(admin);
             CrawlerFetchGuard.Lease second = guard.acquireChapter(admin)) {
            assertThatThrownBy(() -> guard.acquireChapter(admin))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CrawlerFetchGuard.CRAWLER_WORKER_BUSY);
        }
    }
}
