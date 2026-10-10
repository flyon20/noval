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
import com.novelanalyzer.modules.knowledge.dto.ProjectIngestSubmitRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectDocumentQuestionAnswerRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectExtractionReviewRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectKnowledgeFeedbackRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentEvalRunRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentExpertProfileUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentRuntimeConfigUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateCreateRequest;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateReviewRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentEvalService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentGovernanceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentTraceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatApplicationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunEventService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunEventStreamService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationMigrationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationReadService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeHealthService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectApplicationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectMemoryOverviewService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectFeedbackService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeSkillGovernanceService;
import com.novelanalyzer.modules.knowledge.vo.AiMemoryVO;
import com.novelanalyzer.modules.knowledge.vo.AgentCacheTokenStatsVO;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalCaseResultVO;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalRunVO;
import com.novelanalyzer.modules.knowledge.vo.AgentExpertProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import com.novelanalyzer.modules.knowledge.vo.GoldenCandidateDraftVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTracePageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunEventVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatMessageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeConversationVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeHealthVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentBatchVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentQuestionVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectExtractionCandidateVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectMemoryOverviewVO;
import com.novelanalyzer.modules.knowledge.vo.StoryGraphResultVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectKnowledgeFeedbackVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidateVO;
import com.novelanalyzer.modules.knowledge.vo.SkillGovernanceDashboardVO;
import com.novelanalyzer.modules.knowledge.vo.SkillShortcutVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
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
    private final KnowledgeProjectApplicationService knowledgeProjectApplicationService;
    private final KnowledgeProjectWorkService knowledgeProjectWorkService;
    private final KnowledgeProjectIngestService knowledgeProjectIngestService;
    private final KnowledgeStoryGraphService knowledgeStoryGraphService;
    private final KnowledgeProjectFeedbackService knowledgeProjectFeedbackService;
    private final KnowledgeAgentTraceService knowledgeAgentTraceService;
    private final KnowledgeSkillGovernanceService knowledgeSkillGovernanceService;
    private final KnowledgeMemoryService knowledgeMemoryService;
    private final KnowledgeAgentGovernanceService knowledgeAgentGovernanceService;
    private final KnowledgeAgentEvalService knowledgeAgentEvalService;
    private final KnowledgeChatRunService knowledgeChatRunService;
    private final KnowledgeChatRunEventService knowledgeChatRunEventService;
    private final KnowledgeChatRunEventStreamService knowledgeChatRunEventStreamService;
    private final KnowledgeConversationService knowledgeConversationService;
    private final KnowledgeConversationReadService knowledgeConversationReadService;
    private final KnowledgeConversationMigrationService knowledgeConversationMigrationService;
    private final KnowledgeChatApplicationService knowledgeChatApplicationService;
    private final KnowledgeProjectDocumentBatchService knowledgeProjectDocumentBatchService;
    private final KnowledgeProjectMemoryOverviewService knowledgeProjectMemoryOverviewService;

    public KnowledgeController(KnowledgeRetrievalService knowledgeRetrievalService,
                               KnowledgeChatService knowledgeChatService,
                               KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                               KnowledgeHealthService knowledgeHealthService,
                               KnowledgeProjectService knowledgeProjectService,
                               KnowledgeProjectApplicationService knowledgeProjectApplicationService,
                               KnowledgeProjectWorkService knowledgeProjectWorkService,
                               KnowledgeProjectIngestService knowledgeProjectIngestService,
                               KnowledgeStoryGraphService knowledgeStoryGraphService,
                               KnowledgeProjectFeedbackService knowledgeProjectFeedbackService,
                               KnowledgeAgentTraceService knowledgeAgentTraceService,
                               KnowledgeSkillGovernanceService knowledgeSkillGovernanceService,
                               KnowledgeMemoryService knowledgeMemoryService,
                               KnowledgeAgentGovernanceService knowledgeAgentGovernanceService,
                               KnowledgeAgentEvalService knowledgeAgentEvalService,
                               KnowledgeChatRunService knowledgeChatRunService,
                               KnowledgeChatRunEventService knowledgeChatRunEventService,
                               KnowledgeChatRunEventStreamService knowledgeChatRunEventStreamService,
                               KnowledgeConversationService knowledgeConversationService,
                               KnowledgeConversationReadService knowledgeConversationReadService,
                               KnowledgeConversationMigrationService knowledgeConversationMigrationService,
                               KnowledgeChatApplicationService knowledgeChatApplicationService,
                               KnowledgeProjectDocumentBatchService knowledgeProjectDocumentBatchService,
                               KnowledgeProjectMemoryOverviewService knowledgeProjectMemoryOverviewService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeChatService = knowledgeChatService;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.knowledgeHealthService = knowledgeHealthService;
        this.knowledgeProjectService = knowledgeProjectService;
        this.knowledgeProjectApplicationService = knowledgeProjectApplicationService;
        this.knowledgeProjectWorkService = knowledgeProjectWorkService;
        this.knowledgeProjectIngestService = knowledgeProjectIngestService;
        this.knowledgeStoryGraphService = knowledgeStoryGraphService;
        this.knowledgeProjectFeedbackService = knowledgeProjectFeedbackService;
        this.knowledgeAgentTraceService = knowledgeAgentTraceService;
        this.knowledgeSkillGovernanceService = knowledgeSkillGovernanceService;
        this.knowledgeMemoryService = knowledgeMemoryService;
        this.knowledgeAgentGovernanceService = knowledgeAgentGovernanceService;
        this.knowledgeAgentEvalService = knowledgeAgentEvalService;
        this.knowledgeChatRunService = knowledgeChatRunService;
        this.knowledgeChatRunEventService = knowledgeChatRunEventService;
        this.knowledgeChatRunEventStreamService = knowledgeChatRunEventStreamService;
        this.knowledgeConversationService = knowledgeConversationService;
        this.knowledgeConversationReadService = knowledgeConversationReadService;
        this.knowledgeConversationMigrationService = knowledgeConversationMigrationService;
        this.knowledgeChatApplicationService = knowledgeChatApplicationService;
        this.knowledgeProjectDocumentBatchService = knowledgeProjectDocumentBatchService;
        this.knowledgeProjectMemoryOverviewService = knowledgeProjectMemoryOverviewService;
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
        return Result.success(knowledgeChatApplicationService.chat(request));
    }

    @GetMapping("/skills/shortcuts")
    public Result<List<SkillShortcutVO>> skillShortcuts() {
        return Result.success(knowledgeSkillGovernanceService.listSkillShortcuts());
    }

    @PostMapping("/chat-runs")
    public Result<KnowledgeChatRunVO> startChatRun(@Valid @RequestBody KnowledgeChatRequest request) {
        return Result.success(knowledgeChatRunService.startRun(request));
    }

    @GetMapping("/chat-runs")
    public Result<List<KnowledgeChatRunVO>> chatRuns(@RequestParam(value = "projectId", required = false) Long projectId,
                                                     @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(knowledgeChatRunService.listRecentRuns(projectId, limit));
    }

    @GetMapping("/chat-runs/{runId}")
    public Result<KnowledgeChatRunVO> chatRun(@PathVariable String runId) {
        return Result.success(knowledgeChatRunService.getRun(runId));
    }

    @GetMapping("/chat-runs/{runId}/events")
    public Result<List<KnowledgeChatRunEventVO>> chatRunEvents(
        @PathVariable String runId,
        @RequestParam(value = "afterSequence", required = false) Long afterSequence,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.success(knowledgeChatRunEventService.listEvents(runId, afterSequence, limit));
    }

    @GetMapping(value = "/chat-runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatRunEvents(
        @PathVariable String runId,
        @RequestParam(value = "afterSequence", required = false) Long afterSequence,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        return knowledgeChatRunEventStreamService.stream(runId, afterSequence, lastEventId);
    }

    @GetMapping("/conversations/{conversationId}/runs")
    public Result<List<KnowledgeChatRunVO>> conversationRuns(@PathVariable String conversationId,
                                                             @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(knowledgeChatRunService.listConversationRuns(conversationId, limit));
    }

    @PostMapping("/conversations")
    public Result<KnowledgeConversationVO> createConversation(
        @RequestBody(required = false) KnowledgeConversationVO request
    ) {
        Long projectId = request == null ? null : request.getProjectId();
        String title = request == null ? null : request.getTitle();
        return Result.success(knowledgeConversationService.create(projectId, title));
    }

    @GetMapping("/conversations")
    public Result<List<KnowledgeConversationVO>> conversations(
        @RequestParam(value = "projectId", required = false) Long projectId
    ) {
        return Result.success(knowledgeConversationReadService.listMine(projectId));
    }

    @GetMapping("/conversations/{conversationId}")
    public Result<KnowledgeConversationVO> conversation(@PathVariable String conversationId,
                                                        @RequestParam(required = false) Long projectId) {
        return Result.success(knowledgeConversationReadService.get(conversationId, projectId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<KnowledgeChatMessageVO>> conversationMessages(@PathVariable String conversationId,
                                                                     @RequestParam(required = false) Long projectId) {
        return Result.success(knowledgeConversationReadService.listMessages(conversationId, projectId));
    }

    @PostMapping("/conversations/{conversationId}/archive")
    public Result<Void> archiveConversation(@PathVariable String conversationId) {
        knowledgeConversationService.archive(conversationId);
        return Result.success(null);
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/conversation-migration/backfill")
    public Result<KnowledgeConversationMigrationService.BackfillResult> backfillConversations(
        @RequestParam(value = "batchSize", defaultValue = "200") Integer batchSize
    ) {
        return Result.success(knowledgeConversationMigrationService.backfill(batchSize == null ? 200 : batchSize));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/conversation-migration/verify")
    public Result<KnowledgeConversationMigrationService.VerificationResult> verifyConversationBackfill() {
        return Result.success(knowledgeConversationMigrationService.verifyBackfill());
    }

    @PostMapping("/chat-runs/{runId}/cancel")
    public Result<KnowledgeChatRunVO> cancelChatRun(@PathVariable String runId) {
        return Result.success(knowledgeChatRunService.cancelRun(runId));
    }

    @GetMapping("/projects")
    public Result<List<KnowledgeProjectVO>> listProjects() {
        return Result.success(knowledgeProjectService.listMine());
    }

    @PostMapping("/projects")
    public Result<KnowledgeProjectVO> createProject(@RequestBody KnowledgeProjectRequest request) {
        return Result.success(knowledgeProjectApplicationService.create(request));
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

    @GetMapping("/projects/work-library")
    public Result<List<ProjectWorkVO>> listWorkLibrary() {
        return Result.success(knowledgeProjectWorkService.listMyWorkLibrary());
    }

    @GetMapping("/projects/{projectId}/works")
    public Result<List<ProjectWorkVO>> listWorks(@PathVariable Long projectId) {
        return Result.success(knowledgeProjectWorkService.listWorks(projectId));
    }

    @PostMapping("/projects/{projectId}/works")
    public Result<ProjectWorkVO> createWork(@PathVariable Long projectId,
                                            @RequestBody ProjectWorkRequest request) {
        return Result.success(knowledgeProjectWorkService.createWork(projectId, request));
    }

    @GetMapping("/projects/{projectId}/works/{workId}/chapters")
    public Result<List<ProjectChapterVO>> listChapters(@PathVariable Long projectId,
                                                       @PathVariable Long workId) {
        return Result.success(knowledgeProjectWorkService.listChapters(projectId, workId));
    }

    @PostMapping(value = "/projects/{projectId}/works/{workId}/document-batches",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ProjectDocumentBatchVO>> createDocumentBatch(
        @PathVariable Long projectId,
        @PathVariable Long workId,
        @RequestPart("files") List<MultipartFile> files,
        @RequestParam(value = "relativePaths", required = false) List<String> relativePaths,
        @RequestParam(value = "declaredKind", required = false) String declaredKind,
        @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey
    ) {
        ProjectDocumentBatchVO batch = knowledgeProjectDocumentBatchService.create(
            projectId, workId, files, relativePaths, declaredKind, idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(batch));
    }

    @GetMapping("/projects/{projectId}/works/{workId}/document-batches")
    public Result<List<ProjectDocumentBatchVO>> listDocumentBatches(
        @PathVariable Long projectId,
        @PathVariable Long workId,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return Result.success(knowledgeProjectDocumentBatchService.list(projectId, workId, limit));
    }

    @GetMapping("/projects/{projectId}/document-batches/{batchId}")
    public Result<ProjectDocumentBatchVO> getDocumentBatch(@PathVariable Long projectId,
                                                            @PathVariable Long batchId) {
        return Result.success(knowledgeProjectDocumentBatchService.get(projectId, batchId));
    }

    @GetMapping("/projects/{projectId}/document-batches/{batchId}/questions")
    public Result<List<ProjectDocumentQuestionVO>> listDocumentBatchQuestions(@PathVariable Long projectId,
                                                                                @PathVariable Long batchId) {
        return Result.success(knowledgeProjectDocumentBatchService.listQuestions(projectId, batchId));
    }

    @PatchMapping("/projects/{projectId}/document-batches/{batchId}/questions/{questionId}")
    public Result<ProjectDocumentQuestionVO> answerDocumentBatchQuestion(
        @PathVariable Long projectId,
        @PathVariable Long batchId,
        @PathVariable Long questionId,
        @RequestBody ProjectDocumentQuestionAnswerRequest request
    ) {
        return Result.success(knowledgeProjectDocumentBatchService.answerQuestion(
            projectId, batchId, questionId, request
        ));
    }

    @PostMapping("/projects/{projectId}/document-batches/{batchId}/retry")
    public Result<ProjectDocumentBatchVO> retryDocumentBatch(@PathVariable Long projectId,
                                                              @PathVariable Long batchId) {
        return Result.success(knowledgeProjectDocumentBatchService.retry(projectId, batchId));
    }

    @PostMapping("/projects/{projectId}/document-batches/{batchId}/cancel")
    public Result<ProjectDocumentBatchVO> cancelDocumentBatch(@PathVariable Long projectId,
                                                               @PathVariable Long batchId) {
        return Result.success(knowledgeProjectDocumentBatchService.cancel(projectId, batchId));
    }

    @DeleteMapping("/projects/{projectId}/document-batches/{batchId}")
    public Result<Void> discardDocumentBatch(@PathVariable Long projectId,
                                             @PathVariable Long batchId) {
        knowledgeProjectDocumentBatchService.discard(projectId, batchId);
        return Result.success();
    }

    @PostMapping("/projects/{projectId}/works/{workId}/ingest-jobs")
    public ResponseEntity<Result<ProjectIngestJobVO>> submitIngestJob(@PathVariable Long projectId,
                                                                      @PathVariable Long workId,
                                                                      @RequestBody ProjectIngestSubmitRequest request) {
        ProjectIngestJobVO job = knowledgeProjectIngestService.submit(projectId, workId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(job));
    }

    @GetMapping("/projects/{projectId}/ingest-jobs")
    public Result<List<ProjectIngestJobVO>> listIngestJobs(@PathVariable Long projectId,
                                                           @RequestParam(required = false) Long workId,
                                                           @RequestParam(defaultValue = "50") int limit) {
        return Result.success(knowledgeProjectIngestService.listJobs(projectId, workId, limit));
    }

    @GetMapping("/projects/{projectId}/ingest-jobs/{ingestJobId}")
    public Result<ProjectIngestJobVO> getIngestJob(@PathVariable Long projectId,
                                                   @PathVariable Long ingestJobId) {
        return Result.success(knowledgeProjectIngestService.getJob(projectId, ingestJobId));
    }

    @PostMapping("/projects/{projectId}/ingest-jobs/{ingestJobId}/retry")
    public Result<ProjectIngestJobVO> retryIngestJob(@PathVariable Long projectId,
                                                     @PathVariable Long ingestJobId) {
        return Result.success(knowledgeProjectIngestService.retry(projectId, ingestJobId));
    }

    @GetMapping("/projects/{projectId}/extraction-candidates")
    public Result<List<ProjectExtractionCandidateVO>> listExtractionCandidates(@PathVariable Long projectId,
                                                                               @RequestParam(required = false) Long workId,
                                                                               @RequestParam(required = false) String status,
                                                                               @RequestParam(defaultValue = "50") int limit) {
        return Result.success(knowledgeProjectIngestService.listCandidates(projectId, workId, status, limit));
    }

    @PostMapping("/projects/{projectId}/extraction-candidates/{candidateId}/review")
    public Result<ProjectExtractionCandidateVO> reviewExtractionCandidate(@PathVariable Long projectId,
                                                                          @PathVariable Long candidateId,
                                                                          @RequestBody ProjectExtractionReviewRequest request) {
        return Result.success(knowledgeProjectIngestService.reviewCandidate(projectId, candidateId, request));
    }

    @PostMapping("/projects/{projectId}/knowledge-feedback")
    public Result<ProjectKnowledgeFeedbackVO> submitKnowledgeFeedback(@PathVariable Long projectId,
                                                                      @RequestBody ProjectKnowledgeFeedbackRequest request) {
        return Result.success(knowledgeProjectFeedbackService.submit(projectId, request));
    }


    @GetMapping("/projects/{projectId}/works/{workId}/story-graph")
    public Result<StoryGraphResultVO> getStoryGraph(@PathVariable Long projectId,
                                                    @PathVariable Long workId,
                                                    @RequestParam(required = false) Integer nodeLimit) {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "login required");
        }
        return Result.success(knowledgeStoryGraphService.snapshotForWork(user.getUserId(), projectId, workId, nodeLimit));
    }

    @GetMapping("/projects/{projectId}/works/{workId}/memory-overview")
    public Result<ProjectMemoryOverviewVO> getProjectMemoryOverview(@PathVariable Long projectId,
                                                                    @PathVariable Long workId) {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "login required");
        }
        return Result.success(knowledgeProjectMemoryOverviewService.overview(
            user.getUserId(), projectId, workId));
    }

    @PostMapping("/projects/{projectId}/works/{workId}/chapters/{chapterNo}/tombstone")
    public Result<Void> tombstoneChapter(@PathVariable Long projectId,
                                         @PathVariable Long workId,
                                         @PathVariable int chapterNo) {
        knowledgeProjectIngestService.tombstoneChapter(projectId, workId, chapterNo);
        return Result.success();
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent-traces")
    public Result<KnowledgeAgentTracePageVO> listAgentTraces(@RequestParam(value = "page", required = false) Integer page,
                                                             @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                             @RequestParam(value = "status", required = false) String status,
                                                             @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(knowledgeAgentTraceService.listForAdmin(page, pageSize, status, keyword));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent-traces/{traceId}")
    public Result<KnowledgeAgentTraceVO> agentTraceDetail(@PathVariable Long traceId) {
        return Result.success(knowledgeAgentTraceService.detailForAdmin(traceId));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/agent-traces/{traceId}/golden-candidate")
    public Result<GoldenCandidateDraftVO> createGoldenCandidate(@PathVariable Long traceId) {
        return Result.success(knowledgeAgentTraceService.createGoldenCandidateDraft(traceId));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent/runtime-config")
    public Result<AgentRuntimeConfigVO> agentRuntimeConfig() {
        return Result.success(knowledgeAgentGovernanceService.runtimeConfig());
    }

    @RequireRole({"ADMIN"})
    @PutMapping("/admin/agent/runtime-config/{key}")
    public Result<AgentRuntimeConfigVO> updateAgentRuntimeConfig(@PathVariable String key,
                                                                 @RequestBody AgentRuntimeConfigUpdateRequest request) {
        return Result.success(knowledgeAgentGovernanceService.updateRuntimeConfig(key, request));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent/experts")
    public Result<List<AgentExpertProfileVO>> agentExperts() {
        return Result.success(knowledgeAgentGovernanceService.listExpertProfiles());
    }

    @RequireRole({"ADMIN"})
    @PutMapping("/admin/agent/experts/{expertName}")
    public Result<AgentExpertProfileVO> updateAgentExpert(@PathVariable String expertName,
                                                          @RequestBody AgentExpertProfileUpdateRequest request) {
        return Result.success(knowledgeAgentGovernanceService.updateExpertProfile(expertName, request));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent/cache-token-stats")
    public Result<AgentCacheTokenStatsVO> agentCacheTokenStats() {
        return Result.success(knowledgeAgentGovernanceService.cacheTokenStats());
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/agent/eval-runs")
    public Result<AgentEvalRunVO> startAgentEvalRun(@RequestBody AgentEvalRunRequest request) {
        return Result.success(knowledgeAgentEvalService.startRun(request));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/agent/eval-runs/{runId}/cancel")
    public Result<AgentEvalRunVO> cancelAgentEvalRun(@PathVariable Long runId) {
        return Result.success(knowledgeAgentEvalService.cancelRun(runId));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/agent/eval-runs/{runId}/retry")
    public Result<AgentEvalRunVO> retryAgentEvalRun(@PathVariable Long runId) {
        return Result.success(knowledgeAgentEvalService.retryRun(runId));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent/eval-runs")
    public Result<List<AgentEvalRunVO>> agentEvalRuns(@RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(knowledgeAgentEvalService.listRuns(limit));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/agent/eval-runs/{runId}/cases")
    public Result<List<AgentEvalCaseResultVO>> agentEvalCaseResults(@PathVariable Long runId,
                                                                    @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(knowledgeAgentEvalService.listCaseResults(runId, limit));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/skill-candidates")
    public Result<List<SkillCandidateVO>> listSkillCandidates() {
        return Result.success(knowledgeSkillGovernanceService.listCandidates());
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/skills")
    public Result<SkillGovernanceDashboardVO> skillDashboard(@RequestParam(value = "page", required = false) Integer page,
                                                             @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                             @RequestParam(value = "status", required = false) String status) {
        return Result.success(knowledgeSkillGovernanceService.dashboard(page, pageSize, status));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/skill-candidates")
    public Result<SkillCandidateVO> createSkillCandidate(@RequestBody SkillCandidateCreateRequest request) {
        return Result.success(knowledgeSkillGovernanceService.createCandidate(request));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/skill-candidates/{candidateId}/review")
    public Result<SkillCandidateVO> reviewSkillCandidate(@PathVariable Long candidateId,
                                                         @RequestBody SkillCandidateReviewRequest request) {
        return Result.success(knowledgeSkillGovernanceService.review(candidateId, request));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/skill-candidates/{candidateId}/publish")
    public Result<SkillCandidateVO> publishSkillCandidate(@PathVariable Long candidateId) {
        return Result.success(knowledgeSkillGovernanceService.publish(candidateId));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/skill-candidates/{candidateId}/disable")
    public Result<SkillCandidateVO> disableSkillCandidate(@PathVariable Long candidateId) {
        return Result.success(knowledgeSkillGovernanceService.disable(candidateId));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/skill-candidates/{candidateId}/rollback")
    public Result<SkillCandidateVO> rollbackSkillCandidate(@PathVariable Long candidateId) {
        return Result.success(knowledgeSkillGovernanceService.rollback(candidateId));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/memories")
    public Result<List<AiMemoryVO>> listMemories(@RequestParam(value = "userId", required = false) Long userId,
                                                 @RequestParam(value = "projectId", required = false) Long projectId,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "scope", required = false) String scope,
                                                 @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit) {
        return Result.success(knowledgeMemoryService.listMemoriesForAdmin(userId, projectId, status, scope, limit));
    }

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/memory-candidates")
    public Result<List<AiMemoryVO>> listMemoryCandidates(@RequestParam(value = "userId", required = false) Long userId,
                                                         @RequestParam(value = "projectId", required = false) Long projectId,
                                                         @RequestParam(value = "status", required = false) String status,
                                                         @RequestParam(value = "scope", required = false) String scope,
                                                         @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit) {
        return Result.success(knowledgeMemoryService.listCandidateMemoriesForAdmin(userId, projectId, status, scope, limit));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/memory-candidates/{candidateId}/approve")
    public Result<AiMemoryVO> approveMemoryCandidate(@PathVariable Long candidateId) {
        return Result.success(knowledgeMemoryService.reviewCandidateForAdmin(candidateId, "APPROVED"));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/memory-candidates/{candidateId}/reject")
    public Result<AiMemoryVO> rejectMemoryCandidate(@PathVariable Long candidateId) {
        return Result.success(knowledgeMemoryService.reviewCandidateForAdmin(candidateId, "REJECTED"));
    }

    @RequireRole({"ADMIN"})
    @PostMapping("/admin/memories/{memoryId}/delete")
    public Result<Void> deleteMemory(@PathVariable Long memoryId) {
        knowledgeMemoryService.deleteMemoryForAdmin(memoryId);
        return Result.success(null);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody KnowledgeChatRequest request) {
        return knowledgeChatApplicationService.streamChat(request);
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
