import { Body, Controller, Get, Inject, Param, Patch, Post } from "@nestjs/common";
import { experienceDecisionSchema } from "@review/contracts";
import { requireEntity } from "../../common/require-entity.js";
import { ExperienceService } from "../../configuration/experience.service.js";
import { Store } from "../../store.js";
import { Workflow } from "../../workflow/workflow.js";

@Controller("api")
export class ExperiencesController {
  constructor(
    @Inject(Store) private readonly store: Store,
    @Inject(Workflow) private readonly workflow: Workflow,
    @Inject(ExperienceService) private readonly experiences: ExperienceService,
  ) {}

  @Get("projects/:id/experiences")
  list(@Param("id") id: string) {
    requireEntity(this.store.projects.get(id));
    return this.store.experiences.listByProject(id);
  }

  @Post("projects/:id/summarize")
  summarize(@Param("id") id: string) {
    requireEntity(this.store.projects.get(id));
    return this.workflow.queueSummary(id);
  }

  @Patch("experiences/:id")
  decide(@Param("id") id: string, @Body() body: unknown) {
    return this.experiences.decide(id, experienceDecisionSchema.parse(body).status);
  }
}
