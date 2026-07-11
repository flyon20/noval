package com.novelanalyzer.modules.knowledge.controller;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.dto.CrawlerBookSearchRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.BookSearchCandidateVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardOptionVO;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import com.novelanalyzer.modules.knowledge.dto.AiMemoryCandidateRequest;
import com.novelanalyzer.modules.knowledge.dto.AiMemoryReviewRequest;
import com.novelanalyzer.modules.knowledge.dto.AiMemorySearchRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentTelemetryRequest;
import com.novelanalyzer.modules.knowledge.dto.BookResearchPackRequest;
import com.novelanalyzer.modules.knowledge.dto.ConversationSummaryReadRequest;
import com.novelanalyzer.modules.knowledge.dto.ConversationSummaryUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectChapterSearchRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectEntityLookupRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectMemoryRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectResolveRequest;
import com.novelanalyzer.modules.knowledge.dto.RankLookupRequest;
import com.novelanalyzer.modules.knowledge.dto.RankResearchPackRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentGovernanceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationSummaryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRankToolService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeResearchPackService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeSkillGovernanceService;
import com.novelanalyzer.modules.knowledge.vo.AiMemoryVO;
import com.novelanalyzer.modules.knowledge.vo.AgentExpertProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import com.novelanalyzer.modules.knowledge.vo.BookResearchPackVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectMemoryVO;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import com.novelanalyzer.modules.knowledge.vo.RankResearchPackVO;
import com.novelanalyzer.modules.knowledge.vo.RuntimeSkillVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeInternalController {

    private final CrawlerService crawlerService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeRankToolService knowledgeRankToolService;
    private final KnowledgeResearchPackService knowledgeResearchPackService;
    private final KnowledgeProjectMemoryService knowledgeProjectMemoryService;
    private final KnowledgeProjectWorkService knowledgeProjectWorkService;
    private final KnowledgeMemoryService knowledgeMemoryService;
    private final KnowledgeConversationSummaryService conversationSummaryService;
    private final KnowledgeAgentGovernanceService knowledgeAgentGovernanceService;
    private final KnowledgeSkillGovernanceService knowledgeSkillGovernanceService;

    public KnowledgeInternalController(CrawlerService crawlerService,
                                       KnowledgeRetrievalService knowledgeRetrievalService,
                                       KnowledgeRankToolService knowledgeRankToolService,
                                       KnowledgeResearchPackService knowledgeResearchPackService,
                                       KnowledgeProjectMemoryService knowledgeProjectMemoryService,
                                       KnowledgeProjectWorkService knowledgeProjectWorkService,
                                       KnowledgeMemoryService knowledgeMemoryService,
                                       KnowledgeConversationSummaryService conversationSummaryService,
                                       KnowledgeAgentGovernanceService knowledgeAgentGovernanceService,
                                       KnowledgeSkillGovernanceService knowledgeSkillGovernanceService) {
        this.crawlerService = crawlerService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeRankToolService = knowledgeRankToolService;
        this.knowledgeResearchPackService = knowledgeResearchPackService;
        this.knowledgeProjectMemoryService = knowledgeProjectMemoryService;
        this.knowledgeProjectWorkService = knowledgeProjectWorkService;
        this.knowledgeMemoryService = knowledgeMemoryService;
        this.conversationSummaryService = conversationSummaryService;
        this.knowledgeAgentGovernanceService = knowledgeAgentGovernanceService;
        this.knowledgeSkillGovernanceService = knowledgeSkillGovernanceService;
    }

    @PostMapping("/books/search")
    public List<BookSearchCandidateVO> searchBooks(@Valid @RequestBody CrawlerBookSearchRequest request) {
        return crawlerService.searchBooks(request);
    }

    @PostMapping("/search")
    public List<KnowledgeSearchResultVO> searchKnowledge(@Valid @RequestBody KnowledgeSearchRequest request) {
        return knowledgeRetrievalService.search(request);
    }

    @GetMapping("/agent/runtime-config")
    public AgentRuntimeConfigVO agentRuntimeConfig() {
        return knowledgeAgentGovernanceService.runtimeConfig();
    }

    @GetMapping("/agent/experts")
    public List<AgentExpertProfileVO> agentExperts() {
        return knowledgeAgentGovernanceService.listExpertProfiles();
    }

    @GetMapping("/runtime-skills")
    public List<RuntimeSkillVO> runtimeSkills() {
        return knowledgeSkillGovernanceService.listRuntimeSkills();
    }

    @PostMapping("/agent/telemetry")
    public Map<String, Integer> ingestAgentTelemetry(@RequestBody AgentTelemetryRequest request) {
        return knowledgeAgentGovernanceService.ingestTelemetry(request);
    }

    @PostMapping("/rank/lookup")
    public List<RankLookupResultVO> lookupRank(@Valid @RequestBody RankLookupRequest request) {
        return knowledgeRankToolService.lookupRank(request);
    }

    @PostMapping("/rank/refresh")
    public RankRefreshResultVO refreshRank(@Valid @RequestBody CrawlerRankRequest request) {
        return crawlerService.refreshRankBoard(resolveRankRefreshRequest(request));
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

    @PostMapping("/projects/chapters/search")
    public List<ProjectChapterVO> searchProjectChapters(@Valid @RequestBody ProjectChapterSearchRequest request) {
        return knowledgeProjectWorkService.searchChapters(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getQuery(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/projects/chunks/search")
    public List<Map<String, Object>> searchProjectChunks(@Valid @RequestBody ProjectChapterSearchRequest request) {
        return knowledgeProjectWorkService.searchVectorChunks(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getQuery(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/projects/resolve")
    public Map<String, Object> resolveProjectWork(@Valid @RequestBody ProjectResolveRequest request) {
        return knowledgeProjectWorkService.resolveWork(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getQuery(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/projects/foreshadowings/list")
    public List<Map<String, Object>> listProjectForeshadowings(@Valid @RequestBody ProjectEntityLookupRequest request) {
        return knowledgeProjectWorkService.listForeshadowings(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getStatus(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/projects/timeline/lookup")
    public List<Map<String, Object>> lookupProjectTimeline(@Valid @RequestBody ProjectEntityLookupRequest request) {
        return knowledgeProjectWorkService.lookupTimeline(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getQuery(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/projects/character-states/lookup")
    public List<Map<String, Object>> lookupProjectCharacterStates(@Valid @RequestBody ProjectEntityLookupRequest request) {
        return knowledgeProjectWorkService.lookupCharacterStates(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getQuery(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/projects/world-rules/lookup")
    public List<Map<String, Object>> lookupProjectWorldRules(@Valid @RequestBody ProjectEntityLookupRequest request) {
        return knowledgeProjectWorkService.lookupWorldRules(
            request.getUserId(),
            request.getProjectId(),
            request.getWorkId(),
            request.getQuery(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/memory/candidates")
    public Map<String, Long> createMemoryCandidate(@Valid @RequestBody AiMemoryCandidateRequest request) {
        Long id = knowledgeMemoryService.createCandidate(
            request.getUserId(),
            request.getProjectId(),
            request.getConversationId(),
            request.getScope(),
            request.getMemoryType(),
            request.getContent(),
            request.getSummary(),
            request.getConfidence() == null ? 0.0d : request.getConfidence(),
            request.getSourceTraceId(),
            request.getTtlDays() == null ? 30 : request.getTtlDays()
        );
        return Map.of("id", id);
    }

    @PostMapping("/memory/candidates/{candidateId}/promote")
    public AiMemoryVO promoteMemoryCandidate(@PathVariable Long candidateId,
                                             @Valid @RequestBody AiMemoryReviewRequest request) {
        return knowledgeMemoryService.promoteCandidate(candidateId, request.getUserId());
    }

    @PostMapping("/memory/candidates/{candidateId}/reject")
    public Map<String, String> rejectMemoryCandidate(@PathVariable Long candidateId,
                                                     @Valid @RequestBody AiMemoryReviewRequest request) {
        knowledgeMemoryService.rejectCandidate(candidateId, request.getUserId());
        return Map.of("status", "rejected");
    }

    @PostMapping("/memory/candidates/expire")
    public Map<String, Integer> expireMemoryCandidates() {
        return Map.of("expired", knowledgeMemoryService.expireCandidates());
    }

    @PostMapping("/memory/search")
    public List<AiMemoryVO> searchMemory(@Valid @RequestBody AiMemorySearchRequest request) {
        return knowledgeMemoryService.searchConfirmedMemory(
            request.getUserId(),
            request.getProjectId(),
            request.getScope(),
            request.getLimit() == null ? 10 : request.getLimit()
        );
    }

    @PostMapping("/conversation-summary")
    public KnowledgeConversationSummaryService.ConversationSummary updateConversationSummary(
        @Valid @RequestBody ConversationSummaryUpdateRequest request
    ) {
        conversationSummaryService.updateSummary(
            request.getUserId(),
            request.getProjectId(),
            request.getConversationId(),
            request.getSummary(),
            request.getSourceTraceId()
        );
        return conversationSummaryService.readSummary(request.getUserId(), request.getConversationId()).orElse(null);
    }

    @PostMapping("/conversation-summary/read")
    public KnowledgeConversationSummaryService.ConversationSummary readConversationSummary(
        @Valid @RequestBody ConversationSummaryReadRequest request
    ) {
        return conversationSummaryService.readSummary(request.getUserId(), request.getConversationId()).orElse(null);
    }

    private CrawlerRankRequest resolveRankRefreshRequest(CrawlerRankRequest request) {
        CrawlerRankRequest resolved = copyRankRefreshRequest(request);
        if (resolved.hasBoardSelection()) {
            return resolved;
        }
        String category = trimToNull(request.getCategory());
        if (category == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "rank board or category is required");
        }
        String requestedChannelCode = trimToNull(request.getChannelCode());
        for (RankBoardCatalogVO channel : crawlerService.getBoardCatalog(request.getPlatform())) {
            if (requestedChannelCode != null && !requestedChannelCode.equals(channel.getChannelCode())) {
                continue;
            }
            for (RankBoardOptionVO board : channel.getBoards()) {
                if (!matchesCategory(board.getBoardName(), category)) {
                    continue;
                }
                resolved.setChannelCode(channel.getChannelCode());
                resolved.setBoardCode(board.getBoardCode());
                return resolved;
            }
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "rank board not found for category");
    }

    private CrawlerRankRequest copyRankRefreshRequest(CrawlerRankRequest source) {
        CrawlerRankRequest target = new CrawlerRankRequest();
        target.setPlatform(source.getPlatform());
        target.setCategory(source.getCategory());
        target.setChannelCode(source.getChannelCode());
        target.setBoardCode(source.getBoardCode());
        target.setRankFetchCount(source.getRankFetchCount());
        target.setRefreshMode(defaultIfBlank(source.getRefreshMode(), CrawlerRankRequest.REFRESH_MODE_AUTO));
        target.setForceReason(defaultIfBlank(source.getForceReason(), "agent_rank_cache_first_retry"));
        return target;
    }

    private boolean matchesCategory(String boardName, String category) {
        String normalizedBoardName = trimToNull(boardName);
        if (normalizedBoardName == null) {
            return false;
        }
        return normalizedBoardName.contains(category) || category.contains(normalizedBoardName);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
