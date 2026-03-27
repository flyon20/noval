package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.model.AuthSessionEntity;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import com.novelanalyzer.modules.auth.repository.AuthSessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AuthSessionService {

    private static final String SESSION_KEY_PREFIX = "auth:session:";
    private static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    private static final String USER_SESSIONS_KEY_PREFIX = "auth:user:sessions:";
    private static final String DIRTY_SESSION_KEY = "auth:session:dirty";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AuthSessionRepository authSessionRepository;
    private final RefreshTokenService refreshTokenService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties authProperties;

    public AuthSessionService(AuthSessionRepository authSessionRepository,
                              RefreshTokenService refreshTokenService,
                              StringRedisTemplate stringRedisTemplate,
                              AuthProperties authProperties) {
        this.authSessionRepository = authSessionRepository;
        this.refreshTokenService = refreshTokenService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.authProperties = authProperties;
    }

    public CreatedSession createSession(Long userId, String deviceLabel, String userAgent, String loginIp) {
        LocalDateTime now = LocalDateTime.now();
        String refreshToken = refreshTokenService.generateOpaqueRefreshToken();
        String refreshHash = refreshTokenService.hashRefreshToken(refreshToken);
        String sessionId = refreshTokenService.generateSessionId();
        LocalDateTime refreshExpireTime = now.plusSeconds(authProperties.getRefreshTokenExpireSeconds());

        AuthSessionEntity session = new AuthSessionEntity();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setRefreshTokenHash(refreshHash);
        session.setStatus(AuthSessionStatus.ACTIVE);
        session.setDeviceLabel(deviceLabel);
        session.setUserAgent(userAgent);
        session.setLoginIp(loginIp);
        session.setLastActiveTime(now);
        session.setLastRefreshTime(now);
        session.setRefreshExpireTime(refreshExpireTime);

        Long id = authSessionRepository.insertSession(session);
        session.setId(id);
        cacheSessionState(session);

        return new CreatedSession(sessionId, refreshToken, refreshHash, refreshExpireTime);
    }

    public boolean revokeSession(String sessionId, int revokedStatus, String revokeReason) {
        AuthSessionEntity snapshot = readSessionFromCache(sessionId);
        if (snapshot == null) {
            snapshot = authSessionRepository.findSessionBySessionId(sessionId).orElse(null);
        }
        boolean revoked = authSessionRepository.revokeSession(sessionId, revokedStatus, revokeReason, LocalDateTime.now());
        if (revoked && snapshot != null) {
            removeSessionState(snapshot);
        }
        return revoked;
    }

    public void updateActivity(String sessionId) {
        updateActivity(sessionId, LocalDateTime.now());
    }

    public void updateActivity(String sessionId, LocalDateTime activeTime) {
        Optional<AuthSessionEntity> sessionOptional = findActiveSessionBySessionId(sessionId);
        if (sessionOptional.isEmpty()) {
            return;
        }
        AuthSessionEntity session = sessionOptional.get();
        session.setLastActiveTime(activeTime);

        try {
            String sessionKey = buildSessionKey(sessionId);
            stringRedisTemplate.opsForHash().put(sessionKey, "lastActiveTime", formatTime(activeTime));
            stringRedisTemplate.opsForZSet().add(buildUserSessionsKey(session.getUserId()), sessionId, toEpochSeconds(activeTime));
            stringRedisTemplate.opsForSet().add(DIRTY_SESSION_KEY, sessionId);
        } catch (Exception ex) {
            authSessionRepository.updateLastActiveTime(sessionId, activeTime);
        }
    }

    public Optional<AuthSessionEntity> findActiveSessionBySessionId(String sessionId) {
        AuthSessionEntity cached = readSessionFromCache(sessionId);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<AuthSessionEntity> persisted = authSessionRepository.findActiveSessionBySessionId(sessionId);
        persisted.ifPresent(this::cacheSessionState);
        return persisted;
    }

    public Optional<AuthSessionEntity> findActiveSessionByRefreshTokenHash(String refreshTokenHash) {
        try {
            String sessionId = stringRedisTemplate.opsForValue().get(buildRefreshKey(refreshTokenHash));
            if (sessionId != null && !sessionId.isBlank()) {
                return findActiveSessionBySessionId(sessionId);
            }
        } catch (Exception ignored) {
            // Fallback to MySQL on cache miss/unavailability.
        }

        Optional<AuthSessionEntity> persisted = authSessionRepository.findActiveSessionByRefreshTokenHash(refreshTokenHash);
        persisted.ifPresent(this::cacheSessionState);
        return persisted;
    }

    public Optional<AuthSessionEntity> findOldestActiveSessionForUser(Long userId) {
        return authSessionRepository.findOldestActiveSessionForUser(userId);
    }

    public Optional<AuthSessionEntity> rehydrateSessionBySessionId(String sessionId) {
        Optional<AuthSessionEntity> persisted = authSessionRepository.findActiveSessionBySessionId(sessionId);
        persisted.ifPresent(this::cacheSessionState);
        return persisted;
    }

    public void removeRefreshTokenMapping(String refreshTokenHash) {
        if (refreshTokenHash == null || refreshTokenHash.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(buildRefreshKey(refreshTokenHash));
        } catch (Exception ignored) {
            // Ignore cache delete failures.
        }
    }

    public Optional<AuthSessionEntity> rehydrateSessionByRefreshHash(String refreshTokenHash) {
        Optional<AuthSessionEntity> persisted = authSessionRepository.findActiveSessionByRefreshTokenHash(refreshTokenHash);
        persisted.ifPresent(this::cacheSessionState);
        return persisted;
    }

    public int flushDirtySessions() {
        Set<String> dirtySessionIds;
        try {
            dirtySessionIds = stringRedisTemplate.opsForSet().members(DIRTY_SESSION_KEY);
        } catch (Exception ex) {
            return 0;
        }
        if (dirtySessionIds == null || dirtySessionIds.isEmpty()) {
            return 0;
        }

        int flushed = 0;
        for (String sessionId : dirtySessionIds) {
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            if (flushSingleDirtySession(sessionId)) {
                flushed++;
            }
        }
        return flushed;
    }

    private void cacheSessionState(AuthSessionEntity session) {
        try {
            String sessionKey = buildSessionKey(session.getSessionId());
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("userId", String.valueOf(session.getUserId()));
            payload.put("status", String.valueOf(session.getStatus()));
            payload.put("deviceLabel", nullToEmpty(session.getDeviceLabel()));
            payload.put("lastActiveTime", formatTime(session.getLastActiveTime()));
            payload.put("refreshExpireTime", formatTime(session.getRefreshExpireTime()));
            payload.put("refreshTokenHash", nullToEmpty(session.getRefreshTokenHash()));
            stringRedisTemplate.opsForHash().putAll(sessionKey, payload);

            long ttl = calcTtlSeconds(session.getRefreshExpireTime());
            stringRedisTemplate.expire(sessionKey, ttl, TimeUnit.SECONDS);
            stringRedisTemplate.opsForValue().set(buildRefreshKey(session.getRefreshTokenHash()), session.getSessionId(), ttl, TimeUnit.SECONDS);
            stringRedisTemplate.opsForZSet().add(
                buildUserSessionsKey(session.getUserId()),
                session.getSessionId(),
                toEpochSeconds(session.getLastActiveTime())
            );
        } catch (Exception ignored) {
            // Best-effort cache updates; persistence source remains MySQL.
        }
    }

    private void removeSessionState(AuthSessionEntity session) {
        try {
            stringRedisTemplate.delete(buildSessionKey(session.getSessionId()));
            if (session.getRefreshTokenHash() != null && !session.getRefreshTokenHash().isBlank()) {
                stringRedisTemplate.delete(buildRefreshKey(session.getRefreshTokenHash()));
            }
            if (session.getUserId() != null) {
                stringRedisTemplate.opsForZSet().remove(buildUserSessionsKey(session.getUserId()), session.getSessionId());
            }
            stringRedisTemplate.opsForSet().remove(DIRTY_SESSION_KEY, session.getSessionId());
        } catch (Exception ignored) {
            // Ignore cache delete failures.
        }
    }

    private boolean flushSingleDirtySession(String sessionId) {
        try {
            String sessionKey = buildSessionKey(sessionId);
            Object activeTimeRaw = stringRedisTemplate.opsForHash().get(sessionKey, "lastActiveTime");
            LocalDateTime activeTime = parseTime(activeTimeRaw);

            if (activeTime == null) {
                Optional<AuthSessionEntity> persisted = authSessionRepository.findActiveSessionBySessionId(sessionId);
                if (persisted.isEmpty()) {
                    stringRedisTemplate.opsForSet().remove(DIRTY_SESSION_KEY, sessionId);
                    return false;
                }
                cacheSessionState(persisted.get());
                // Do not flush using the persisted MySQL timestamp when the hot Redis timestamp is missing.
                // Keep the session dirty so the next real activity can repopulate a fresh lastActiveTime.
                return false;
            }

            boolean updated = authSessionRepository.updateLastActiveTime(sessionId, activeTime);
            if (updated) {
                Object latestActiveTimeRaw = stringRedisTemplate.opsForHash().get(sessionKey, "lastActiveTime");
                LocalDateTime latestActiveTime = parseTime(latestActiveTimeRaw);
                if (activeTime.equals(latestActiveTime)) {
                    stringRedisTemplate.opsForSet().remove(DIRTY_SESSION_KEY, sessionId);
                }
                return true;
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private AuthSessionEntity readSessionFromCache(String sessionId) {
        try {
            String sessionKey = buildSessionKey(sessionId);
            Map<Object, Object> payload = stringRedisTemplate.opsForHash().entries(sessionKey);
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            AuthSessionEntity session = new AuthSessionEntity();
            session.setSessionId(sessionId);
            session.setUserId(parseLong(payload.get("userId")));
            session.setStatus(parseInteger(payload.get("status")));
            session.setDeviceLabel(asString(payload.get("deviceLabel")));
            session.setLastActiveTime(parseTime(payload.get("lastActiveTime")));
            session.setRefreshExpireTime(parseTime(payload.get("refreshExpireTime")));
            session.setRefreshTokenHash(asString(payload.get("refreshTokenHash")));
            if (session.getStatus() == null || session.getStatus() != AuthSessionStatus.ACTIVE) {
                return null;
            }
            if (session.getRefreshExpireTime() == null || !session.getRefreshExpireTime().isAfter(LocalDateTime.now())) {
                return null;
            }
            return session;
        } catch (Exception ex) {
            return null;
        }
    }

    private long calcTtlSeconds(LocalDateTime refreshExpireTime) {
        if (refreshExpireTime == null) {
            return Math.max(authProperties.getRefreshTokenExpireSeconds(), 1L);
        }
        long ttl = refreshExpireTime.toEpochSecond(ZoneOffset.UTC) - LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        return Math.max(ttl, 1L);
    }

    private static String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private static String buildRefreshKey(String refreshTokenHash) {
        return REFRESH_KEY_PREFIX + refreshTokenHash;
    }

    private static String buildUserSessionsKey(Long userId) {
        return USER_SESSIONS_KEY_PREFIX + userId;
    }

    private static long toEpochSeconds(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        return value.toEpochSecond(ZoneOffset.UTC);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "" : TIME_FORMATTER.format(value);
    }

    private static LocalDateTime parseTime(Object value) {
        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(text, TIME_FORMATTER);
    }

    private static Long parseLong(Object value) {
        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        return Long.valueOf(text);
    }

    private static Integer parseInteger(Object value) {
        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        return Integer.valueOf(text);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public record CreatedSession(String sessionId,
                                 String refreshToken,
                                 String refreshTokenHash,
                                 LocalDateTime refreshExpireTime) {
    }
}
