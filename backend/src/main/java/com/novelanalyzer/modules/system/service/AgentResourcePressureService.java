package com.novelanalyzer.modules.system.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AgentResourcePressureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentResourcePressureService.class);
    private static final long SNAPSHOT_CACHE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final KnowledgeProperties.ResourcePolicy policy;
    private final JdbcTemplate jdbcTemplate;
    private final ResourceProbe resourceProbe;
    private volatile CachedSnapshot cachedSnapshot;

    @Autowired
    public AgentResourcePressureService(KnowledgeProperties knowledgeProperties,
                                        JdbcTemplate jdbcTemplate) {
        this(knowledgeProperties, jdbcTemplate, new HostResourceProbe());
    }

    AgentResourcePressureService(KnowledgeProperties knowledgeProperties,
                                 JdbcTemplate jdbcTemplate,
                                 ResourceProbe resourceProbe) {
        this.policy = knowledgeProperties.getResourcePolicy();
        this.jdbcTemplate = jdbcTemplate;
        this.resourceProbe = resourceProbe;
    }

    private AgentResourcePressureService(KnowledgeProperties.ResourcePolicy policy) {
        this.policy = policy;
        this.jdbcTemplate = null;
        this.resourceProbe = () -> new ResourceUsage(0.0d, 0.0d);
    }

    public static AgentResourcePressureService permissive(KnowledgeProperties knowledgeProperties) {
        return new AgentResourcePressureService(knowledgeProperties.getResourcePolicy());
    }

    public PressureSnapshot snapshot() {
        long now = System.nanoTime();
        CachedSnapshot cached = cachedSnapshot;
        if (cached != null && now - cached.capturedAtNanos() < SNAPSHOT_CACHE_NANOS) {
            return cached.snapshot();
        }
        synchronized (this) {
            cached = cachedSnapshot;
            if (cached != null && now - cached.capturedAtNanos() < SNAPSHOT_CACHE_NANOS) {
                return cached.snapshot();
            }
            PressureSnapshot snapshot = readSnapshot();
            cachedSnapshot = new CachedSnapshot(now, snapshot);
            return snapshot;
        }
    }

    private PressureSnapshot readSnapshot() {
        ResourceUsage usage;
        try {
            usage = resourceProbe.read();
        } catch (RuntimeException ex) {
            LOGGER.debug("agent resource probe unavailable: {}", ex.getMessage());
            usage = new ResourceUsage(-1.0d, -1.0d);
        }
        QueuePressure queuePressure = readQueuePressure();
        return new PressureSnapshot(
            clampPercent(usage.memoryUsedPercent()),
            clampPercent(usage.diskUsedPercent()),
            queuePressure.backlogCount(),
            queuePressure.oldestPendingMinutes()
        );
    }

    public boolean shouldRejectDeepRun() {
        double memoryUsedPercent = snapshot().memoryUsedPercent();
        return memoryUsedPercent >= 0
            && memoryUsedPercent >= policy.getMemoryRejectDeepPercent();
    }

    public boolean shouldPauseIndexing() {
        PressureSnapshot snapshot = snapshot();
        return atOrAbove(snapshot.memoryUsedPercent(), policy.getMemoryPausePercent())
            || atOrAbove(snapshot.diskUsedPercent(), policy.getDiskStopImportPercent());
    }

    public boolean shouldSuppressLowPriorityWork() {
        PressureSnapshot snapshot = snapshot();
        return atOrAbove(snapshot.memoryUsedPercent(), policy.getMemoryPausePercent())
            || atOrAbove(snapshot.diskUsedPercent(), policy.getDiskWarnPercent())
            || snapshot.queueBacklogCount() >= policy.getQueueBacklogWarnCount()
            || snapshot.queueOldestPendingMinutes() >= policy.getQueueOldestWarnMinutes();
    }

    private QueuePressure readQueuePressure() {
        if (jdbcTemplate == null) {
            return new QueuePressure(0L, 0L);
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select count(1) as backlog_count,
                       min(coalesce(update_time, create_time)) as oldest_time
                from async_job
                where job_type = 'KNOWLEDGE_INDEX_BOOK'
                  and status = 'PENDING'
                  and coalesce(deleted, 0) = 0
                """);
            if (rows.isEmpty()) {
                return new QueuePressure(0L, 0L);
            }
            Map<String, Object> row = rows.get(0);
            long count = longValue(firstValue(row, "backlog_count", "BACKLOG_COUNT"));
            Timestamp oldest = timestampValue(firstValue(row, "oldest_time", "OLDEST_TIME"));
            long ageMinutes = oldest == null
                ? 0L
                : Math.max(0L, Duration.between(oldest.toInstant(), Instant.now()).toMinutes());
            return new QueuePressure(count, ageMinutes);
        } catch (RuntimeException ex) {
            LOGGER.debug("agent queue pressure unavailable: {}", ex.getMessage());
            return new QueuePressure(0L, 0L);
        }
    }

    private Object firstValue(Map<String, Object> values, String primary, String fallback) {
        Object value = values.get(primary);
        return value == null ? values.get(fallback) : value;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Timestamp timestampValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        return null;
    }

    private boolean atOrAbove(double value, int threshold) {
        return value >= 0 && value >= threshold;
    }

    private double clampPercent(double value) {
        if (value < 0) {
            return -1.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }

    interface ResourceProbe {
        ResourceUsage read();
    }

    record ResourceUsage(double memoryUsedPercent, double diskUsedPercent) {
    }

    public record PressureSnapshot(double memoryUsedPercent,
                                   double diskUsedPercent,
                                   long queueBacklogCount,
                                   long queueOldestPendingMinutes) {
    }

    private record QueuePressure(long backlogCount, long oldestPendingMinutes) {
    }

    private record CachedSnapshot(long capturedAtNanos, PressureSnapshot snapshot) {
    }

    private static final class HostResourceProbe implements ResourceProbe {

        @Override
        public ResourceUsage read() {
            return new ResourceUsage(memoryUsedPercent(), diskUsedPercent());
        }

        private double memoryUsedPercent() {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean systemBean) {
                long total = systemBean.getTotalMemorySize();
                long free = systemBean.getFreeMemorySize();
                if (total > 0) {
                    return 100.0d * Math.max(0L, total - free) / total;
                }
            }
            Runtime runtime = Runtime.getRuntime();
            long total = runtime.maxMemory();
            long free = runtime.freeMemory() + Math.max(0L, runtime.maxMemory() - runtime.totalMemory());
            return total <= 0 ? -1.0d : 100.0d * Math.max(0L, total - free) / total;
        }

        private double diskUsedPercent() {
            try {
                Path path = Path.of(".").toAbsolutePath().normalize();
                FileStore store = Files.getFileStore(path);
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                return total <= 0 ? -1.0d : 100.0d * Math.max(0L, total - usable) / total;
            } catch (Exception ex) {
                return -1.0d;
            }
        }
    }
}
