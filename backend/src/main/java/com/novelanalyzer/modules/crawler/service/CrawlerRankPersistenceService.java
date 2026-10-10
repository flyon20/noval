package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankRefreshCommitRecord;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRankGovernanceRepository;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

@Service
public class CrawlerRankPersistenceService {

    private final CrawlerRepository crawlerRepository;
    private final CrawlerRankGovernanceRepository governanceRepository;

    public CrawlerRankPersistenceService(CrawlerRepository crawlerRepository,
                                         CrawlerRankGovernanceRepository governanceRepository) {
        this.crawlerRepository = crawlerRepository;
        this.governanceRepository = governanceRepository;
    }

    @Transactional
    public long claimFencingToken(Long rankBoardId) {
        return governanceRepository.nextFencingToken(rankBoardId);
    }

    public RankRefreshResultVO findCommittedResult(IdempotencyContext context) {
        if (context == null) {
            return null;
        }
        RankRefreshCommitRecord committed = governanceRepository.findCommitted(context.idempotencyHash())
            .orElse(null);
        if (committed == null) {
            return null;
        }
        validateFingerprint(context, committed);
        return committed.result();
    }

    @Transactional
    public RankRefreshResultVO commitReusedResult(IdempotencyContext context,
                                                  RankRefreshResultVO result) {
        if (context == null) {
            return result;
        }
        if (governanceRepository.tryInsertCommitted(
            context.idempotencyHash(),
            context.requestFingerprint(),
            result
        )) {
            return result;
        }
        RankRefreshCommitRecord committed = governanceRepository.findCommitted(context.idempotencyHash())
            .orElseThrow(() -> new IllegalStateException("rank refresh commit disappeared after duplicate key"));
        validateFingerprint(context, committed);
        return committed.result();
    }

    @Transactional
    public RankSnapshotEntity persistBoardSnapshot(CrawlerRankRequest request,
                                                   RankBoardEntity board,
                                                   String refreshMode,
                                                   String category,
                                                   List<ExternalRankItem> rankItems,
                                                   LocalDateTime startTime,
                                                   BooleanSupplier ownershipHealthy,
                                                   long fencingToken,
                                                   IdempotencyContext idempotencyContext) {
        requireFencingToken(board.getId(), fencingToken);
        registerFinalOwnershipCheck(ownershipHealthy);
        requireOwnership(ownershipHealthy);
        LocalDateTime snapshotTime = LocalDateTime.now();
        RankSnapshotEntity snapshot = crawlerRepository.saveRankSnapshot(
            board.getId(),
            snapshotTime,
            rankItems.size()
        );
        for (ExternalRankItem item : rankItems) {
            requireOwnership(ownershipHealthy);
            Long bookId = crawlerRepository.saveOrUpdateBook(
                request.getPlatform(),
                item.getPlatformBookId(),
                item.getBookName(),
                item.getAuthor(),
                item.getIntro(),
                item.getBookUrl()
            );
            crawlerRepository.saveRankItem(
                request.getPlatform(),
                category,
                request.getChannelCode(),
                request.getBoardCode(),
                snapshot.getId(),
                item.getRankNo(),
                bookId,
                item.getBookName(),
                item.getBookUrl(),
                item.getAuthor(),
                item.getIntro(),
                snapshotTime
            );
        }
        requireOwnership(ownershipHealthy);
        crawlerRepository.saveRankRefreshTask(
            request.getPlatform(),
            request.getChannelCode(),
            request.getBoardCode(),
            refreshMode,
            request.getForceReason(),
            2,
            null,
            startTime,
            LocalDateTime.now()
        );
        requireOwnership(ownershipHealthy);
        if (idempotencyContext != null && !governanceRepository.tryInsertCommitted(
            idempotencyContext.idempotencyHash(),
            idempotencyContext.requestFingerprint(),
            toRefreshResult(request, snapshot)
        )) {
            RankRefreshCommitRecord committed = governanceRepository.findCommitted(idempotencyContext.idempotencyHash())
                .orElseThrow(() -> new IllegalStateException("rank refresh commit disappeared after duplicate key"));
            validateFingerprint(idempotencyContext, committed);
            throw new RankRefreshAlreadyCommittedException(committed.result());
        }
        requireOwnership(ownershipHealthy);
        return snapshot;
    }

    private void requireFencingToken(Long rankBoardId, long fencingToken) {
        if (!governanceRepository.lockAndVerifyFencingToken(rankBoardId, fencingToken)) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "rank refresh fencing token is stale");
        }
    }

    private RankRefreshResultVO toRefreshResult(CrawlerRankRequest request, RankSnapshotEntity snapshot) {
        RankRefreshResultVO result = new RankRefreshResultVO();
        result.setChannelCode(request.getChannelCode());
        result.setBoardCode(request.getBoardCode());
        result.setSnapshotId(snapshot.getId());
        result.setSnapshotTime(snapshot.getSnapshotTime());
        result.setTotal(snapshot.getRecordCount());
        result.setReused(Boolean.FALSE);
        result.setRefreshLimited(Boolean.FALSE);
        result.setAnalysisTriggered(Boolean.FALSE);
        return result;
    }

    private void registerFinalOwnershipCheck(BooleanSupplier ownershipHealthy) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                requireOwnership(ownershipHealthy);
            }
        });
    }

    private void requireOwnership(BooleanSupplier ownershipHealthy) {
        if (ownershipHealthy == null || !ownershipHealthy.getAsBoolean()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "rank refresh ownership lost");
        }
    }

    private void validateFingerprint(IdempotencyContext context, RankRefreshCommitRecord committed) {
        if (!context.requestFingerprint().equals(committed.requestFingerprint())) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "idempotency key reused with different rank refresh arguments"
            );
        }
    }

    public record IdempotencyContext(String idempotencyHash, String requestFingerprint) {
    }

    public static final class RankRefreshAlreadyCommittedException extends RuntimeException {
        private final RankRefreshResultVO result;

        public RankRefreshAlreadyCommittedException(RankRefreshResultVO result) {
            super("rank refresh idempotency result already committed");
            this.result = result;
        }

        public RankRefreshResultVO getResult() {
            return result;
        }
    }
}
