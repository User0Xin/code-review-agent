package com.agent.codereviewagent.service;

import com.agent.codereviewagent.mapper.WorkbenchJobMapper;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.models.entity.WorkbenchJob;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** 手动和 webhook 共享任务身份。只在登记时短暂串行，平台请求和模型调用不持有登记锁。 */
@Service
@DependsOnDatabaseInitialization
public class WorkbenchJobs implements InitializingBean {
    private final WorkbenchJobMapper jobs;
    private final RepositoryProfiles repositories;
    private final RepositoryAccess access;
    private final MergeRequestReviewService review;
    private final TaskExecutor worker;

    public WorkbenchJobs(WorkbenchJobMapper jobs, RepositoryProfiles repositories, RepositoryAccess access,
                         MergeRequestReviewService review, @Qualifier("githubWebhookExecutor") TaskExecutor worker) {
        this.jobs = jobs;
        this.repositories = repositories;
        this.access = access;
        this.review = review;
        this.worker = worker;
    }

    @Override
    public void afterPropertiesSet() {
        // 进程中断时远端可能已收到部分评论，不能把旧任务当作未执行而自动重放。
        jobs.interruptUnfinished(Instant.now().toString());
    }

    public List<WorkbenchJob> list(long repositoryId) {
        repositories.get(repositoryId);
        return jobs.recent(repositoryId);
    }

    public WorkbenchJob manual(long repositoryId, long number) {
        return start(repositories.get(repositoryId), number, "manual", null);
    }

    public WorkbenchJob webhook(RepositoryProfile profile, long number, String expectedHead) {
        return start(profile, number, "webhook", expectedHead);
    }

    private WorkbenchJob start(RepositoryProfile profile, long number, String source, String expectedHead) {
        if (number <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入正整数 PR/MR 编号");
        }
        RepositoryClient.Change change;
        try {
            // 客户端绑定已登记仓库，在该仓库下解析编号；不接受浏览器传入的 API 地址或提交。
            change = access.client(profile).change(profile.remoteId(), number);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取此仓库的 PR/MR，请核对编号和服务端访问权限");
        }
        if (change == null || change.refs() == null || change.refs().headSha() == null
                || change.refs().headSha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "合并请求的 diff 尚未就绪");
        }
        if (expectedHead != null && !expectedHead.equals(change.refs().headSha())) {
            // 延迟交付的旧 webhook 不应替另一个提交发起评审。
            return null;
        }
        return enqueue(profile.id(), number, source, change);
    }

    private synchronized WorkbenchJob enqueue(long repo, long number, String source, RepositoryClient.Change change) {
        var refs = change.refs();
        String base = refs.baseSha() == null ? "" : refs.baseSha();
        String start = refs.startSha() == null ? "" : refs.startSha();
        WorkbenchJob previous = jobs.latest(repo, number, refs.headSha(), base, start);
        Instant now = Instant.now();
        if (previous != null && (previous.finishedAt() == null
                || ("webhook".equals(source) && "SUCCEEDED".equals(previous.status()))
                || Instant.parse(previous.finishedAt()).plusSeconds(3).isAfter(now))) {
            return previous;
        }
        jobs.enqueue(repo, number, refs.headSha(), base, start, source, now.toString());
        var job = jobs.latest(repo, number, refs.headSha(), base, start);
        try {
            worker.execute(() -> execute(job, change));
        } catch (TaskRejectedException full) {
            jobs.finish(job.id(), "FAILED", "审查队列已满，请稍后重试", Instant.now().toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "审查队列已满，请稍后重试");
        }
        return job;
    }

    private void execute(WorkbenchJob job, RepositoryClient.Change change) {
        if (jobs.claim(job.id()) != 1) { return; }
        try {
            ReviewRecordEntity result = review.runConfigured(job.repositoryId(), job.number(), job.source(),
                    change.description(), change.refs());
            boolean success = "SUCCESS".equals(result.getOutcome());
            String message = success
                    ? "审查完成，新增 " + result.getPublishedCount() + " 条评论"
                        + (Boolean.TRUE.equals(result.getSpecSkipped()) ? "（未选中 spec）" : "")
                    : result.getReason();
            jobs.finish(job.id(), success ? "SUCCEEDED" : "FAILED", message, Instant.now().toString());
        } catch (RuntimeException failure) {
            jobs.finish(job.id(), "FAILED", "审查执行中断，请核对仓库配置和服务端日志", Instant.now().toString());
        }
    }
}
