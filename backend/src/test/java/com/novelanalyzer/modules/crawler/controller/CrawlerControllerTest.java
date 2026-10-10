package com.novelanalyzer.modules.crawler.controller;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CrawlerControllerTest {

    @Test
    void shouldRejectRankRefreshWithoutIdempotencyKeyAtHttpBoundary() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerController controller = new CrawlerController(crawlerService);
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);

        assertThatThrownBy(() -> controller.refresh(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("idempotencyKey");
        verifyNoInteractions(crawlerService);
    }

    @Test
    void shouldReturnConflictWhenSameRankRefreshRequestIsAlreadyInProgress() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerController controller = new CrawlerController(crawlerService);
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
        request.setIdempotencyKey("same-key");
        when(crawlerService.refreshRankBoard(request))
            .thenThrow(new CrawlerService.RankRefreshInProgressException());

        ResponseEntity<?> entity = controller.refresh(request).join();
        assertThat(entity.getStatusCode().value()).isEqualTo(409);
        assertThat(entity.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(entity.getBody()).isInstanceOf(Result.class);
        Result<?> body = (Result<?>) entity.getBody();
        assertThat(body.getCode()).isEqualTo(409);
        assertThat(body.getMessage()).isEqualTo(CrawlerService.RANK_REFRESH_IN_PROGRESS);
        assertThat(body.getData()).isNull();
    }

    @Test
    void shouldReturnConflictAndRetryAfterFromLegacyRankEndpoint() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerController controller = new CrawlerController(crawlerService);
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-new:262");
        when(crawlerService.getRank(request))
            .thenThrow(new CrawlerService.RankRefreshInProgressException());

        ResponseEntity<?> entity = controller.rank(request).join();

        assertThat(entity.getStatusCode().value()).isEqualTo(409);
        assertThat(entity.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(entity.getBody()).isInstanceOf(Result.class);
        Result<?> body = (Result<?>) entity.getBody();
        assertThat(body.getCode()).isEqualTo(409);
        assertThat(body.getMessage()).isEqualTo(CrawlerService.RANK_REFRESH_IN_PROGRESS);
        assertThat(body.getData()).isNull();
    }

    @Test
    void shouldKeepSuccessfulRankRefreshResponseContract() {
        CrawlerService crawlerService = mock(CrawlerService.class);
        CrawlerController controller = new CrawlerController(crawlerService);
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
        request.setIdempotencyKey("completed-key");
        RankRefreshResultVO result = new RankRefreshResultVO();
        result.setSnapshotId(10L);
        result.setReused(Boolean.TRUE);
        when(crawlerService.refreshRankBoard(request)).thenReturn(result);

        ResponseEntity<Result<RankRefreshResultVO>> response = controller.refresh(request).join();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(200);
        assertThat(response.getBody().getData()).isSameAs(result);
    }
}
