package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.PasswordChangeRequest;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import com.novelanalyzer.modules.auth.model.AuthUserEntity;
import com.novelanalyzer.modules.auth.repository.AuthRepository;
import com.novelanalyzer.modules.auth.repository.AuthSessionRepository;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.security.service.PasswordLoginRiskControlService;
import com.novelanalyzer.modules.security.service.TokenBlacklistService;
import com.novelanalyzer.common.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void changePasswordWithSmsCodeUsesCurrentUserPhoneAndRevokesOtherSessions() {
        AuthRepository authRepository = mock(AuthRepository.class);
        AuthSessionService authSessionService = mock(AuthSessionService.class);
        SmsAuthService smsAuthService = mock(SmsAuthService.class);
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        AuthService service = new AuthService(
            new AuthProperties(),
            authRepository,
            mock(SystemConfigService.class),
            mock(JwtUtils.class),
            passwordEncoder,
            mock(PasswordLoginRiskControlService.class),
            mock(TokenBlacklistService.class),
            authSessionService,
            mock(AuthSessionRepository.class),
            mock(RefreshTokenService.class),
            smsAuthService
        );

        AuthUserEntity user = new AuthUserEntity();
        user.setId(7L);
        user.setPhone("13800138000");
        user.setPassword(passwordEncoder.encode("OldPassword123"));
        user.setStatus(1);
        when(authRepository.findActiveUserById(7L)).thenReturn(Optional.of(user));

        PasswordChangeRequest request = new PasswordChangeRequest();
        request.setVerifyMode("SMS_CODE");
        request.setSmsCode("123456");
        request.setSmsOutId("out-id-001");
        request.setNewPassword("NewPassword123");

        service.changePassword(7L, "session-1", request);

        verify(smsAuthService).verifyCode("13800138000", "RESET_PASSWORD", "123456", "out-id-001", true);
        verify(authRepository).updatePasswordByUserId(eq(7L), anyString());
        verify(authSessionService).revokeOtherActiveSessions(
            7L,
            "session-1",
            AuthSessionStatus.REVOKED,
            "password changed"
        );
    }
}
