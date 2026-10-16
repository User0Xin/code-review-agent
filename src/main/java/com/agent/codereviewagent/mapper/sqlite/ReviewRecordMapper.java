package com.agent.codereviewagent.mapper.sqlite;

import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审查历史的 MyBatis-Plus Mapper。 */
@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecordEntity> {
}
