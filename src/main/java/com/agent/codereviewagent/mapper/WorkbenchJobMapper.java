package com.agent.codereviewagent.mapper;

import com.agent.codereviewagent.models.entity.WorkbenchJob;
import org.apache.ibatis.annotations.Delete;
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

    @Select("SELECT " + FIELDS + " FROM workbench_job WHERE repository_id=#{repo} AND id=#{id}")
    WorkbenchJob get(long repo, long id);

    @Select("SELECT " + FIELDS + " FROM workbench_job WHERE repository_id=#{repo} AND change_number=#{number} "
            + "AND status IN ('QUEUED','RUNNING') ORDER BY id DESC LIMIT 1")
    WorkbenchJob active(long repo, long number);

    record Details(String baseSha, String startSha, Integer detailsVersion, String failureStage) {}

    @Select("SELECT base_sha AS baseSha,start_sha AS startSha,details_version AS detailsVersion,failure_stage AS failureStage "
            + "FROM workbench_job WHERE repository_id=#{repo} AND id=#{id}")
    Details details(long repo, long id);

    @Select("SELECT " + FIELDS + " FROM workbench_job WHERE " + VERSION
            + " ORDER BY (finished_at IS NULL) DESC, julianday(finished_at) DESC, id DESC LIMIT 1")
    WorkbenchJob latest(long repo, long number, String head, String base, String start);

    @Select("SELECT " + FIELDS + " FROM workbench_job WHERE " + VERSION + " AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1")
    WorkbenchJob successful(long repo, long number, String head, String base, String start);

    @Update("UPDATE workbench_job SET base_sha=#{base},start_sha=#{start} WHERE id=#{id} AND status='QUEUED'")
    int bindVersion(long id, String base, String start);

    @Delete("DELETE FROM workbench_job WHERE id=#{id} AND status='QUEUED'")
    void discardDuplicate(long id);

    @Insert("INSERT INTO workbench_job(repository_id,change_number,head_sha,base_sha,start_sha,status,source,created_at,details_version) "
            + "VALUES(#{repo},#{number},#{head},#{base},#{start},'QUEUED',#{source},#{now},1)")
    void enqueue(long repo, long number, String head, String base, String start, String source, String now);

    @Update("UPDATE workbench_job SET status='RUNNING' WHERE id=#{id} AND status='QUEUED'")
    int claim(long id);

    default int finish(long id, String status, String message, String now) {
        return finish(id, status, message, now, null);
    }

    @Update("UPDATE workbench_job SET status=#{status},message=#{message},finished_at=#{now},failure_stage=#{stage} "
            + "WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int finish(long id, String status, String message, String now, String stage);

    @Update("UPDATE workbench_job SET status='FAILED',failure_stage='INTERRUPTED',message='服务中断，请核对远端评论后重新发起评审',finished_at=#{now} "
            + "WHERE status IN ('QUEUED','RUNNING')")
    void interruptUnfinished(String now);
}
