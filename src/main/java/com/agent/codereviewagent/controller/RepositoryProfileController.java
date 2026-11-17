package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.common.Result;
import com.agent.codereviewagent.models.dto.RepositoryRequest;
import com.agent.codereviewagent.models.dto.SkillImportRequest;
import com.agent.codereviewagent.service.RepositoryProfile;
import com.agent.codereviewagent.service.RepositoryProfiles;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 工作台只展示和修改配置，仓库身份确认与所有出站操作在服务端完成。 */
@RestController
@RequestMapping("/api")
public class RepositoryProfileController {
    private final RepositoryProfiles profiles;
    public RepositoryProfileController(RepositoryProfiles profiles) { this.profiles = profiles; }

    @GetMapping("/repositories")
    public Result<List<RepositoryProfile>> list() { return Result.success(profiles.list()); }

    @PostMapping("/repositories")
    public Result<RepositoryProfile> create(@RequestBody RepositoryRequest request) { return Result.success(profiles.create(request)); }

    @GetMapping("/repositories/{id}")
    public Result<RepositoryProfile> get(@PathVariable long id) { return Result.success(profiles.get(id)); }

    @PutMapping("/repositories/{id}")
    public Result<RepositoryProfile> update(@PathVariable long id,@RequestBody RepositoryRequest request) {
        return Result.success(profiles.update(id,request));
    }

    @GetMapping("/repositories/{id}/skills/{slot}")
    public Result<RepositoryProfile.Skill> skill(@PathVariable long id,@PathVariable String slot) {
        return Result.success(profiles.skill(id,slot));
    }

    @PutMapping("/repositories/{id}/skills/{slot}")
    public Result<RepositoryProfile> store(@PathVariable long id,@PathVariable String slot,@RequestBody SkillImportRequest request) {
        return Result.success(profiles.storeSkill(id,slot,request));
    }

    @DeleteMapping("/repositories/{id}/skills/{slot}")
    public Result<RepositoryProfile> remove(@PathVariable long id,@PathVariable String slot) {
        return Result.success(profiles.removeSkill(id,slot));
    }
}
