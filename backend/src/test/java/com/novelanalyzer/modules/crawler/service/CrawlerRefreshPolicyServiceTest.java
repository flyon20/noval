package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrawlerRefreshPolicyServiceTest {

    @Test
    void shouldClassifyFreshStaleAndExpiredUsingUtcHours() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getIntValueOrDefault(anyString(), anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        CrawlerRefreshPolicyService service = new CrawlerRefreshPolicyService(systemConfigService, null, clock);

        LocalDateTime fresh = LocalDateTime.ofInstant(now.minusSeconds(71 * 3600), ZoneOffset.UTC);
        LocalDateTime stale = LocalDateTime.ofInstant(now.minusSeconds(100 * 3600), ZoneOffset.UTC);
        LocalDateTime expired = LocalDateTime.ofInstant(now.minusSeconds(200 * 3600), ZoneOffset.UTC);

        assertThat(service.evaluateRankSnapshot(fresh).isFresh()).isTrue();
        assertThat(service.evaluateRankSnapshot(fresh).historicalReference()).isFalse();
        assertThat(service.evaluateRankSnapshot(stale).isStale()).isTrue();
        assertThat(service.evaluateRankSnapshot(stale).refreshRecommended()).isTrue();
        assertThat(service.evaluateRankSnapshot(expired).isExpired()).isTrue();
        assertThat(service.evaluateRankSnapshot(expired).historicalReference()).isTrue();
        assertThat(service.shouldReuseRankSnapshot(fresh)).isTrue();
        assertThat(service.shouldReuseRankSnapshot(stale)).isFalse();
        assertThat(service.shouldReuseRankSnapshot(expired)).isFalse();
    }

    @Test
    void shouldReuseSnapshotsWithinThreeDaysAndTreatFourDaySnapshotsAsStaleByDefault() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getIntValueOrDefault(anyString(), anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        CrawlerRefreshPolicyService service = new CrawlerRefreshPolicyService(systemConfigService, null, clock);
        LocalDateTime withinThreeDays = LocalDateTime.ofInstant(now.minusSeconds(71 * 3600), ZoneOffset.UTC);
        LocalDateTime fourDays = LocalDateTime.ofInstant(now.minusSeconds(96 * 3600), ZoneOffset.UTC);

        assertThat(service.shouldReuseRankSnapshot(withinThreeDays)).isTrue();
        assertThat(service.shouldReuseRankSnapshot(fourDays)).isFalse();
        assertThat(service.evaluateRankSnapshot(fourDays).isStale()).isTrue();
    }

    @Test
    void shouldSuppressOnlyAutomaticRefreshWhenResourcePolicyReportsPressure() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        AgentResourcePressureService pressureService = mock(AgentResourcePressureService.class);
        when(pressureService.shouldSuppressLowPriorityWork()).thenReturn(true);
        CrawlerRefreshPolicyService service = new CrawlerRefreshPolicyService(
            systemConfigService,
            pressureService
        );

        assertThat(service.shouldSuppressAutomaticRefresh()).isTrue();
        assertThat(service.normalizeRankRefreshMode("FORCE")).isEqualTo("FORCE");
    }
}
