import { DomainError } from "../common/domain-error.js";
import type { Store } from "../store.js";

export class ExperienceService {
  constructor(private readonly store: Store) {}
  decide(id: string, status: "accepted" | "rejected") {
    const store = this.store;
    const experience = store.experiences.get(id);
    if (!experience) {
      throw new DomainError("经验不存在", "not-found");
    }
    if (experience.status !== "draft") {
      throw new DomainError("这份建议已经处理", "conflict");
    }
    return store.transaction(() => {
      let acceptedVersionId: string | undefined;
      if (status === "accepted" && experience.kind === "standard") {
        const project = store.projects.get(experience.projectId);
        if (!project) {
          throw new DomainError("项目不存在", "not-found");
        }
        store.projects.save(
          {
            ...project,
            standard: [project.standard, experience.body].filter(Boolean).join("\n\n"),
            standardVersion: project.standardVersion + 1,
            updatedAt: new Date().toISOString(),
          },
          experience.id,
        );
        acceptedVersionId = store.projects.getStandardVersion(
          project.id,
          project.standardVersion + 1,
        )?.id;
      }
      return store.experiences.save(
        {
          ...experience,
          status,
          updatedAt: new Date().toISOString(),
        },
        undefined,
        acceptedVersionId,
      );
    });
  }
}
