package com.novelanalyzer.modules.knowledge.controller;

import com.novelanalyzer.modules.crawler.dto.CrawlerBookSearchRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.BookSearchCandidateVO;
import com.novelanalyzer.modules.knowledge.dto.BookResearchPackRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectMemoryRequest;
import com.novelanalyzer.modules.knowledge.dto.RankLookupRequest;
import com.novelanalyzer.modules.knowledge.dto.RankResearchPackRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRankToolService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeResearchPackService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import com.novelanalyzer.modules.knowledge.vo.BookResearchPackVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectMemoryVO;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import com.novelanalyzer.modules.knowledge.vo.RankResearchPackVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeInternalController {

    private final CrawlerService crawlerService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeRankToolService knowledgeRankToolService;
    private final KnowledgeResearchPackService knowledgeResearchPackService;
    private final KnowledgeProjectMemoryService knowledgeProjectMemoryService;

    public KnowledgeInternalController(CrawlerService crawlerService,
                                       KnowledgeRetrievalService knowledgeRetrievalService,
                                       KnowledgeRankToolService knowledgeRankToolService,
                                       KnowledgeResearchPackService knowledgeResearchPackService,
                                       KnowledgeProjectMemoryService knowledgeProjectMemoryService) {
        this.crawlerService = crawlerService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeRankToolService = knowledgeRankToolService;
        this.knowledgeResearchPackService = knowledgeResearchPackService;
        this.knowledgeProjectMemoryService = knowledgeProjectMemoryService;
    }

    @PostMapping("/books/search")
    public List<BookSearchCandidateVO> searchBooks(@Valid @RequestBody CrawlerBookSearchRequest request) {
        return crawlerService.searchBooks(request);
    }

    @PostMapping("/search")
    public List<KnowledgeSearchResultVO> searchKnowledge(@Valid @RequestBody KnowledgeSearchRequest request) {
        return knowledgeRetrievalService.search(request);
    }

    @PostMapping("/rank/lookup")
    public List<RankLookupResultVO> lookupRank(@Valid @RequestBody RankLookupRequest request) {
        return knowledgeRankToolService.lookupRank(request);
    }

    @PostMapping("/research-pack/book")
    public BookResearchPackVO buildBookResearchPack(@Valid @RequestBody BookResearchPackRequest request) {
        return knowledgeResearchPackService.buildBookPack(request);
    }

    @PostMapping("/research-pack/rank")
    public RankResearchPackVO buildRankResearchPack(@Valid @RequestBody RankResearchPackRequest request) {
        return knowledgeResearchPackService.buildRankPack(request);
    }

    @PostMapping("/projects/{projectId}/memory")
    public KnowledgeProjectMemoryVO projectMemory(@PathVariable Long projectId,
                                                  @Valid @RequestBody ProjectMemoryRequest request) {
        if (request.getMemories() == null || request.getMemories().isEmpty()) {
            return knowledgeProjectMemoryService.read(projectId, request.getUserId());
        }
        return knowledgeProjectMemoryService.upsert(
            projectId,
            request.getUserId(),
            request.getMemories(),
            request.getSourceTraceId()
        );
    }
}
