package com.novelanalyzer.modules.crawler.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlChapterMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlRankMapper;
import com.novelanalyzer.modules.crawler.mapper.RankBoardMapper;
import com.novelanalyzer.modules.crawler.mapper.RankSnapshotMapper;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlerRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private CrawlRankMapper crawlRankMapper;
    private CrawlerRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:crawler_repository_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table crawl_rank (
                id bigint auto_increment primary key,
                platform varchar(20) not null,
                category varchar(100),
                crawl_time timestamp not null,
                deleted tinyint default 0
            )
            """);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "crawler-repository-test"),
            CrawlRankEntity.class
        );
        crawlRankMapper = mock(CrawlRankMapper.class);
        repository = new CrawlerRepository(
            mock(CrawlBookMapper.class),
            crawlRankMapper,
            mock(CrawlChapterMapper.class),
            mock(RankBoardMapper.class),
            mock(RankSnapshotMapper.class),
            jdbcTemplate
        );
    }

    @Test
    void findsOnlyRowsFromTheLatestMatchingSnapshot() {
        LocalDateTime older = LocalDateTime.of(2026, 8, 15, 10, 0);
        LocalDateTime latest = LocalDateTime.of(2026, 8, 16, 10, 0);
        jdbcTemplate.update(
            "insert into crawl_rank(platform, category, crawl_time, deleted) values(?, ?, ?, 0)",
            "platform-a", "category-a", older
        );
        jdbcTemplate.update(
            "insert into crawl_rank(platform, category, crawl_time, deleted) values(?, ?, ?, 0)",
            "platform-a", "category-a", latest
        );
        CrawlRankEntity row = new CrawlRankEntity();
        row.setCrawlTime(latest);
        when(crawlRankMapper.selectList(any())).thenReturn(List.of(row));

        assertThat(repository.findLatestRankSnapshot("platform-a", "category-a"))
            .containsExactly(row);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<CrawlRankEntity>> wrapperCaptor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(crawlRankMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment().toLowerCase())
            .contains("platform", "category", "deleted", "crawl_time", "order by", "rank_no");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values()).contains(latest);
    }

    @Test
    void skipsRankFetchWhenNoSnapshotExists() {
        assertThat(repository.findLatestRankSnapshot("platform-a", "category-a")).isEmpty();
        verify(crawlRankMapper, never()).selectList(any());
    }
}
