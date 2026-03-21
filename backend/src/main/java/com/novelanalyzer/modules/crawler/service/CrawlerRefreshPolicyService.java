package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CrawlerRefreshPolicyService {

    private static final int DEFAULT_RANK_REFRESH_DAYS = 5;
    private static final int DEFAULT_RANK_FORCE_COOLDOWN_DAYS = 2;
    private static final int DEFAULT_RANK_FORCE_MAX_TIMES = 2;
    private static final int DEFAULT_BOOK_REFRESH_DAYS = 7;

    private final SystemConfigService systemConfigService;

    public CrawlerRefreshPolicyService(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    public String normalizeRankRefreshMode(String refreshMode) {
        if (refreshMode == null || refreshMode.isBlank()) {
            return CrawlerRankRequest.REFRESH_MODE_AUTO;
        }
        return CrawlerRankRequest.REFRESH_MODE_FORCE.equalsIgnoreCase(refreshMode)
            ? CrawlerRankRequest.REFRESH_MODE_FORCE
            : CrawlerRankRequest.REFRESH_MODE_AUTO;
    }

    public boolean shouldReuseRankSnapshot(LocalDateTime latestSnapshotTime) {
        if (latestSnapshotTime == null) {
            return false;
        }
        return latestSnapshotTime.isAfter(LocalDateTime.now().minusDays(getRankRefreshDays()));
    }

    public boolean allowForceRefresh(int recentForceCount) {
        return recentForceCount < getRankForceMaxTimes();
    }

    public LocalDateTime forceRefreshWindowStart() {
        return LocalDateTime.now().minusDays(getRankForceCooldownDays());
    }

    public boolean shouldReuseBookDetail(LocalDateTime lastCrawlTime) {
        if (lastCrawlTime == null) {
            return false;
        }
        return lastCrawlTime.isAfter(LocalDateTime.now().minusDays(getBookRefreshDays()));
    }

    private int getRankRefreshDays() {
        return systemConfigService.getIntValueOrDefault("crawler.rank.refresh-days", DEFAULT_RANK_REFRESH_DAYS);
    }

    private int getRankForceCooldownDays() {
        return systemConfigService.getIntValueOrDefault("crawler.rank.force-cooldown-days", DEFAULT_RANK_FORCE_COOLDOWN_DAYS);
    }

    private int getRankForceMaxTimes() {
        return systemConfigService.getIntValueOrDefault("crawler.rank.force-max-times", DEFAULT_RANK_FORCE_MAX_TIMES);
    }

    private int getBookRefreshDays() {
        return systemConfigService.getIntValueOrDefault("crawler.book.refresh-days", DEFAULT_BOOK_REFRESH_DAYS);
    }
}
