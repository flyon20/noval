package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.model.AuthSessionEntity;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import com.novelanalyzer.modules.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private AuthSessionService authSessionService;

    @BeforeEach
    void setUp() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setRefreshTokenExpireSeconds(604800L);

        lenient().doReturn(hashOperations).when(stringRedisTemplate).opsForHash();
        lenient().doReturn(valueOperations).when(stringRedisTemplate).opsForValue();
        lenient().doReturn(zSetOperations).when(stringRedisTemplate).opsForZSet();
        lenient().doReturn(setOperations).when(stringRedisTemplate).opsForSet();

        authSessionService = new AuthSessionService(
            authSessionRepository,
            refreshTokenService,
            stringRedisTemplate,
            authProperties
        );
    }

    @Test
    void shouldCreateSessionAndCacheRedisState() {
        when(refreshTokenService.generateOpaqueRefreshToken()).thenReturn("refresh-token");
        when(refreshTokenService.hashRefreshToken("refresh-token")).thenReturn("refresh-hash");
        when(refreshTokenService.generateSessionId()).thenReturn("session-id");
        when(authSessionRepository.insertSession(any(AuthSessionEntity.class))).thenReturn(100L);

        AuthSessionService.CreatedSession created = authSessionService.createSession(
            1L,
            "Chrome on Windows",
            "UA",
            "127.0.0.1"
        );

        ArgumentCaptor<AuthSessionEntity> sessionCaptor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).insertSession(sessionCaptor.capture());
        AuthSessionEntity inserted = sessionCaptor.getValue();

        assertThat(inserted.getUserId()).isEqualTo(1L);
        assertThat(inserted.getSessionId()).isEqualTo("session-id");
        assertThat(inserted.getRefreshTokenHash()).isEqualTo("refresh-hash");
        assertThat(inserted.getStatus()).isEqualTo(AuthSessionStatus.ACTIVE);
        assertThat(created.sessionId()).isEqualTo("session-id");
        assertThat(created.refreshToken()).isEqualTo("refresh-token");

        verify(hashOperations).putAll(anyString(), any(Map.class));
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
        verify(zSetOperations, atLeastOnce()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void shouldUsePreRevokeSnapshotToClearRedisStateDeterministically() {
        AuthSessionEntity persisted = new AuthSessionEntity();
        persisted.setUserId(1L);
        persisted.setSessionId("session-id");
        persisted.setRefreshTokenHash("refresh-hash");
        persisted.setStatus(AuthSessionStatus.ACTIVE);
        persisted.setRefreshExpireTime(LocalDateTime.now().plusDays(7));

        when(hashOperations.entries("auth:session:session-id")).thenReturn(Map.of());
        when(authSessionRepository.findSessionBySessionId("session-id")).thenReturn(Optional.of(persisted));
        when(authSessionRepository.revokeSession(eq("session-id"), eq(AuthSessionStatus.REVOKED), eq("logout"), any(LocalDateTime.class)))
            .thenReturn(true);

        boolean revoked = authSessionService.revokeSession("session-id", AuthSessionStatus.REVOKED, "logout");

        assertThat(revoked).isTrue();
        verify(stringRedisTemplate).delete("auth:session:session-id");
        verify(stringRedisTemplate).delete("auth:refresh:refresh-hash");
        verify(zSetOperations).remove("auth:user:sessions:1", "session-id");
        verify(setOperations).remove("auth:session:dirty", "session-id");
    }

    @Test
    void shouldRehydrateFromMysqlOnCacheMissAndUpdateActivityThroughRedis() {
        AuthSessionEntity persisted = new AuthSessionEntity();
        persisted.setUserId(1L);
        persisted.setSessionId("session-id");
        persisted.setRefreshTokenHash("refresh-hash");
        persisted.setStatus(AuthSessionStatus.ACTIVE);
        persisted.setDeviceLabel("Chrome");
        persisted.setLastActiveTime(LocalDateTime.now().minusMinutes(5));
        persisted.setRefreshExpireTime(LocalDateTime.now().plusDays(7));

        when(hashOperations.entries("auth:session:session-id")).thenReturn(Map.of());
        when(authSessionRepository.findActiveSessionBySessionId("session-id")).thenReturn(Optional.of(persisted));

        Optional<AuthSessionEntity> rehydrated = authSessionService.rehydrateSessionBySessionId("session-id");

        assertThat(rehydrated).isPresent();
        verify(hashOperations).putAll(anyString(), any(Map.class));

        authSessionService.updateActivity("session-id", LocalDateTime.now());

        verify(hashOperations).put(anyString(), anyString(), anyString());
        verify(zSetOperations, atLeastOnce()).add(anyString(), anyString(), anyDouble());
        verify(setOperations).add("auth:session:dirty", "session-id");
        verify(authSessionRepository, never()).updateLastActiveTime(anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldKeepDirtyMarkerWhenNewerActivityArrivesDuringFlush() {
        LocalDateTime flushedTime = LocalDateTime.now().minusMinutes(1);
        LocalDateTime newerTime = flushedTime.plusSeconds(30);

        when(setOperations.members("auth:session:dirty")).thenReturn(java.util.Set.of("session-id"));
        when(hashOperations.get("auth:session:session-id", "lastActiveTime"))
            .thenReturn(flushedTime.toString())
            .thenReturn(newerTime.toString());
        when(authSessionRepository.updateLastActiveTime("session-id", flushedTime)).thenReturn(true);

        int flushed = authSessionService.flushDirtySessions();

        assertThat(flushed).isEqualTo(1);
        verify(authSessionRepository).updateLastActiveTime("session-id", flushedTime);
        verify(setOperations, never()).remove("auth:session:dirty", "session-id");
    }
}
