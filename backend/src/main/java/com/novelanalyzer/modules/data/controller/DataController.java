package com.novelanalyzer.modules.data.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.data.service.DataQueryService;
import com.novelanalyzer.modules.data.vo.AnalysisHistoryItemVO;
import com.novelanalyzer.modules.data.vo.VisualDataVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/data")
@RequireRole({"ADMIN", "USER"})
public class DataController {

    private final DataQueryService dataQueryService;

    public DataController(DataQueryService dataQueryService) {
        this.dataQueryService = dataQueryService;
    }

    @GetMapping("/history")
    public Result<List<AnalysisHistoryItemVO>> history(@RequestParam(value = "platform", required = false) String platform,
                                                       @RequestParam(value = "bookId", required = false) Long bookId,
                                                       @RequestParam(value = "analysisType", required = false) String analysisType,
                                                       @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(dataQueryService.getHistory(platform, bookId, analysisType, limit));
    }

    @GetMapping("/visual")
    public Result<VisualDataVO> visual(@RequestParam(value = "platform", required = false) String platform) {
        return Result.success(dataQueryService.getVisualData(platform));
    }
}
