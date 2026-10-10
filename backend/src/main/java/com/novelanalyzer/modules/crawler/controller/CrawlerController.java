package com.novelanalyzer.modules.crawler.controller;

import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.dto.CrawlerBookSearchRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.dto.UserRankPreferenceRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.BookDetailVO;
import com.novelanalyzer.modules.crawler.vo.BookSearchCandidateVO;
import com.novelanalyzer.modules.crawler.vo.ChapterRefreshResultVO;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardStatusVO;
import com.novelanalyzer.modules.crawler.vo.RankBookItemVO;
import com.novelanalyzer.modules.crawler.vo.RankPageVO;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import com.novelanalyzer.modules.crawler.vo.UserRankPreferenceVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@Validated
@RestController
@RequestMapping("/api/crawler")
@RequireRole({"ADMIN", "USER"})
public class CrawlerController {

    private final CrawlerService crawlerService;
    private final AsyncTaskExecutor rankRefreshTaskExecutor;

    public CrawlerController(CrawlerService crawlerService) {
        this(crawlerService, Runnable::run);
    }

    @Autowired
    public CrawlerController(CrawlerService crawlerService,
                             @Qualifier("crawlerRankRefreshTaskExecutor") AsyncTaskExecutor rankRefreshTaskExecutor) {
        this.crawlerService = crawlerService;
        this.rankRefreshTaskExecutor = rankRefreshTaskExecutor;
    }

    @PostMapping("/rank")
    public CompletableFuture<ResponseEntity<Result<List<RankBookItemVO>>>> rank(
        @Valid @RequestBody CrawlerRankRequest request
    ) {
        return submitRankOperation(() -> crawlerService.getRank(request));
    }

    @GetMapping("/boards")
    public Result<List<RankBoardCatalogVO>> boards(@RequestParam("platform") @NotBlank String platform) {
        return Result.success(crawlerService.getBoardCatalog(platform));
    }

    @GetMapping("/preference")
    public Result<UserRankPreferenceVO> getPreference(@RequestParam("platform") @NotBlank String platform) {
        return Result.success(crawlerService.getUserRankPreference(platform));
    }

    @PostMapping("/preference")
    public Result<UserRankPreferenceVO> savePreference(@Valid @RequestBody UserRankPreferenceRequest request) {
        return Result.success(crawlerService.saveUserRankPreference(request));
    }

    @PostMapping("/rank/refresh")
    public CompletableFuture<ResponseEntity<Result<RankRefreshResultVO>>> refresh(
        @Valid @RequestBody CrawlerRankRequest request
    ) {
        if (request == null || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "idempotencyKey is required for rank refresh");
        }
        if (CrawlerRankRequest.REFRESH_MODE_FORCE.equalsIgnoreCase(request.getRefreshMode())
            && !AuthUserHolder.getRoles().contains("ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "FORCE rank refresh requires administrator role");
        }
        return submitRankOperation(() -> crawlerService.refreshRankBoard(request));
    }

    private <T> CompletableFuture<ResponseEntity<Result<T>>> submitRankOperation(Supplier<T> operation) {
        AuthUser caller = AuthUserHolder.get();
        String traceId = TraceIdHolder.get();
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (caller != null) {
                    AuthUserHolder.set(caller);
                }
                TraceIdHolder.set(traceId);
                try {
                    return ResponseEntity.ok(Result.success(operation.get()));
                } catch (CrawlerService.RankRefreshInProgressException ex) {
                    return rankRefreshConflict();
                } finally {
                    AuthUserHolder.clear();
                    TraceIdHolder.clear();
                }
            }, rankRefreshTaskExecutor);
        } catch (RejectedExecutionException ex) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "rank refresh worker is busy");
        }
    }

    private <T> ResponseEntity<Result<T>> rankRefreshConflict() {
        Result<T> response = new Result<>();
        response.setCode(HttpStatus.CONFLICT.value());
        response.setMessage(CrawlerService.RANK_REFRESH_IN_PROGRESS);
        response.setTimestamp(System.currentTimeMillis());
        response.setTraceId(TraceIdHolder.get());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(response);
    }

    @GetMapping("/rank/page")
    public Result<RankPageVO> rankPage(@RequestParam("platform") @NotBlank String platform,
                                       @RequestParam("channelCode") @NotBlank String channelCode,
                                       @RequestParam("boardCode") @NotBlank String boardCode,
                                       @RequestParam("page") @Min(1) Integer page,
                                       @RequestParam("pageSize") @Min(1) Integer pageSize) {
        return Result.success(crawlerService.getRankPage(platform, channelCode, boardCode, page, pageSize));
    }

    @GetMapping("/rank/status")
    public Result<RankBoardStatusVO> rankStatus(@RequestParam("platform") @NotBlank String platform,
                                                @RequestParam("channelCode") @NotBlank String channelCode,
                                                @RequestParam("boardCode") @NotBlank String boardCode) {
        return Result.success(crawlerService.getRankStatus(platform, channelCode, boardCode));
    }

    @PostMapping("/books/search")
    public Result<List<BookSearchCandidateVO>> searchBooks(@Valid @RequestBody CrawlerBookSearchRequest request) {
        return Result.success(crawlerService.searchBooks(request));
    }

    @GetMapping("/book/{id}")
    public Result<BookDetailVO> book(@PathVariable("id") Long id,
                                     @RequestParam("platform") @NotBlank String platform) {
        return Result.success(crawlerService.getBookDetail(platform, id));
    }

    @PostMapping("/chapters")
    public Result<List<ChapterVO>> chapters(@Valid @RequestBody CrawlerChapterRequest request) {
        return Result.success(crawlerService.getChapters(request));
    }

    @PostMapping("/chapters/status")
    public Result<List<ChapterVO>> chapterStatus(@Valid @RequestBody CrawlerChapterRequest request) {
        return Result.success(crawlerService.getChapterStatus(request));
    }

    @PostMapping("/chapters/refresh")
    public Result<ChapterRefreshResultVO> refreshChapters(@Valid @RequestBody CrawlerChapterRequest request) {
        return Result.success(crawlerService.refreshChapters(request));
    }
}
