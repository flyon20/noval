package com.novelanalyzer.modules.analysis.vo;

import java.util.Map;

public class TrendAnalysisVO {

    private String analysisType;
    private String platform;
    private String channelCode;
    private String boardCode;
    private String boardName;
    private String modelName;
    private String resultContent;
    private Map<String, Object> resultJson;
    private Integer sourceSnapshotCount;

    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getBoardCode() {
        return boardCode;
    }

    public void setBoardCode(String boardCode) {
        this.boardCode = boardCode;
    }

    public String getBoardName() {
        return boardName;
    }

    public void setBoardName(String boardName) {
        this.boardName = boardName;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getResultContent() {
        return resultContent;
    }

    public void setResultContent(String resultContent) {
        this.resultContent = resultContent;
    }

    public Map<String, Object> getResultJson() {
        return resultJson;
    }

    public void setResultJson(Map<String, Object> resultJson) {
        this.resultJson = resultJson;
    }

    public Integer getSourceSnapshotCount() {
        return sourceSnapshotCount;
    }

    public void setSourceSnapshotCount(Integer sourceSnapshotCount) {
        this.sourceSnapshotCount = sourceSnapshotCount;
    }
}
