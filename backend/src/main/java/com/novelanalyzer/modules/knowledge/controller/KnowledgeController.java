package com.novelanalyzer.modules.knowledge.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeIndexRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeProjectRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateReviewRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentTraceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeHealthService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeSkillGovernanceService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeHealthVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidateVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequireRole({"ADMIN", "USER"})
public class KnowledgeController {

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeChatService knowledgeChatService;
    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;
    private final KnowledgeHealthService knowledgeHealthService;
    private final KnowledgeProjectService knowledgeProjectService;
    private final KnowledgeAgentTraceService knowledgeAgentTraceService;
    private final KnowledgeSkillGovernanceService knowledgeSkillGovernanceService;

    public KnowledgeController(KnowledgeRetrievalService knowledgeRetrievalService,
                               KnowledgeChatService knowledgeChatService,
                               KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                               KnowledgeHealthService knowledgeHealthService,
                               KnowledgeProjectService knowledgeProjectService,
                               KnowledgeAgentTraceService knowledgeAgentTraceService,
                               KnowledgeSkillGovernanceService knowledgeSkillGovernanceService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeChatService = knowledgeChatService;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.knowledgeHealthService = knowledgeHealthService;
        this.knowledgeProjectService = knowledgeProjectService;
        this.knowledgeAgentTraceService = knowledgeAgentTraceService;
        this.knowledgeSkillGovernanceService = knowledgeSkillGovernanceService;
    }

    @PostMapping("/search")
    public Result<List<KnowledgeSearchResultVO>> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return Result.success(knowledgeRetrievalService.search(request));
    }

    @GetMapping("/health")
    public Result<KnowledgeHealthVO> health() {
        return Result.success(knowledgeHealthService.health());
    }

    @PostMapping("/chat")
    public Result<KnowledgeChatResponseVO> chat(@Valid @RequestBody KnowledgeChatRequest request) {
        return Result.success(knowledgeChatService.chat(request));
    }

    @GetMapping("/projects")
    public Result<List<KnowledgeProjectVO>> listProjects() {
        return Result.success(knowledgeProjectService.listMine());
    }

    @PostMapping("/projects")
    public Result<KnowledgeProjectVO> createProject(@RequestBody KnowledgeProjectRequest request) {
        return Result.success(knowledgeProjectService.create(request));
    }

    @PutMapping("/projects/{projectId}")
    public Result<KnowledgeProjectVO> renameProject(@PathVariable Long projectId,
                                                    @RequestBody KnowledgeProjectRequest request) {
        return Result.success(knowledgeProjectService.rename(projectId, request));
    }

    @PostMapping("/projects/{projectId}/archive")
    public Result<Void> archiveProject(@PathVariable Long projectId) {
        knowledgeProjectService.archive(projectId);
        return Result.success(null);
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent-traces")
    public Result<List<KnowledgeAgentTraceVO>> listAgentTraces() {
        return Result.success(knowledgeAgentTraceService.listForAdmin());
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent-traces/{traceId}")
    public Result<KnowledgeAgentTraceVO> agentTraceDetail(@PathVariable Long traceId) {
        return Result.success(knowledgeAgentTraceService.detailForAdmin(traceId));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/skill-candidates")
    public Result<List<SkillCandidateVO>> listSkillCandidates() {
        return Result.success(knowledgeSkillGovernanceService.listCandidates());
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/skill-candidates/{candidateId}/review")
    public Result<SkillCandidateVO> reviewSkillCandidate(@PathVariable Long candidateId,
                                                         @RequestBody SkillCandidateReviewRequest request) {
        return Result.success(knowledgeSkillGovernanceService.review(candidateId, request));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody KnowledgeChatRequest request) {
        return knowledgeChatService.streamChat(request);
    }

    @PostMapping("/index")
    public Result<AsyncJobSubmitResponse> index(@Valid @RequestBody KnowledgeIndexRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return Result.success(knowledgeIndexJobExecutor.submitAndExecute(request.getBookId(), authUser.getUserId()));
    }

    @PostMapping("/rebuild")
    public Result<KnowledgeRebuildResponse> rebuild(@RequestBody KnowledgeRebuildRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        int limit = request.getLimit() == null ? 100 : request.getLimit();
        return Result.success(knowledgeIndexJobExecutor.submitRebuild(request.getMode(), limit, authUser.getUserId()));
    }
}
