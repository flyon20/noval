package com.novelanalyzer.modules.system.service;

import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardOptionVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardStatusVO;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginBootstrapServiceTest {

    @Test
    void shouldReadPersistedSnapshotsWithoutStartingRankRefresh() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        RankBoardOptionVO board = new RankBoardOptionVO();
        board.setBoardCode("urban-brain");
        RankBoardCatalogVO catalog = new RankBoardCatalogVO();
        catalog.setChannelCode("male-new");
        catalog.setBoards(List.of(board));
        RankBoardStatusVO status = new RankBoardStatusVO();
        status.setSnapshotId(10L);
        status.setTotal(30);
        when(crawlerService.getPersistedBoardCatalog("fanqie")).thenReturn(List.of(catalog));
        when(crawlerService.getRankStatus("fanqie", "male-new", "urban-brain")).thenReturn(status);
        LoginBootstrapService service = new LoginBootstrapService(crawlerService);

        var result = service.bootstrap("fanqie");

        assertThat(result.getResults()).hasSize(1);
        assertThat(result.getResults().get(0).getSnapshotId()).isEqualTo(10L);
        verify(crawlerService, never()).refreshRankBoard(org.mockito.ArgumentMatchers.any());
    }
}
