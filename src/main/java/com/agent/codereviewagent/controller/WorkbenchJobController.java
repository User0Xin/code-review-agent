package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.models.dto.StartReviewRequest;
import com.agent.codereviewagent.models.entity.WorkbenchJob;
import com.agent.codereviewagent.service.WorkbenchJobs;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/repositories/{id}")
public class WorkbenchJobController {
    private final WorkbenchJobs jobs;

    public WorkbenchJobController(WorkbenchJobs jobs) { this.jobs = jobs; }

    @GetMapping("/jobs")
    public Result<List<WorkbenchJob>> list(@PathVariable long id) {
        return Result.success(jobs.list(id));
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<WorkbenchJob> start(@PathVariable long id, @RequestBody StartReviewRequest request) {
        if (request == null || request.number() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 PR/MR 编号");
        }
        return Result.success(jobs.manual(id, request.number()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Result<Void> error(ResponseStatusException failure, jakarta.servlet.http.HttpServletResponse response) {
        int code = failure.getStatusCode().value();
        response.setStatus(code);
        return Result.error(code, failure.getReason());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> missingRepository() {
        return Result.error(404, "被审查仓库不存在");
    }
}
