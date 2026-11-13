package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.CodingStandardRevisionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 读写编码规范修订提案及其对应的 GitLab 合并请求状态。 */
@Mapper
public interface CodingStandardRevisionMapper extends BaseMapper<CodingStandardRevisionEntity> { }
