package com.novelanalyzer.modules.knowledge.controller;

import com.novelanalyzer.modules.crawler.dto.CrawlerBookSearchRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.BookSearchCandidateVO;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.security.service.InternalServiceAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeInternalController {

    private final InternalServiceAuthService internalServiceAuthService;
    private final CrawlerService crawlerService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public KnowledgeInternalController(InternalServiceAuthService internalServiceAuthService,
                                       CrawlerService crawlerService,
                                       KnowledgeRetrievalService knowledgeRetrievalService) {
        this.internalServiceAuthService = internalServiceAuthService;
        this.crawlerService = crawlerService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @PostMapping("/books/search")
    public List<BookSearchCandidateVO> searchBooks(@Valid @RequestBody CrawlerBookSearchRequest request,
                                                   HttpServletRequest httpServletRequest) {
        internalServiceAuthService.assertLangGraphWorkerCaller(httpServletRequest);
        return crawlerService.searchBooks(request);
    }

    @PostMapping("/search")
    public List<KnowledgeSearchResultVO> searchKnowledge(@Valid @RequestBody KnowledgeSearchRequest request,
                                                         HttpServletRequest httpServletRequest) {
        internalServiceAuthService.assertLangGraphWorkerCaller(httpServletRequest);
        return knowledgeRetrievalService.search(request);
    }
}
