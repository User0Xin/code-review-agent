package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审查记录的数据访问 Mapper。 */
@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecordEntity> {
}
