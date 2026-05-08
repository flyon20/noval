package com.novelanalyzer.modules.auth.repository;

import com.novelanalyzer.modules.auth.model.AuthSessionEntity;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:authsessionrepo;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    }
)
@AutoConfigureMockMvc
@Sql(
    scripts = {"classpath:sql/phase2-schema-h2.sql", "classpath:sql/phase2-data-h2.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class AuthSessionRepositoryTest {

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSelectOldestNonExpiredActiveSessionForUser() {
        LocalDateTime now = LocalDateTime.now();
        insertActiveSession(1L, "sid-oldest", "hash-oldest", now.minusHours(3), now.plusDays(7));
        insertActiveSession(1L, "sid-middle", "hash-middle", now.minusHours(2), now.plusDays(7));
        insertActiveSession(1L, "sid-expired", "hash-expired", now.minusHours(4), now.minusMinutes(1));

        AuthSessionEntity oldest = authSessionRepository.findOldestActiveSessionForUser(1L).orElseThrow();

        assertThat(oldest.getSessionId()).isEqualTo("sid-oldest");
    }

    @Test
    void shouldTreatExpiredActiveSessionAsInactiveOnLookup() {
        LocalDateTime now = LocalDateTime.now();
        insertActiveSession(1L, "sid-expired", "hash-expired", now.minusMinutes(10), now.minusSeconds(1));

        assertThat(authSessionRepository.findActiveSessionBySessionId("sid-expired")).isEmpty();
        assertThat(authSessionRepository.findActiveSessionByRefreshTokenHash("hash-expired")).isEmpty();
    }

    private void insertActiveSession(Long userId,
                                     String sessionId,
                                     String refreshHash,
                                     LocalDateTime activeTime,
                                     LocalDateTime expireTime) {
        jdbcTemplate.update(
            """
            INSERT INTO sys_user_session (
                user_id, session_id, refresh_token_hash, status, last_active_time,
                refresh_expire_time, create_time, update_time, deleted, version
            )
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
            """,
            userId,
            sessionId,
            refreshHash,
            AuthSessionStatus.ACTIVE,
            activeTime,
            expireTime
        );
    }
}
