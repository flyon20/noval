package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.crawler")
public class CrawlerProperties {

    private String baseUrl;
    private String internalApiKey;
    private int connectTimeoutMillis;
    private int readTimeoutMillis;
    private RankBackfill rankBackfill = new RankBackfill();
    private RankCatalogSync rankCatalogSync = new RankCatalogSync();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public RankBackfill getRankBackfill() {
        return rankBackfill;
    }

    public void setRankBackfill(RankBackfill rankBackfill) {
        this.rankBackfill = rankBackfill == null ? new RankBackfill() : rankBackfill;
    }

    public RankCatalogSync getRankCatalogSync() {
        return rankCatalogSync;
    }

    public void setRankCatalogSync(RankCatalogSync rankCatalogSync) {
        this.rankCatalogSync = rankCatalogSync == null ? new RankCatalogSync() : rankCatalogSync;
    }

    public static class RankBackfill {

        private boolean enabled = true;
        private String cron = "0 15 2 * * ?";
        private String platform = "fanqie";
        private int refreshDays = 3;
        private int batchSize = 20;
        private int rankFetchCount = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public int getRefreshDays() {
            return refreshDays;
        }

        public void setRefreshDays(int refreshDays) {
            this.refreshDays = refreshDays;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getRankFetchCount() {
            return rankFetchCount;
        }

        public void setRankFetchCount(int rankFetchCount) {
            this.rankFetchCount = rankFetchCount;
        }
    }

    public static class RankCatalogSync {

        private boolean enabled = true;
        private String cron = "0 35 2 1 * ?";
        private String platform = "fanqie";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }
    }
}
