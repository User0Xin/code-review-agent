package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.RevisionCheckpointEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RevisionCheckpointMapper extends BaseMapper<RevisionCheckpointEntity> {
    @Insert("INSERT INTO revision_checkpoint(project_id,last_finding_id,retry_pending) VALUES(#{project},#{last},#{retry}) "
            + "ON CONFLICT(project_id) DO UPDATE SET last_finding_id=excluded.last_finding_id,retry_pending=excluded.retry_pending")
    void checkpoint(@Param("project") long project, @Param("last") long last, @Param("retry") boolean retry);
}
