package com.novelanalyzer.modules.analysis.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.analysis.dto.AnalysisRequest;
import com.novelanalyzer.modules.analysis.service.AnalysisService;
import com.novelanalyzer.modules.analysis.vo.AnalysisResultVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}

