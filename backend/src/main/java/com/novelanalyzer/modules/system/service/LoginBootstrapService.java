package com.novelanalyzer.modules.system.service;

import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardOptionVO;
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
        List<RankBoardCatalogVO> catalogs = crawlerService.getBoardCatalog(platform);
        List<RankRefreshResultVO> results = new ArrayList<>();
        for (RankBoardCatalogVO catalog : catalogs) {
            for (RankBoardOptionVO board : catalog.getBoards()) {
                CrawlerRankRequest request = new CrawlerRankRequest();
                request.setPlatform(platform);
                request.setChannelCode(catalog.getChannelCode());
                request.setBoardCode(board.getBoardCode());
                request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
                results.add(crawlerService.refreshRankBoard(request));
            }
        }
        LoginBootstrapVO vo = new LoginBootstrapVO();
        vo.setResults(results);
        return vo;
    }
}
