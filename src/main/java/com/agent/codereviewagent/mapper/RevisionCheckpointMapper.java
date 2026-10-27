package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.RevisionCheckpointEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RevisionCheckpointMapper extends BaseMapper<RevisionCheckpointEntity> {
    @Insert("INSERT INTO revision_checkpoint(project_id,last_finding_id,retry_pending) VALUES(#{project},#{last},#{retry}) "
            + "ON CONFLICT(project_id) DO UPDATE SET last_finding_id=excluded.last_finding_id,retry_pending=excluded.retry_pending,"
            + "retry_finding_ids=CASE WHEN excluded.retry_pending=0 THEN NULL ELSE retry_finding_ids END")
    void checkpoint(@Param("project") long project, @Param("last") long last, @Param("retry") boolean retry);

    @Update("UPDATE revision_checkpoint SET retry_finding_ids=#{ids} WHERE project_id=#{project}")
    void retryBasis(@Param("project") long project, @Param("ids") String ids);
}
