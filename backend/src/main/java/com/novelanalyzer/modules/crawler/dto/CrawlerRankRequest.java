package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public class CrawlerRankRequest {

    public static final String REFRESH_MODE_AUTO = "AUTO";
    public static final String REFRESH_MODE_FORCE = "FORCE";

    @NotBlank(message = "platform is required")
    private String platform;

    private String category;

    private String channelCode;

    private String boardCode;

    private String refreshMode;

    private String forceReason;

    private Integer rankFetchCount;

    private String idempotencyKey;

    private Long userId;

    private Long projectId;

    private SupervisorAttestation supervisorAttestation;

    @AssertTrue(message = "category or channelCode/boardCode is required")
    public boolean isScopeValid() {
        return hasLegacyCategory() || hasBoardSelection();
    }

    public boolean hasLegacyCategory() {
        return category != null && !category.isBlank();
    }

    public boolean hasBoardSelection() {
        return channelCode != null && !channelCode.isBlank()
            && boardCode != null && !boardCode.isBlank();
    }

    @AssertTrue(message = "rankFetchCount must be between 10 and 100, in steps of 10")
    public boolean isRankFetchCountValid() {
        return rankFetchCount == null
            || (rankFetchCount >= 10 && rankFetchCount <= 100 && rankFetchCount % 10 == 0);
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public String getRefreshMode() {
        return refreshMode;
    }

    public void setRefreshMode(String refreshMode) {
        this.refreshMode = refreshMode;
    }

    public String getForceReason() {
        return forceReason;
    }

    public void setForceReason(String forceReason) {
        this.forceReason = forceReason;
    }

    public Integer getRankFetchCount() {
        return rankFetchCount;
    }

    public void setRankFetchCount(Integer rankFetchCount) {
        this.rankFetchCount = rankFetchCount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public SupervisorAttestation getSupervisorAttestation() {
        return supervisorAttestation;
    }

    public void setSupervisorAttestation(SupervisorAttestation supervisorAttestation) {
        this.supervisorAttestation = supervisorAttestation;
    }

    public static class SupervisorAttestation {
        private String tool;
        private String route;
        private String permission;
        private String userId;
        private String projectId;
        private Long timestamp;
        private String nonce;
        private String signature;

        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
        public String getPermission() { return permission; }
        public void setPermission(String permission) { this.permission = permission; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
        public String getNonce() { return nonce; }
        public void setNonce(String nonce) { this.nonce = nonce; }
        public String getSignature() { return signature; }
        public void setSignature(String signature) { this.signature = signature; }
    }
}
