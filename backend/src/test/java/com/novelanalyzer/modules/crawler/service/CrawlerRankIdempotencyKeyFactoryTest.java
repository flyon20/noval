package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlerRankIdempotencyKeyFactoryTest {

    @Test
    void shouldGenerateStableKeysBoundToBoundaryBoardModeAndRequestScope() {
        CrawlerRankRequest request = request("urban-brain", CrawlerRankRequest.REFRESH_MODE_AUTO);

        String first = CrawlerRankIdempotencyKeyFactory.generate("rank-backfill", request, "2026-07-16");
        String repeated = CrawlerRankIdempotencyKeyFactory.generate("rank-backfill", request, "2026-07-16");

        assertThat(repeated).isEqualTo(first);
        assertThat(CrawlerRankIdempotencyKeyFactory.generate(
            "rank-backfill", request("urban-power", CrawlerRankRequest.REFRESH_MODE_AUTO), "2026-07-16"
        )).isNotEqualTo(first);
        assertThat(CrawlerRankIdempotencyKeyFactory.generate(
            "rank-backfill", request("urban-brain", CrawlerRankRequest.REFRESH_MODE_FORCE), "2026-07-16"
        )).isNotEqualTo(first);
        assertThat(CrawlerRankIdempotencyKeyFactory.generate(
            "rank-backfill", request, "2026-07-17"
        )).isNotEqualTo(first);
    }

    private CrawlerRankRequest request(String boardCode, String refreshMode) {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode(boardCode);
        request.setRefreshMode(refreshMode);
        return request;
    }
}
