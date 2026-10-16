package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.FindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 发现的数据访问 Mapper。 */
@Mapper
public interface FindingMapper extends BaseMapper<FindingEntity> {
}
