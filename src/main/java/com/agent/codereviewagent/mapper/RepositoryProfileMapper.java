package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.RepositoryProfileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/** 工作台仓库配置表。 */
@Mapper
public interface RepositoryProfileMapper extends BaseMapper<RepositoryProfileEntity> {
    /** 两个管理员同时登记同一平台身份时，只创建一次。 */
    @Insert("""
            INSERT INTO repository_profile(platform,remote_id,full_name,url,standard_paths,spec_paths)
            VALUES(#{platform},#{remoteId},#{fullName},#{url},#{standardPaths},#{specPaths})
            ON CONFLICT DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int register(RepositoryProfileEntity entity);
}
