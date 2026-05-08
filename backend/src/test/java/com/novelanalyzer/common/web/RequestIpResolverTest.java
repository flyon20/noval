package com.novelanalyzer.common.web;

import com.novelanalyzer.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIpResolverTest {

    @Test
    void shouldReturnRemoteAddrWhenProxyIsNotTrusted() {
        SecurityProperties properties = new SecurityProperties();
        properties.setTrustedProxyIps(List.of("10.0.0.1"));
        RequestIpResolver resolver = new RequestIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("172.18.0.5");
    }

    @Test
    void shouldPreferRealClientIpWhenRemoteAddrMatchesTrustedCidr() {
        SecurityProperties properties = new SecurityProperties();
        properties.setTrustedProxyIps(List.of("172.16.0.0/12"));
        RequestIpResolver resolver = new RequestIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.3");
        request.addHeader("X-Real-IP", "47.238.247.169");
        request.addHeader("X-Forwarded-For", "47.238.247.169, 172.18.0.3");

        assertThat(resolver.resolve(request)).isEqualTo("47.238.247.169");
    }

    @Test
    void shouldFallbackToForwardedForWhenRealIpHeaderMissing() {
        SecurityProperties properties = new SecurityProperties();
        properties.setTrustedProxyIps(List.of("172.16.0.0/12"));
        RequestIpResolver resolver = new RequestIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.4");
        request.addHeader("X-Forwarded-For", "47.238.247.169, 172.18.0.4");

        assertThat(resolver.resolve(request)).isEqualTo("47.238.247.169");
    }
}
