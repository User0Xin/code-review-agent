import { Body, Controller, Get, Inject, Param, Patch, Post } from "@nestjs/common";
import { importPrSchema, projectInputSchema } from "@review/contracts";
import { requireEntity } from "../../common/require-entity.js";
import { ProjectService } from "../../configuration/project.service.js";
import type { RepositoryPort } from "../../ports.js";
import { Store } from "../../store.js";
import { REPOSITORY } from "../../tokens.js";
import { Workflow } from "../../workflow/workflow.js";

@Controller("api/projects")
export class ProjectsController {
  constructor(
    @Inject(ProjectService) private readonly projects: ProjectService,
    @Inject(Store) private readonly store: Store,
    @Inject(Workflow) private readonly workflow: Workflow,
    @Inject(REPOSITORY) private readonly repository: RepositoryPort,
  ) {}

  @Post()
  create(@Body() body: unknown) {
    return this.projects.save(projectInputSchema.parse(body));
  }

  @Patch(":id")
  update(@Param("id") id: string, @Body() body: unknown) {
    return this.projects.update(id, projectInputSchema.parse(body));
  }

  @Get(":id/pulls")
  listPullRequests(@Param("id") id: string) {
    const project = requireEntity(this.store.projects.get(id));
    return this.repository.listPullRequests(project, this.store.vault.get(`repo:${id}`));
  }

  @Post(":id/pulls")
  importPullRequest(@Param("id") id: string, @Body() body: unknown) {
    requireEntity(this.store.projects.get(id));
    return this.workflow.importPullRequest(id, importPrSchema.parse(body).number);
  }
}
