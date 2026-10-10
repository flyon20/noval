package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankRefreshCommitRecord;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.repository.CrawlerRankGovernanceRepository;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawlerRankPersistenceServiceTest {

    @Mock
    private CrawlerRepository crawlerRepository;

    @Mock
    private CrawlerRankGovernanceRepository governanceRepository;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldDeclareSnapshotBatchAsTransactional() throws Exception {
        Method method = CrawlerRankPersistenceService.class.getMethod(
            "persistBoardSnapshot",
            CrawlerRankRequest.class,
            RankBoardEntity.class,
            String.class,
            String.class,
            List.class,
            LocalDateTime.class,
            java.util.function.BooleanSupplier.class,
            long.class,
            CrawlerRankPersistenceService.IdempotencyContext.class
        );

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void shouldRecheckOwnershipImmediatelyBeforeTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicBoolean healthy = new AtomicBoolean(true);
        CrawlerRankPersistenceService service = new CrawlerRankPersistenceService(
            crawlerRepository,
            governanceRepository
        );
        CrawlerRankRequest request = request();
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(2L);
        when(crawlerRepository.saveRankSnapshot(eq(1L), any(LocalDateTime.class), eq(0)))
            .thenReturn(snapshot);
        when(governanceRepository.lockAndVerifyFencingToken(1L, 7L)).thenReturn(true);

        service.persistBoardSnapshot(
            request,
            board,
            "AUTO",
            "urban-brain",
            List.of(),
            LocalDateTime.now(),
            healthy::get,
            7L,
            null
        );
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        healthy.set(false);

        assertThat(synchronizations).hasSize(1);
        assertThatThrownBy(() -> synchronizations.get(0).beforeCommit(false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ownership lost");
        verify(crawlerRepository).saveRankRefreshTask(
            eq("fanqie"),
            eq("male-new"),
            eq("urban-brain"),
            eq("AUTO"),
            any(),
            eq(2),
            any(),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
    }

    @Test
    void shouldRejectStaleDatabaseFencingTokenBeforeWritingSnapshot() {
        CrawlerRankPersistenceService service = new CrawlerRankPersistenceService(
            crawlerRepository,
            governanceRepository
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        when(governanceRepository.lockAndVerifyFencingToken(1L, 6L)).thenReturn(false);

        assertThatThrownBy(() -> service.persistBoardSnapshot(
            request(),
            board,
            "AUTO",
            "urban-brain",
            List.of(),
            LocalDateTime.now(),
            () -> true,
            6L,
            null
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("fencing token is stale");

        verify(crawlerRepository, org.mockito.Mockito.never())
            .saveRankSnapshot(org.mockito.ArgumentMatchers.anyLong(), any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldRejectCommittedIdempotencyKeyWithDifferentFingerprint() {
        CrawlerRankPersistenceService service = new CrawlerRankPersistenceService(
            crawlerRepository,
            governanceRepository
        );
        RankRefreshResultVO result = new RankRefreshResultVO();
        result.setSnapshotId(10L);
        when(governanceRepository.findCommitted("hash"))
            .thenReturn(Optional.of(new RankRefreshCommitRecord("hash", "other-fingerprint", result)));

        assertThatThrownBy(() -> service.findCommittedResult(
            new CrawlerRankPersistenceService.IdempotencyContext("hash", "expected-fingerprint")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("different rank refresh arguments");
    }

    private CrawlerRankRequest request() {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        return request;
    }
}
