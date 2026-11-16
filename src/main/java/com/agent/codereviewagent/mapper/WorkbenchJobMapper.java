package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.WorkbenchJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkbenchJobMapper {
    String FIELDS = "id, repository_id AS repositoryId, change_number AS number, head_sha AS headSha, "
            + "status, source, message, created_at AS createdAt, finished_at AS finishedAt";
    String VERSION = "repository_id=#{repo} AND change_number=#{number} AND head_sha=#{head} "
            + "AND base_sha=#{base} AND start_sha=#{start}";

    @Select("SELECT " + FIELDS + " FROM workbench_job WHERE repository_id=#{repo} ORDER BY id DESC LIMIT 100")
    List<WorkbenchJob> recent(long repo);

    @Select("SELECT " + FIELDS + " FROM workbench_job WHERE " + VERSION + " ORDER BY id DESC LIMIT 1")
    WorkbenchJob latest(long repo, long number, String head, String base, String start);

    @Insert("INSERT INTO workbench_job(repository_id,change_number,head_sha,base_sha,start_sha,status,source,created_at) "
            + "VALUES(#{repo},#{number},#{head},#{base},#{start},'QUEUED',#{source},#{now})")
    void enqueue(long repo, long number, String head, String base, String start, String source, String now);

    @Update("UPDATE workbench_job SET status='RUNNING' WHERE id=#{id} AND status='QUEUED'")
    int claim(long id);

    @Update("UPDATE workbench_job SET status=#{status},message=#{message},finished_at=#{now} "
            + "WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int finish(long id, String status, String message, String now);

    @Update("UPDATE workbench_job SET status='FAILED',message='服务中断，请核对远端评论后重新发起评审',finished_at=#{now} "
            + "WHERE status IN ('QUEUED','RUNNING')")
    void interruptUnfinished(String now);
}
