package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.knowledge")
public class KnowledgeProperties {

    private Qdrant qdrant = new Qdrant();
    private Embedding embedding = new Embedding();
    private Index index = new Index();

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

    public static class Qdrant {

        private String baseUrl = "http://127.0.0.1:6333";
        private String collection = "novel_knowledge_chunks";

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
    }
}
