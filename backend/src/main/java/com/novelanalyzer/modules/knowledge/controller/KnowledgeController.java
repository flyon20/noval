package com.novelanalyzer.modules.knowledge.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeIndexRequest;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
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

    public KnowledgeController(KnowledgeRetrievalService knowledgeRetrievalService,
                               KnowledgeChatService knowledgeChatService,
                               KnowledgeIndexJobExecutor knowledgeIndexJobExecutor) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeChatService = knowledgeChatService;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
    }

    @PostMapping("/search")
    public Result<List<KnowledgeSearchResultVO>> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return Result.success(knowledgeRetrievalService.search(request));
    }

    @PostMapping("/chat")
    public Result<KnowledgeChatResponseVO> chat(@Valid @RequestBody KnowledgeChatRequest request) {
        return Result.success(knowledgeChatService.chat(request));
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
}
