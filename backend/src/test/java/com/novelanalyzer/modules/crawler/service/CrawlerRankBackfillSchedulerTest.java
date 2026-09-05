package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.config.CrawlerProperties;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlerRankBackfillSchedulerTest {

    @Test
    void shouldBackfillOnlyBoardsOlderThanConfiguredRefreshDays() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerRepository crawlerRepository = mock(CrawlerRepository.class);
        CrawlerProperties properties = new CrawlerProperties();
        properties.getRankBackfill().setEnabled(true);
        properties.getRankBackfill().setRefreshDays(7);
        properties.getRankBackfill().setBatchSize(20);
        properties.getRankBackfill().setRankFetchCount(50);

        RankBoardEntity stale = board(1L, "male-new", "urban-brain");
        when(crawlerRepository.findBoardsMissingSnapshotBefore("fanqie", 7, 20)).thenReturn(List.of(stale));

        CrawlerRankBackfillScheduler scheduler = new CrawlerRankBackfillScheduler(crawlerService, crawlerRepository, properties);

        scheduler.backfillStaleRankBoards();

        verify(crawlerRepository).findBoardsMissingSnapshotBefore("fanqie", 7, 20);
        verify(crawlerService).refreshRankBoard(argThat(request ->
            "fanqie".equals(request.getPlatform())
                && "male-new".equals(request.getChannelCode())
                && "urban-brain".equals(request.getBoardCode())
                && CrawlerRankRequest.REFRESH_MODE_AUTO.equals(request.getRefreshMode())
                && Integer.valueOf(50).equals(request.getRankFetchCount())
                && request.getIdempotencyKey() != null
                && !request.getIdempotencyKey().isBlank()
        ));
    }

    @Test
    void shouldGenerateStableBackfillIdempotencyKeyForTheSameDailyRequestScope() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerRepository crawlerRepository = mock(CrawlerRepository.class);
        CrawlerProperties properties = new CrawlerProperties();
        RankBoardEntity stale = board(1L, "male-new", "urban-brain");
        when(crawlerRepository.findBoardsMissingSnapshotBefore("fanqie", 3, 20)).thenReturn(List.of(stale));
        CrawlerRankBackfillScheduler scheduler = new CrawlerRankBackfillScheduler(
            crawlerService,
            crawlerRepository,
            properties
        );

        scheduler.backfillStaleRankBoards();
        scheduler.backfillStaleRankBoards();

        ArgumentCaptor<CrawlerRankRequest> captor = ArgumentCaptor.forClass(CrawlerRankRequest.class);
        verify(crawlerService, org.mockito.Mockito.times(2)).refreshRankBoard(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(CrawlerRankRequest::getIdempotencyKey)
            .doesNotContainNull()
            .doesNotContain("")
            .hasSize(2);
        assertThat(captor.getAllValues().get(0).getIdempotencyKey())
            .isEqualTo(captor.getAllValues().get(1).getIdempotencyKey());
    }

    @Test
    void shouldSyncBoardCatalogMonthlyWhenEnabled() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerRepository crawlerRepository = mock(CrawlerRepository.class);
        CrawlerProperties properties = new CrawlerProperties();
        properties.getRankCatalogSync().setEnabled(true);

        CrawlerRankBackfillScheduler scheduler = new CrawlerRankBackfillScheduler(crawlerService, crawlerRepository, properties);

        scheduler.syncRankCatalog();

        verify(crawlerService).syncRankBoardCatalog("fanqie");
    }

    @Test
    void shouldSkipRankBackfillWhenDisabled() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerRepository crawlerRepository = mock(CrawlerRepository.class);
        CrawlerProperties properties = new CrawlerProperties();
        properties.getRankBackfill().setEnabled(false);

        CrawlerRankBackfillScheduler scheduler = new CrawlerRankBackfillScheduler(crawlerService, crawlerRepository, properties);

        scheduler.backfillStaleRankBoards();

        verify(crawlerRepository, never()).findBoardsMissingSnapshotBefore("fanqie", 3, 20);
    }

    private RankBoardEntity board(Long id, String channelCode, String boardCode) {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(id);
        board.setPlatform("fanqie");
        board.setChannelCode(channelCode);
        board.setBoardCode(boardCode);
        board.setBoardName(boardCode);
        return board;
    }
}
