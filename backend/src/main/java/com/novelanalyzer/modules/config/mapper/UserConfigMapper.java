package com.novelanalyzer.modules.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.novelanalyzer.modules.config.model.UserConfigEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserConfigMapper extends BaseMapper<UserConfigEntity> {
}
