package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.dto.RankLookupRequest;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeRankToolService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeRankToolService.class);

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;

    public KnowledgeRankToolService(KnowledgeRepository knowledgeRepository,
                                    KnowledgeIndexJobExecutor knowledgeIndexJobExecutor) {
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
    }

    public List<RankLookupResultVO> lookupRank(RankLookupRequest request) {
        String platform = trimToNull(request.getPlatform());
        String channelCode = trimToNull(request.getChannelCode());
        String boardCode = trimToNull(request.getBoardCode());
        String category = trimToNull(request.getCategory());
        int limit = normalizeLimit(request.getLimit());
        List<RankLookupResultVO> results;
        if (usesHistoricalTimeWindow(request)) {
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
        submitIncrementalIndexJobs(results);
        return results;
    }

    private void submitIncrementalIndexJobs(List<RankLookupResultVO> results) {
        if (results == null || results.isEmpty() || knowledgeIndexJobExecutor == null) {
            return;
        }
        for (RankLookupResultVO result : results) {
            Long bookId = result.getBookId();
            if (bookId == null) {
                continue;
            }
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
