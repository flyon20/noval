package com.novelanalyzer.modules.auth.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class AuthSessionFlushScheduler {

    private final AuthSessionService authSessionService;

    public AuthSessionFlushScheduler(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    @Scheduled(
        initialDelayString = "${app.auth.session-activity-flush-initial-delay-millis:60000}",
        fixedDelayString = "${app.auth.session-activity-flush-interval-millis:60000}"
    )
    public void flushDirtySessions() {
        authSessionService.flushDirtySessions();
    }
}
