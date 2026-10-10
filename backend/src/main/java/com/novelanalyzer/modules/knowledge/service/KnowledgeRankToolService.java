package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.crawler.service.CrawlerRefreshPolicyService;
import com.novelanalyzer.modules.knowledge.dto.RankLookupRequest;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class KnowledgeRankToolService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeRankToolService.class);

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;
    private final CrawlerRefreshPolicyService crawlerRefreshPolicyService;
    private final KnowledgeProperties knowledgeProperties;
    private final TaskExecutor knowledgeIndexTaskExecutor;

    public KnowledgeRankToolService(KnowledgeRepository knowledgeRepository,
                                    KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                    CrawlerRefreshPolicyService crawlerRefreshPolicyService,
                                    KnowledgeProperties knowledgeProperties,
                                    @Qualifier("knowledgeIndexTaskExecutor") TaskExecutor knowledgeIndexTaskExecutor) {
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.crawlerRefreshPolicyService = crawlerRefreshPolicyService;
        this.knowledgeProperties = knowledgeProperties;
        this.knowledgeIndexTaskExecutor = knowledgeIndexTaskExecutor;
    }

    public List<RankLookupResultVO> lookupRank(RankLookupRequest request) {
        validateSnapshotDateRange(request);
        String platform = trimToNull(request.getPlatform());
        String channelCode = trimToNull(request.getChannelCode());
        String boardCode = trimToNull(request.getBoardCode());
        String category = trimToNull(request.getCategory());
        int limit = normalizeLimit(request.getLimit());
        List<RankLookupResultVO> results;
        if (hasSnapshotDateRange(request)) {
            results = knowledgeRepository.lookupRankSnapshotsInDateRange(
                platform,
                channelCode,
                boardCode,
                category,
                request.getRankNo(),
                request.getSnapshotStartDate(),
                request.getSnapshotEndDate(),
                limit
            );
        } else if (usesHistoricalTimeWindow(request)) {
            results = knowledgeRepository.lookupRankSnapshotsInTimeWindow(
                platform,
                channelCode,
                boardCode,
                category,
                request.getRankNo(),
                normalizeTimeWindowDays(request.getTimeWindowDays()),
                limit
            );
        } else {
            results = knowledgeRepository.lookupLatestRanks(
                platform,
                channelCode,
                boardCode,
                category,
                request.getRankNo(),
                limit
            );
        }
        annotateFreshness(results, request);
        dispatchIncrementalIndexJobs(results);
        return results;
    }

    private void annotateFreshness(List<RankLookupResultVO> results, RankLookupRequest request) {
        if (results == null || results.isEmpty() || crawlerRefreshPolicyService == null) {
            return;
        }
        boolean latestMode = request == null
            || request.getFreshness() == null
            || request.getFreshness().isBlank()
            || "latest".equalsIgnoreCase(request.getFreshness().trim());
        for (RankLookupResultVO result : results) {
            CrawlerRefreshPolicyService.RankSnapshotEvaluation evaluation =
                crawlerRefreshPolicyService.evaluateRankSnapshot(result.getSnapshotTime());
            result.setFreshness(evaluation.freshness());
            result.setAgeHours(evaluation.ageHours());
            // latest mode never presents EXPIRED as current evidence without historical flag.
            boolean historical = evaluation.historicalReference()
                || (!latestMode && Boolean.TRUE.equals(request.getAllowHistorical()));
            result.setHistoricalReference(historical);
            if (latestMode && evaluation.isExpired()) {
                result.setHistoricalReference(true);
            }
        }
    }

    private void dispatchIncrementalIndexJobs(List<RankLookupResultVO> results) {
        if (results == null
            || results.isEmpty()
            || knowledgeIndexJobExecutor == null
            || knowledgeIndexTaskExecutor == null
            || knowledgeProperties == null
            || !knowledgeProperties.getIndex().isRankIncrementalEnabled()) {
            return;
        }

        Long latestSnapshotId = results.stream()
            .map(RankLookupResultVO::getSnapshotId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        Set<Long> bookIds = new LinkedHashSet<>();
        for (RankLookupResultVO result : results) {
            if (latestSnapshotId != null && !Objects.equals(latestSnapshotId, result.getSnapshotId())) {
                continue;
            }
            if (result.getBookId() != null) {
                bookIds.add(result.getBookId());
            }
        }
        if (bookIds.isEmpty()) {
            return;
        }

        try {
            knowledgeIndexTaskExecutor.execute(() -> submitIncrementalIndexJobs(bookIds));
        } catch (RuntimeException ex) {
            LOGGER.warn("rank incremental indexing dispatch rejected: books={}, message={}", bookIds.size(), ex.getMessage());
        }
    }

    private void submitIncrementalIndexJobs(Set<Long> bookIds) {
        for (Long bookId : bookIds) {
            try {
                knowledgeIndexJobExecutor.submitAndExecute(bookId, null, "RANK_INCREMENTAL");
            } catch (Exception ex) {
                LOGGER.warn("rank incremental indexing request failed: bookId={}, message={}", bookId, ex.getMessage());
            }
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 10;
        }
        return Math.min(Math.max(limit, 1), 100);
    }

    private boolean usesHistoricalTimeWindow(RankLookupRequest request) {
        String freshness = request.getFreshness() == null ? "" : request.getFreshness().trim();
        return Boolean.TRUE.equals(request.getAllowHistorical())
            && "time_window".equalsIgnoreCase(freshness);
    }

    private int normalizeTimeWindowDays(Integer timeWindowDays) {
        if (timeWindowDays == null) {
            return 30;
        }
        return Math.min(Math.max(timeWindowDays, 1), 365);
    }

    private boolean hasSnapshotDateRange(RankLookupRequest request) {
        return Boolean.TRUE.equals(request.getAllowHistorical())
            && request.getSnapshotStartDate() != null
            && request.getSnapshotEndDate() != null
            && !request.getSnapshotStartDate().isAfter(request.getSnapshotEndDate());
    }

    private void validateSnapshotDateRange(RankLookupRequest request) {
        boolean hasStart = request.getSnapshotStartDate() != null;
        boolean hasEnd = request.getSnapshotEndDate() != null;
        if (hasStart != hasEnd) {
            throw new IllegalArgumentException("snapshot start and end dates must be provided together");
        }
        if (!hasStart) {
            return;
        }
        if (request.getSnapshotStartDate().isAfter(request.getSnapshotEndDate())) {
            throw new IllegalArgumentException("snapshot start date must not be after end date");
        }
        if (!Boolean.TRUE.equals(request.getAllowHistorical())) {
            throw new IllegalArgumentException("snapshot date range requires historical lookup");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
