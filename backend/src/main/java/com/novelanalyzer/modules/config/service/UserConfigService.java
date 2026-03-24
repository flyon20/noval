package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.modules.config.dto.UserConfigUpdateRequest;
import com.novelanalyzer.modules.config.repository.UserConfigRepository;
import com.novelanalyzer.modules.config.vo.UserConfigVO;
import org.springframework.stereotype.Service;

@Service
public class UserConfigService {

    private final UserConfigRepository userConfigRepository;

    public UserConfigService(UserConfigRepository userConfigRepository) {
        this.userConfigRepository = userConfigRepository;
    }

    public String getValueForUser(Long userId, String configKey) {
        return userConfigRepository.findByUserIdAndKey(userId, configKey)
            .map(e -> e.getConfigValue())
            .orElse(null);
    }

    public UserConfigVO setValueForUser(Long userId, UserConfigUpdateRequest request) {
        userConfigRepository.saveOrUpdate(userId, request.getConfigKey(), request.getConfigValue());
        UserConfigVO vo = new UserConfigVO();
        vo.setConfigKey(request.getConfigKey());
        vo.setConfigValue(request.getConfigValue());
        return vo;
    }

    public UserConfigVO getVO(Long userId, String configKey) {
        String value = getValueForUser(userId, configKey);
        UserConfigVO vo = new UserConfigVO();
        vo.setConfigKey(configKey);
        vo.setConfigValue(value);
        return vo;
    }
}
