package com.novelanalyzer.modules.system.vo;

import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;

import java.util.ArrayList;
import java.util.List;

public class LoginBootstrapVO {

    private List<RankRefreshResultVO> results = new ArrayList<>();

    public List<RankRefreshResultVO> getResults() {
        return results;
    }

    public void setResults(List<RankRefreshResultVO> results) {
        this.results = results;
    }
}
