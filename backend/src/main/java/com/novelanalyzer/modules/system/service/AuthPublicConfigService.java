package com.novelanalyzer.modules.system.service;

import com.novelanalyzer.modules.auth.service.TurnstileService;
import com.novelanalyzer.modules.system.vo.AuthPublicConfigVO;
import org.springframework.stereotype.Service;

@Service
public class AuthPublicConfigService {

    private final TurnstileService turnstileService;

    public AuthPublicConfigService(TurnstileService turnstileService) {
        this.turnstileService = turnstileService;
    }

    public AuthPublicConfigVO getPublicConfig() {
        AuthPublicConfigVO vo = new AuthPublicConfigVO();
        boolean enabled = turnstileService.isEnabled() && turnstileService.getSiteKey() != null;
        vo.setTurnstileEnabled(enabled);
        vo.setTurnstileSiteKey(enabled ? turnstileService.getSiteKey() : null);
        return vo;
    }
}
