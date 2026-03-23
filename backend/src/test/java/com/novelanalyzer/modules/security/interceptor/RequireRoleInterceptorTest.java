package com.novelanalyzer.modules.security.interceptor;

import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequireRoleInterceptorTest {

    private final RequireRoleInterceptor interceptor = new RequireRoleInterceptor();

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldRejectRequestDispatchWhenAuthUserMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.REQUEST);

        assertThatThrownBy(() -> interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            annotatedHandlerMethod()
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldBypassAuthorizationOnAsyncDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.ASYNC);

        assertTrue(interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            annotatedHandlerMethod()
        ));
    }

    private HandlerMethod annotatedHandlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new ProtectedController(), ProtectedController.class.getMethod("stream"));
    }

    static class ProtectedController {

        @RequireRole("ADMIN")
        public void stream() {
        }
    }
}
