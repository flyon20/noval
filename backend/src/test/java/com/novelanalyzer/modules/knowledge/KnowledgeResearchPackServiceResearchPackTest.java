package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.dto.RankResearchPackRequest;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRankToolService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeResearchPackService;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeResearchPackServiceResearchPackTest {

    private final KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
    private final KnowledgeRankToolService rankToolService = mock(KnowledgeRankToolService.class);
    private final KnowledgeResearchPackService service = new KnowledgeResearchPackService(knowledgeRepository, rankToolService);

    @Test
    void buildRankPackDoesNotUsePerBookRepositoryQueriesForTopNRows() {
        when(rankToolService.lookupRank(any())).thenReturn(List.of(
            rankRow(101L, 1, "Top One"),
            rankRow(102L, 2, "Top Two")
        ));
        when(knowledgeRepository.findBooksByIds(any())).thenReturn(List.of());
        when(knowledgeRepository.findChaptersByBookIds(any(), anyInt())).thenReturn(List.of());
        when(knowledgeRepository.findLatestAnalysisResultsForBooks(anyLong(), any(), anyInt())).thenReturn(List.of());

        RankResearchPackRequest request = new RankResearchPackRequest();
        request.setUserId(7L);
        request.setPlatform("fanqie");
        request.setLimit(2);
        request.setChapterLimitPerBook(1);

        assertThat(service.buildRankPack(request).getBooks())
            .extracting("bookId")
            .containsExactly(101L, 102L);
        verify(knowledgeRepository).findBooksByIds(eq(List.of(101L, 102L)));
        verify(knowledgeRepository).findChaptersByBookIds(eq(List.of(101L, 102L)), eq(1));
        verify(knowledgeRepository).findLatestAnalysisResultsForBooks(eq(7L), eq(List.of(101L, 102L)), eq(1));
        verify(knowledgeRepository, never()).findBook(anyLong());
        verify(knowledgeRepository, never()).findChapters(anyLong(), anyInt());
        verify(knowledgeRepository, never()).findLatestAnalysisResultsForBook(anyLong(), anyLong(), anyInt());
    }

    @Test
    void buildRankPackRejectsMissingTrustedUserScope() {
        when(rankToolService.lookupRank(any())).thenReturn(List.of());
        RankResearchPackRequest request = new RankResearchPackRequest();
        request.setPlatform("fanqie");

        assertThatThrownBy(() -> service.buildRankPack(request))
            .hasMessageContaining("user scope required");
    }

    private RankLookupResultVO rankRow(Long bookId, Integer rankNo, String bookName) {
        RankLookupResultVO row = new RankLookupResultVO();
        row.setPlatform("fanqie");
        row.setBookId(bookId);
        row.setBookName(bookName);
        row.setAuthor("Author " + rankNo);
        row.setIntro("Intro " + rankNo);
        row.setRankNo(rankNo);
        row.setCategory("Urban");
        row.setSourceLabel("Board #" + rankNo);
        return row;
    }
}
