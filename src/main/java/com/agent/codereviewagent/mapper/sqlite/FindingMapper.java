package com.agent.codereviewagent.mapper.sqlite;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 发现的 MyBatis-Plus Mapper。 */
@Mapper
public interface FindingMapper extends BaseMapper<FindingEntity> {
}
