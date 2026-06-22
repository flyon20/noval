package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeHealthVO {

    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimension;
    private List<ChunkStat> chunkStats = new ArrayList<>();
    private CoverageStat rankRows = new CoverageStat();
    private CoverageStat chapters = new CoverageStat();
    private List<JobStat> jobStats = new ArrayList<>();
    private QueueStat queue = new QueueStat();

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public List<ChunkStat> getChunkStats() {
        return chunkStats;
    }

    public void setChunkStats(List<ChunkStat> chunkStats) {
        this.chunkStats = chunkStats == null ? new ArrayList<>() : chunkStats;
    }

    public CoverageStat getRankRows() {
        return rankRows;
    }

    public void setRankRows(CoverageStat rankRows) {
        this.rankRows = rankRows == null ? new CoverageStat() : rankRows;
    }

    public CoverageStat getChapters() {
        return chapters;
    }

    public void setChapters(CoverageStat chapters) {
        this.chapters = chapters == null ? new CoverageStat() : chapters;
    }

    public List<JobStat> getJobStats() {
        return jobStats;
    }

    public void setJobStats(List<JobStat> jobStats) {
        this.jobStats = jobStats == null ? new ArrayList<>() : jobStats;
    }

    public QueueStat getQueue() {
        return queue;
    }

    public void setQueue(QueueStat queue) {
        this.queue = queue == null ? new QueueStat() : queue;
    }

    public static class ChunkStat {
        private String sourceType;
        private String vectorStatus;
        private Long count;

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        public String getVectorStatus() {
            return vectorStatus;
        }

        public void setVectorStatus(String vectorStatus) {
            this.vectorStatus = vectorStatus;
        }

        public Long getCount() {
            return count;
        }

        public void setCount(Long count) {
            this.count = count;
        }
    }

    public static class CoverageStat {
        private Long total = 0L;
        private Long indexed = 0L;
        private Long missing = 0L;

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }

        public Long getIndexed() {
            return indexed;
        }

        public void setIndexed(Long indexed) {
            this.indexed = indexed;
        }

        public Long getMissing() {
            return missing;
        }

        public void setMissing(Long missing) {
            this.missing = missing;
        }
    }

    public static class JobStat {
        private String status;
        private Long count;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getCount() {
            return count;
        }

        public void setCount(Long count) {
            this.count = count;
        }
    }

    public static class QueueStat {
        private Long waiting = 0L;
        private Long processing = 0L;
        private Long retry = 0L;

        public Long getWaiting() {
            return waiting;
        }

        public void setWaiting(Long waiting) {
            this.waiting = waiting;
        }

        public Long getProcessing() {
            return processing;
        }

        public void setProcessing(Long processing) {
            this.processing = processing;
        }

        public Long getRetry() {
            return retry;
        }

        public void setRetry(Long retry) {
            this.retry = retry;
        }
    }
}
