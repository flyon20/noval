package com.novelanalyzer.modules.analysis.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.analysis.dto.AnalysisRequest;
import com.novelanalyzer.modules.analysis.service.AnalysisService;
import com.novelanalyzer.modules.analysis.vo.AnalysisResultVO;
import com.novelanalyzer.modules.analysis.vo.TrendAnalysisVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
@RequireRole({"ADMIN", "USER"})
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/deconstruct")
    public Result<AnalysisResultVO> deconstruct(@Valid @RequestBody AnalysisRequest request) {
        return Result.success(analysisService.analyze("deconstruct", request));
    }

    @PostMapping("/structure")
    public Result<AnalysisResultVO> structure(@Valid @RequestBody AnalysisRequest request) {
        return Result.success(analysisService.analyze("structure", request));
    }

    @PostMapping("/plot")
    public Result<AnalysisResultVO> plot(@Valid @RequestBody AnalysisRequest request) {
        return Result.success(analysisService.analyze("plot", request));
    }

    @GetMapping("/trend")
    public Result<TrendAnalysisVO> trend(@RequestParam("platform") @NotBlank String platform,
                                         @RequestParam(value = "category", required = false) String category) {
        return Result.success(analysisService.analyzeTrend(platform, category));
    }
}
