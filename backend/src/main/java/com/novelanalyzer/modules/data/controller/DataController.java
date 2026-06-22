package com.novelanalyzer.modules.data.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.data.service.DataQueryService;
import com.novelanalyzer.modules.data.vo.AnalysisHistoryItemVO;
import com.novelanalyzer.modules.data.vo.AnalysisHistoryPageVO;
import com.novelanalyzer.modules.data.vo.VisualDataVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data")
@RequireRole({"ADMIN", "USER"})
public class DataController {

    private final DataQueryService dataQueryService;

    public DataController(DataQueryService dataQueryService) {
        this.dataQueryService = dataQueryService;
    }

    @GetMapping("/history")
    public Result<AnalysisHistoryPageVO> history(@RequestParam(value = "platform", required = false) String platform,
                                                 @RequestParam(value = "bookId", required = false) Long bookId,
                                                 @RequestParam(value = "analysisType", required = false) String analysisType,
                                                 @RequestParam(value = "channelCode", required = false) String channelCode,
                                                 @RequestParam(value = "boardCode", required = false) String boardCode,
                                                 @RequestParam(value = "chapterCount", required = false) Integer chapterCount,
                                                 @RequestParam(value = "modelName", required = false) String modelName,
                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "startTime", required = false) String startTime,
                                                 @RequestParam(value = "endTime", required = false) String endTime,
                                                 @RequestParam(value = "page", required = false) Integer page,
                                                 @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(dataQueryService.getHistory(
            platform,
            bookId,
            analysisType,
            channelCode,
            boardCode,
            chapterCount,
            modelName,
            keyword,
            startTime,
            endTime,
            page,
            pageSize
        ));
    }

    @GetMapping("/history/{id}")
    public Result<AnalysisHistoryItemVO> historyDetail(@PathVariable("id") Long id) {
        return Result.success(dataQueryService.getHistoryDetail(id));
    }

    @GetMapping("/visual")
    public Result<VisualDataVO> visual(@RequestParam("platform") @NotBlank String platform,
                                       @RequestParam("channelCode") @NotBlank String channelCode,
                                       @RequestParam("boardCode") @NotBlank String boardCode) {
        return Result.success(dataQueryService.getVisualData(platform, channelCode, boardCode));
    }
}
