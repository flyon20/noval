package com.novelanalyzer.modules.crawler.vo;

import java.util.ArrayList;
import java.util.List;

public class RankBoardCatalogVO {

    private String channelCode;
    private String channelName;
    private List<RankBoardOptionVO> boards = new ArrayList<>();

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public List<RankBoardOptionVO> getBoards() {
        return boards;
    }

    public void setBoards(List<RankBoardOptionVO> boards) {
        this.boards = boards;
    }
}
