package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class CrawlerRefreshPolicyService {

    public static final String FRESHNESS_FRESH = "FRESH";
    public static final String FRESHNESS_STALE = "STALE";
    public static final String FRESHNESS_EXPIRED = "EXPIRED";
    public static final String FRESHNESS_MISSING = "MISSING";

    private static final int DEFAULT_RANK_FRESH_HOURS = 72;
    private static final int DEFAULT_RANK_EXPIRE_HOURS = 168;
    private static final int DEFAULT_RANK_REFRESH_DAYS = 3;
    private static final int DEFAULT_RANK_FORCE_COOLDOWN_DAYS = 2;
    private static final int DEFAULT_RANK_FORCE_MAX_TIMES = 2;
    private static final int DEFAULT_BOOK_REFRESH_DAYS = 7;
    private static final int DEFAULT_CHAPTER_FORCE_REFRESH_USER_MAX_TIMES = 3;
    private static final int MAX_CHAPTER_FORCE_REFRESH_TIMES = 20;

    private final SystemConfigService systemConfigService;
    private final AgentResourcePressureService resourcePressureService;
    private final Clock clock;

    @Autowired
    public CrawlerRefreshPolicyService(SystemConfigService systemConfigService,
                                       AgentResourcePressureService resourcePressureService) {
        this(systemConfigService, resourcePressureService, Clock.systemUTC());
    }

    public CrawlerRefreshPolicyService(SystemConfigService systemConfigService) {
        this(systemConfigService, null, Clock.systemUTC());
    }

    public CrawlerRefreshPolicyService(SystemConfigService systemConfigService,
                                       AgentResourcePressureService resourcePressureService,
                                       Clock clock) {
        this.systemConfigService = systemConfigService;
        this.resourcePressureService = resourcePressureService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public String normalizeRankRefreshMode(String refreshMode) {
        if (refreshMode == null || refreshMode.isBlank()) {
            return CrawlerRankRequest.REFRESH_MODE_AUTO;
        }
        return CrawlerRankRequest.REFRESH_MODE_FORCE.equalsIgnoreCase(refreshMode)
            ? CrawlerRankRequest.REFRESH_MODE_FORCE
            : CrawlerRankRequest.REFRESH_MODE_AUTO;
    }

    public RankSnapshotEvaluation evaluateRankSnapshot(LocalDateTime snapshotTime) {
        return evaluateRankSnapshot(snapshotTime, Instant.now(clock));
    }

    public RankSnapshotEvaluation evaluateRankSnapshot(LocalDateTime snapshotTime, Instant nowUtc) {
        Instant now = nowUtc == null ? Instant.now(clock) : nowUtc;
        if (snapshotTime == null) {
            return RankSnapshotEvaluation.missing(now);
        }
        Instant snapshotInstant = snapshotTime.atZone(ZoneOffset.UTC).toInstant();
        long ageHours = Math.max(0L, Duration.between(snapshotInstant, now).toHours());
        int freshHours = getRankFreshHours();
        int expireHours = getRankExpireHours();
        if (ageHours < freshHours) {
            return new RankSnapshotEvaluation(FRESHNESS_FRESH, ageHours, false, false, now, snapshotInstant);
        }
        if (ageHours < expireHours) {
            return new RankSnapshotEvaluation(FRESHNESS_STALE, ageHours, false, true, now, snapshotInstant);
        }
        return new RankSnapshotEvaluation(FRESHNESS_EXPIRED, ageHours, true, true, now, snapshotInstant);
    }

    public boolean shouldReuseRankSnapshot(LocalDateTime latestSnapshotTime) {
        return FRESHNESS_FRESH.equals(evaluateRankSnapshot(latestSnapshotTime).freshness());
    }

    public boolean shouldSuppressAutomaticRefresh() {
        return resourcePressureService != null
            && resourcePressureService.shouldSuppressLowPriorityWork();
    }

    public boolean allowForceRefresh(int recentForceCount) {
        return recentForceCount < getRankForceMaxTimes();
    }

    public LocalDateTime forceRefreshWindowStart() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).minusDays(getRankForceCooldownDays());
    }

    public boolean shouldReuseBookDetail(LocalDateTime lastCrawlTime) {
        if (lastCrawlTime == null) {
            return false;
        }
        Instant threshold = Instant.now(clock).minus(Duration.ofDays(getBookRefreshDays()));
        return lastCrawlTime.atZone(ZoneOffset.UTC).toInstant().isAfter(threshold);
    }

    public LocalDateTime chapterForceRefreshWindowStart() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).minusDays(getRankRefreshDays());
    }

    public int chapterForceRefreshWindowDays() {
        return getRankRefreshDays();
    }

    public int chapterForceRefreshUserMaxTimes() {
        int configured = systemConfigService.getIntValueOrDefault(
            "crawler.chapter.force-refresh.user-max-times",
            DEFAULT_CHAPTER_FORCE_REFRESH_USER_MAX_TIMES
        );
        return Math.min(Math.max(configured, 0), MAX_CHAPTER_FORCE_REFRESH_TIMES);
    }

    public int chapterForceRefreshAdminMaxTimes() {
        return MAX_CHAPTER_FORCE_REFRESH_TIMES;
    }

    public int getRankFreshHours() {
        int hours = systemConfigService.getIntValueOrDefault("crawler.rank.fresh-hours", DEFAULT_RANK_FRESH_HOURS);
        if (hours <= 0) {
            hours = getRankRefreshDays() * 24;
        }
        return Math.max(1, hours);
    }

    public int getRankExpireHours() {
        int hours = systemConfigService.getIntValueOrDefault("crawler.rank.expire-hours", DEFAULT_RANK_EXPIRE_HOURS);
        if (hours <= getRankFreshHours()) {
            hours = Math.max(getRankFreshHours() + 1, DEFAULT_RANK_EXPIRE_HOURS);
        }
        return hours;
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

    public record RankSnapshotEvaluation(
        String freshness,
        long ageHours,
        boolean historicalReference,
        boolean refreshRecommended,
        Instant evaluatedAt,
        Instant snapshotAt
    ) {
        public static RankSnapshotEvaluation missing(Instant evaluatedAt) {
            return new RankSnapshotEvaluation(FRESHNESS_MISSING, 0L, false, true, evaluatedAt, null);
        }

        public boolean isFresh() {
            return FRESHNESS_FRESH.equals(freshness);
        }

        public boolean isStale() {
            return FRESHNESS_STALE.equals(freshness);
        }

        public boolean isExpired() {
            return FRESHNESS_EXPIRED.equals(freshness);
        }

        public boolean isMissing() {
            return FRESHNESS_MISSING.equals(freshness);
        }
    }
}
