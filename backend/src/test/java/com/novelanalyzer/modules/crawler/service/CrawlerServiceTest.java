package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.modules.asyncjob.service.AsyncJobLockService;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.vo.RankPageVO;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawlerServiceTest {

    @Mock
    private PythonCrawlerClient pythonCrawlerClient;

    @Mock
    private CrawlerRepository crawlerRepository;

    @Mock
    private CrawlerCacheService crawlerCacheService;

    @Mock
    private CrawlerRefreshPolicyService crawlerRefreshPolicyService;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private AsyncJobLockService asyncJobLockService;

    @InjectMocks
    private CrawlerService crawlerService;

    @Test
    void shouldClampRankPageSizeToMaxWhenRequestIsTooLarge() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.of(2026, 5, 10, 14, 0));
        snapshot.setRecordCount(1);

        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Book 01");
        rank.setAuthor("Author 01");
        rank.setIntro("Intro 01");
        rank.setBookUrl("https://fanqienovel.com/page/1");
        rank.setPlatform("fanqie");
        rank.setCategory("urban-brain");

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(snapshot));
        when(crawlerRepository.findRankPageBySnapshot(10L, 0, 100)).thenReturn(List.of(rank));

        RankPageVO vo = crawlerService.getRankPage("fanqie", "male-new", "urban-brain", 1, 999);

        assertThat(vo.getPage()).isEqualTo(1);
        assertThat(vo.getPageSize()).isEqualTo(100);
        assertThat(vo.getTotal()).isEqualTo(1);
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getBookName()).isEqualTo("Book 01");
        verify(crawlerRepository).findRankPageBySnapshot(10L, 0, 100);
    }
}
