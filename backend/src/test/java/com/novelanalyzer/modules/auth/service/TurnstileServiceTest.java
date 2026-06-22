package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.config.CloudflareTurnstileProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class TurnstileServiceTest {

    @Test
    void shouldLogCloudflareErrorCodesWhenValidationFails(CapturedOutput output) throws Exception {
        CloudflareTurnstileProperties properties = enabledProperties();
        TurnstileService service = new TurnstileService(properties, new RestTemplateBuilder());
        RestTemplate restTemplate = extractRestTemplate(service);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(properties.getVerifyUrl()))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("response=bad-token")))
            .andRespond(withSuccess(
                "{\"success\":false,\"hostname\":\"www.panch.fun\",\"error-codes\":[\"timeout-or-duplicate\"]}",
                MediaType.APPLICATION_JSON
            ));

        assertThatThrownBy(() -> service.assertSmsSendPassed("bad-token", "203.0.113.1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请完成人机校验后再发送验证码");

        server.verify();
        assertThat(output.getAll())
            .contains("timeout-or-duplicate")
            .contains("www.panch.fun");
    }

    @Test
    void shouldReturnBusinessErrorWhenSiteverifyRequestFails(CapturedOutput output) throws Exception {
        CloudflareTurnstileProperties properties = enabledProperties();
        TurnstileService service = new TurnstileService(properties, new RestTemplateBuilder());
        RestTemplate restTemplate = extractRestTemplate(service);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(properties.getVerifyUrl()))
            .andRespond(withServerError());

        assertThatThrownBy(() -> service.assertSmsSendPassed("token", "203.0.113.1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请完成人机校验后再发送验证码");

        server.verify();
        assertThat(output.getAll()).contains("cloudflare turnstile siteverify request failed");
    }

    private CloudflareTurnstileProperties enabledProperties() {
        CloudflareTurnstileProperties properties = new CloudflareTurnstileProperties();
        properties.setEnabled(true);
        properties.setSiteKey("site-key");
        properties.setSecretKey("secret-key");
        properties.setExpectedHostname("www.panch.fun");
        return properties;
    }

    private RestTemplate extractRestTemplate(TurnstileService service) throws Exception {
        Field field = TurnstileService.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(service);
    }
}
