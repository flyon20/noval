package com.novelanalyzer.modules.crawler.model;

import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;

public class RankRefreshIdempotencyEntry {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    private String fingerprint;
    private String status;
    private String ownerToken;
    private Long startedAtEpochMillis;
    private RankRefreshResultVO result;

    public RankRefreshIdempotencyEntry() {
    }

    public RankRefreshIdempotencyEntry(String fingerprint,
                                       String status,
                                       String ownerToken,
                                       Long startedAtEpochMillis,
                                       RankRefreshResultVO result) {
        this.fingerprint = fingerprint;
        this.status = status;
        this.ownerToken = ownerToken;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.result = result;
    }

    public static RankRefreshIdempotencyEntry inProgress(String fingerprint, String ownerToken, long startedAtEpochMillis) {
        return new RankRefreshIdempotencyEntry(
            fingerprint,
            STATUS_IN_PROGRESS,
            ownerToken,
            startedAtEpochMillis,
            null
        );
    }

    public static RankRefreshIdempotencyEntry succeeded(String fingerprint, RankRefreshResultVO result) {
        return new RankRefreshIdempotencyEntry(
            fingerprint,
            STATUS_SUCCEEDED,
            null,
            null,
            result
        );
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerToken() {
        return ownerToken;
    }

    public void setOwnerToken(String ownerToken) {
        this.ownerToken = ownerToken;
    }

    public Long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public void setStartedAtEpochMillis(Long startedAtEpochMillis) {
        this.startedAtEpochMillis = startedAtEpochMillis;
    }

    public RankRefreshResultVO getResult() {
        return result;
    }

    public void setResult(RankRefreshResultVO result) {
        this.result = result;
    }
}
