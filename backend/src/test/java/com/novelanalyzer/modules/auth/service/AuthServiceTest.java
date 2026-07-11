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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void loginShouldReturnServiceUnavailableWhenUserLockTimesOut() {
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
        user.setPassword(passwordEncoder.encode("Password123"));
        user.setStatus(1);
        when(authRepository.findUserByPhone("13800138000")).thenReturn(Optional.of(user));
        doThrow(new CannotAcquireLockException("lock wait timeout")).when(authRepository).lockUserById(7L);

        com.novelanalyzer.modules.auth.dto.LoginRequest request = new com.novelanalyzer.modules.auth.dto.LoginRequest();
        request.setPhone("13800138000");
        request.setPassword("Password123");

        assertThatThrownBy(() -> service.login(request, "127.0.0.1"))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .satisfies(ex -> {
                com.novelanalyzer.common.exception.BusinessException businessException =
                    (com.novelanalyzer.common.exception.BusinessException) ex;
                assertThat(businessException.getResultCode()).isEqualTo(com.novelanalyzer.common.result.ResultCode.SERVICE_UNAVAILABLE);
                assertThat(businessException.getMessage()).isEqualTo("\u767b\u5f55\u8bf7\u6c42\u6b63\u5728\u6392\u961f\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
            });
    }

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
