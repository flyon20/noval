package com.novelanalyzer.modules.crawler.controller;

import com.novelanalyzer.common.result.Result;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/crawler")
@RequireRole({"ADMIN", "USER"})
public class CrawlerController {

    private final CrawlerService crawlerService;

    public CrawlerController(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @PostMapping("/rank")
    public Result<List<RankBookItemVO>> rank(@Valid @RequestBody CrawlerRankRequest request) {
        return Result.success(crawlerService.getRank(request));
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
    public Result<RankRefreshResultVO> refresh(@Valid @RequestBody CrawlerRankRequest request) {
        return Result.success(crawlerService.refreshRankBoard(request));
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

    @PostMapping("/chapters/refresh")
    public Result<ChapterRefreshResultVO> refreshChapters(@Valid @RequestBody CrawlerChapterRequest request) {
        return Result.success(crawlerService.refreshChapters(request));
    }
}
