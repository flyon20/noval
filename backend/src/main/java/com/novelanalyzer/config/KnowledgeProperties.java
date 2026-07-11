package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.knowledge")
public class KnowledgeProperties {

    private Qdrant qdrant = new Qdrant();
    private Embedding embedding = new Embedding();
    private Index index = new Index();
    private Eval eval = new Eval();

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
        this.qdrant = qdrant;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
    }

    public Eval getEval() {
        return eval;
    }

    public void setEval(Eval eval) {
        this.eval = eval == null ? new Eval() : eval;
    }

    public static class Qdrant {

        private String baseUrl = "http://127.0.0.1:6333";
        private String collection = "novel_knowledge_chunks";
        private String memoryCollection = "noval_ai_memory";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public String getMemoryCollection() {
            return memoryCollection;
        }

        public void setMemoryCollection(String memoryCollection) {
            this.memoryCollection = memoryCollection;
        }
    }

    public static class Embedding {

        private String provider = "dashscope";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String model = "text-embedding-v4";
        private int dimension = 1024;
        private String apiKey = "";
        private String apiKeyRef = "DASHSCOPE_API_KEY";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiKeyRef() {
            return apiKeyRef;
        }

        public void setApiKeyRef(String apiKeyRef) {
            this.apiKeyRef = apiKeyRef;
        }
    }

    public static class Index {

        private int maxChapters = 10;
        private int maxActiveJobs = 1;
        private int chunkTargetChars = 1000;
        private int chunkOverlapChars = 160;
        private boolean queueEnabled = true;
        private int workerConcurrency = 2;
        private int maxRetries = 3;
        private long visibilityTimeoutSeconds = 600;
        private String retryBackoffSeconds = "30,120,600";
        private boolean rankIncrementalEnabled = true;
        private String rankIncrementalCron = "0 20 3 * * ?";
        private int rankIncrementalLimit = 500;
        private boolean chapterMissingEnabled = false;
        private String chapterMissingCron = "0 50 3 * * ?";
        private int chapterMissingLimit = 100;
        private Rabbit rabbit = new Rabbit();

        public int getMaxChapters() {
            return maxChapters;
        }

        public void setMaxChapters(int maxChapters) {
            this.maxChapters = maxChapters;
        }

        public int getMaxActiveJobs() {
            return maxActiveJobs;
        }

        public void setMaxActiveJobs(int maxActiveJobs) {
            this.maxActiveJobs = maxActiveJobs;
        }

        public int getChunkTargetChars() {
            return chunkTargetChars;
        }

        public void setChunkTargetChars(int chunkTargetChars) {
            this.chunkTargetChars = chunkTargetChars;
        }

        public int getChunkOverlapChars() {
            return chunkOverlapChars;
        }

        public void setChunkOverlapChars(int chunkOverlapChars) {
            this.chunkOverlapChars = chunkOverlapChars;
        }

        public boolean isQueueEnabled() {
            return queueEnabled;
        }

        public void setQueueEnabled(boolean queueEnabled) {
            this.queueEnabled = queueEnabled;
        }

        public int getWorkerConcurrency() {
            return workerConcurrency;
        }

        public void setWorkerConcurrency(int workerConcurrency) {
            this.workerConcurrency = workerConcurrency;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getVisibilityTimeoutSeconds() {
            return visibilityTimeoutSeconds;
        }

        public void setVisibilityTimeoutSeconds(long visibilityTimeoutSeconds) {
            this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        }

        public String getRetryBackoffSeconds() {
            return retryBackoffSeconds;
        }

        public void setRetryBackoffSeconds(String retryBackoffSeconds) {
            this.retryBackoffSeconds = retryBackoffSeconds;
        }

        public boolean isRankIncrementalEnabled() {
            return rankIncrementalEnabled;
        }

        public void setRankIncrementalEnabled(boolean rankIncrementalEnabled) {
            this.rankIncrementalEnabled = rankIncrementalEnabled;
        }

        public String getRankIncrementalCron() {
            return rankIncrementalCron;
        }

        public void setRankIncrementalCron(String rankIncrementalCron) {
            this.rankIncrementalCron = rankIncrementalCron;
        }

        public int getRankIncrementalLimit() {
            return rankIncrementalLimit;
        }

        public void setRankIncrementalLimit(int rankIncrementalLimit) {
            this.rankIncrementalLimit = rankIncrementalLimit;
        }

        public boolean isChapterMissingEnabled() {
            return chapterMissingEnabled;
        }

        public void setChapterMissingEnabled(boolean chapterMissingEnabled) {
            this.chapterMissingEnabled = chapterMissingEnabled;
        }

        public String getChapterMissingCron() {
            return chapterMissingCron;
        }

        public void setChapterMissingCron(String chapterMissingCron) {
            this.chapterMissingCron = chapterMissingCron;
        }

        public int getChapterMissingLimit() {
            return chapterMissingLimit;
        }

        public void setChapterMissingLimit(int chapterMissingLimit) {
            this.chapterMissingLimit = chapterMissingLimit;
        }

        public Rabbit getRabbit() {
            return rabbit;
        }

        public void setRabbit(Rabbit rabbit) {
            this.rabbit = rabbit == null ? new Rabbit() : rabbit;
        }

        public static class Rabbit {

            private String exchange = "noval.knowledge.index";
            private String queue = "noval.knowledge.index.book";
            private String routingKey = "knowledge.index.book";
            private String retryExchange = "noval.knowledge.index.retry";
            private String retryRoutingKeyPrefix = "knowledge.index.book.retry";
            private String deadLetterExchange = "noval.knowledge.index.dlx";
            private String deadLetterQueue = "noval.knowledge.index.book.dlq";
            private String deadLetterRoutingKey = "knowledge.index.book.dlq";

            public String getExchange() {
                return exchange;
            }

            public void setExchange(String exchange) {
                this.exchange = exchange;
            }

            public String getQueue() {
                return queue;
            }

            public void setQueue(String queue) {
                this.queue = queue;
            }

            public String getRoutingKey() {
                return routingKey;
            }

            public void setRoutingKey(String routingKey) {
                this.routingKey = routingKey;
            }

            public String getRetryExchange() {
                return retryExchange;
            }

            public void setRetryExchange(String retryExchange) {
                this.retryExchange = retryExchange;
            }

            public String getRetryRoutingKeyPrefix() {
                return retryRoutingKeyPrefix;
            }

            public void setRetryRoutingKeyPrefix(String retryRoutingKeyPrefix) {
                this.retryRoutingKeyPrefix = retryRoutingKeyPrefix;
            }

            public String getDeadLetterExchange() {
                return deadLetterExchange;
            }

            public void setDeadLetterExchange(String deadLetterExchange) {
                this.deadLetterExchange = deadLetterExchange;
            }

            public String getDeadLetterQueue() {
                return deadLetterQueue;
            }

            public void setDeadLetterQueue(String deadLetterQueue) {
                this.deadLetterQueue = deadLetterQueue;
            }

            public String getDeadLetterRoutingKey() {
                return deadLetterRoutingKey;
            }

            public void setDeadLetterRoutingKey(String deadLetterRoutingKey) {
                this.deadLetterRoutingKey = deadLetterRoutingKey;
            }
        }
    }

    public static class Eval {

        private boolean queueEnabled = true;
        private int workerConcurrency = 1;
        private int maxRetries = 3;
        private long visibilityTimeoutSeconds = 1800;
        private String retryBackoffSeconds = "30,120,600";
        private Rabbit rabbit = new Rabbit();

        public boolean isQueueEnabled() {
            return queueEnabled;
        }

        public void setQueueEnabled(boolean queueEnabled) {
            this.queueEnabled = queueEnabled;
        }

        public int getWorkerConcurrency() {
            return workerConcurrency;
        }

        public void setWorkerConcurrency(int workerConcurrency) {
            this.workerConcurrency = workerConcurrency;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getVisibilityTimeoutSeconds() {
            return visibilityTimeoutSeconds;
        }

        public void setVisibilityTimeoutSeconds(long visibilityTimeoutSeconds) {
            this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        }

        public String getRetryBackoffSeconds() {
            return retryBackoffSeconds;
        }

        public void setRetryBackoffSeconds(String retryBackoffSeconds) {
            this.retryBackoffSeconds = retryBackoffSeconds;
        }

        public Rabbit getRabbit() {
            return rabbit;
        }

        public void setRabbit(Rabbit rabbit) {
            this.rabbit = rabbit == null ? new Rabbit() : rabbit;
        }

        public static class Rabbit {
            private String exchange = "noval.knowledge.eval";
            private String queue = "noval.knowledge.eval.run";
            private String routingKey = "knowledge.eval.run";
            private String retryExchange = "noval.knowledge.eval.retry";
            private String retryRoutingKeyPrefix = "knowledge.eval.run.retry";
            private String deadLetterExchange = "noval.knowledge.eval.dlx";
            private String deadLetterQueue = "noval.knowledge.eval.run.dlq";
            private String deadLetterRoutingKey = "knowledge.eval.run.dlq";

            public String getExchange() {
                return exchange;
            }

            public void setExchange(String exchange) {
                this.exchange = exchange;
            }

            public String getQueue() {
                return queue;
            }

            public void setQueue(String queue) {
                this.queue = queue;
            }

            public String getRoutingKey() {
                return routingKey;
            }

            public void setRoutingKey(String routingKey) {
                this.routingKey = routingKey;
            }

            public String getRetryExchange() {
                return retryExchange;
            }

            public void setRetryExchange(String retryExchange) {
                this.retryExchange = retryExchange;
            }

            public String getRetryRoutingKeyPrefix() {
                return retryRoutingKeyPrefix;
            }

            public void setRetryRoutingKeyPrefix(String retryRoutingKeyPrefix) {
                this.retryRoutingKeyPrefix = retryRoutingKeyPrefix;
            }

            public String getDeadLetterExchange() {
                return deadLetterExchange;
            }

            public void setDeadLetterExchange(String deadLetterExchange) {
                this.deadLetterExchange = deadLetterExchange;
            }

            public String getDeadLetterQueue() {
                return deadLetterQueue;
            }

            public void setDeadLetterQueue(String deadLetterQueue) {
                this.deadLetterQueue = deadLetterQueue;
            }

            public String getDeadLetterRoutingKey() {
                return deadLetterRoutingKey;
            }

            public void setDeadLetterRoutingKey(String deadLetterRoutingKey) {
                this.deadLetterRoutingKey = deadLetterRoutingKey;
            }
        }
    }
}
