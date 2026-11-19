package com.agent.codereviewagent.service;

import com.agent.codereviewagent.config.DiffWaitProperties;
import com.agent.codereviewagent.utils.Sleeper;
import com.agent.codereviewagent.mapper.WorkbenchJobMapper;
import com.agent.codereviewagent.models.entity.ReviewRecordEntity;
import com.agent.codereviewagent.models.entity.WorkbenchJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Objects;
import java.util.function.Consumer;

/** 手动和 webhook 共享任务身份。只在登记时短暂串行，平台请求和模型调用不持有登记锁。 */
@Service
@DependsOnDatabaseInitialization
public class WorkbenchJobs implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchJobs.class);
    // 临时版本只用于尚在等待平台 diff 的事件，不能与任何真实提交 SHA 相同。
    private static final String AWAITING_DIFF = "#awaiting-diff";
    private final DiffWaitProperties diffWait;
    private final Sleeper sleeper;
    private final WorkbenchJobMapper jobs;
    private final RepositoryProfiles repositories;
    private final RepositoryAccess access;
    private final MergeRequestReviewService review;
    private final TaskExecutor worker;

    public WorkbenchJobs(WorkbenchJobMapper jobs, RepositoryProfiles repositories, RepositoryAccess access,
                         MergeRequestReviewService review, @Qualifier("githubWebhookExecutor") TaskExecutor worker,
                         DiffWaitProperties diffWait, Sleeper sleeper) {
        this.jobs = jobs;
        this.repositories = repositories;
        this.access = access;
        this.review = review;
        this.worker = worker;
        this.diffWait = diffWait;
        this.sleeper = sleeper;
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
        if (expectedHead != null && expectedHead.isBlank()) { return null; }
        return start(profile, number, "webhook", expectedHead);
    }

    private WorkbenchJob start(RepositoryProfile profile, long number, String source, String expectedHead) {
        if (number <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入正整数 PR/MR 编号");
        }
        RepositoryClient client;
        RepositoryClient.Change change;
        try {
            client = access.client(profile);
            change = readChange(client, profile, number);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取此仓库的 PR/MR，请核对编号和服务端访问权限");
        }
        if (expectedHead != null && !matches(change, expectedHead)) {
            var pending = new RepositoryClient.DiffRefs(expectedHead, "", AWAITING_DIFF);
            return enqueue(profile.id(), number, source, pending, job -> awaitVersion(job, profile, client));
        }
        if (change == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "合并请求的 diff 尚未就绪");
        }
        return enqueue(profile.id(), number, source, change.refs(), job -> execute(job, change));
    }

    private static RepositoryClient.Change readChange(RepositoryClient client, RepositoryProfile profile, long number) {
        try {
            var change = client.change(profile.remoteId(), number);
            return change == null || change.refs() == null || change.refs().headSha() == null
                    || change.refs().headSha().isBlank() ? null : change;
        } catch (DiffNotReadyException notReady) {
            return null;
        }
    }

    private static boolean matches(RepositoryClient.Change change, String head) {
        return change != null && head.equals(change.refs().headSha());
    }

    private void awaitVersion(WorkbenchJob job, RepositoryProfile profile, RepositoryClient client) {
        try {
            for (int attempt = 0; attempt < Math.max(1, diffWait.getMaxAttempts()); attempt++) {
                if (attempt > 0) { sleeper.sleep(diffWait.getInterval()); }
                var change = readChange(client, profile, job.number());
                if (matches(change, job.headSha())) {
                    if (bindVersion(job, change.refs())) { execute(job, change); }
                    return;
                }
            }
            jobs.finish(job.id(), "FAILED", "等待事件指定版本的 diff 超时，请核对提交后重新发起评审", Instant.now().toString());
        } catch (RuntimeException failure) {
            jobs.finish(job.id(), "FAILED", "无法读取事件指定版本的 diff，请核对平台连接后重试", Instant.now().toString());
        }
    }

    // 等待期间手动任务或另一交付可能已经认领完整版本。与入队共用登记锁。
    private synchronized boolean bindVersion(WorkbenchJob pending, RepositoryClient.DiffRefs refs) {
        String base = Objects.toString(refs.baseSha(), "");
        String start = Objects.toString(refs.startSha(), "");
        if (reusable(pending.repositoryId(), pending.number(), refs.headSha(), base, start, "webhook") != null) {
            jobs.discardDuplicate(pending.id());
            return false;
        }
        return jobs.bindVersion(pending.id(), base, start) == 1;
    }

    private WorkbenchJob reusable(long repo, long number, String head, String base, String start, String source) {
        WorkbenchJob previous = jobs.latest(repo, number, head, base, start);
        if (previous == null || previous.finishedAt() == null) { return previous; }
        if ("webhook".equals(source)) { return jobs.successful(repo, number, head, base, start); }
        return Instant.parse(previous.finishedAt()).plusSeconds(3).isAfter(Instant.now()) ? previous : null;
    }

    private synchronized WorkbenchJob enqueue(long repo, long number, String source, RepositoryClient.DiffRefs refs,
                                             Consumer<WorkbenchJob> task) {
        String base = Objects.toString(refs.baseSha(), "");
        String start = Objects.toString(refs.startSha(), "");
        WorkbenchJob previous = reusable(repo, number, refs.headSha(), base, start, source);
        if (previous != null) { return previous; }
        jobs.enqueue(repo, number, refs.headSha(), base, start, source, Instant.now().toString());
        var job = jobs.latest(repo, number, refs.headSha(), base, start);
        try {
            worker.execute(() -> task.accept(job));
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
            // 用户只看到固定说明；定位信息留在服务端，不输出请求、凭据或模型输入。
            log.error("工作台审查任务中断：jobId={}, repositoryId={}, number={}, error={}",
                    job.id(), job.repositoryId(), job.number(), failure.getClass().getSimpleName());
            jobs.finish(job.id(), "FAILED", "审查执行中断，请核对仓库配置和服务端日志", Instant.now().toString());
        }
    }
}
