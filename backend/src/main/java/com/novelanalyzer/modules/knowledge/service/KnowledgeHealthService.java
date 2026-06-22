package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeHealthVO;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeHealthService {

    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIndexQueueService knowledgeIndexQueueService;

    public KnowledgeHealthService(KnowledgeProperties knowledgeProperties,
                                  KnowledgeRepository knowledgeRepository,
                                  KnowledgeIndexQueueService knowledgeIndexQueueService) {
        this.knowledgeProperties = knowledgeProperties;
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeIndexQueueService = knowledgeIndexQueueService;
    }

    public KnowledgeHealthVO health() {
        String embeddingModel = knowledgeProperties.getEmbedding().getModel();
        Integer embeddingDimension = knowledgeProperties.getEmbedding().getDimension();
        KnowledgeHealthVO vo = new KnowledgeHealthVO();
        vo.setEmbeddingProvider(knowledgeProperties.getEmbedding().getProvider());
        vo.setEmbeddingModel(embeddingModel);
        vo.setEmbeddingDimension(embeddingDimension);
        vo.setChunkStats(knowledgeRepository.countChunksBySourceAndStatus(embeddingModel, embeddingDimension));
        vo.setRankRows(knowledgeRepository.rankCoverage(embeddingModel, embeddingDimension));
        vo.setChapters(knowledgeRepository.chapterCoverage(embeddingModel, embeddingDimension));
        vo.setJobStats(knowledgeRepository.countKnowledgeIndexJobsByStatus());

        KnowledgeIndexQueueService.QueueStats stats = knowledgeIndexQueueService.stats();
        KnowledgeHealthVO.QueueStat queue = new KnowledgeHealthVO.QueueStat();
        queue.setWaiting(stats.waiting());
        queue.setProcessing(stats.processing());
        queue.setRetry(stats.retry());
        vo.setQueue(queue);
        return vo;
    }
}
