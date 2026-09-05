package com.novelanalyzer.modules.crawler.model;

import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;

public record RankRefreshCommitRecord(
    String idempotencyHash,
    String requestFingerprint,
    RankRefreshResultVO result
) {
}
