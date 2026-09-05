package com.novelanalyzer.modules.system.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardOptionVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardStatusVO;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import com.novelanalyzer.modules.system.vo.LoginBootstrapVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LoginBootstrapService {

    private final CrawlerService crawlerService;

    public LoginBootstrapService(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    public LoginBootstrapVO bootstrap(String platform) {
        List<RankBoardCatalogVO> catalogs = crawlerService.getPersistedBoardCatalog(platform);
        List<RankRefreshResultVO> results = new ArrayList<>();
        for (RankBoardCatalogVO catalog : catalogs) {
            for (RankBoardOptionVO board : catalog.getBoards()) {
                try {
                    RankBoardStatusVO status = crawlerService.getRankStatus(
                        platform, catalog.getChannelCode(), board.getBoardCode()
                    );
                    RankRefreshResultVO result = new RankRefreshResultVO();
                    result.setChannelCode(catalog.getChannelCode());
                    result.setBoardCode(board.getBoardCode());
                    result.setSnapshotId(status.getSnapshotId());
                    result.setSnapshotTime(status.getSnapshotTime());
                    result.setTotal(status.getTotal());
                    result.setReused(true);
                    result.setRefreshLimited(false);
                    result.setAnalysisTriggered(false);
                    results.add(result);
                } catch (BusinessException ex) {
                    if (ex.getResultCode() != ResultCode.NOT_FOUND) {
                        throw ex;
                    }
                    // Login bootstrap is cache-only; a missing snapshot must not start a crawl.
                }
            }
        }
        LoginBootstrapVO vo = new LoginBootstrapVO();
        vo.setResults(results);
        return vo;
    }
}
