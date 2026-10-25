import type { Job } from "./jobs.js";
import type { ModelConnection } from "./models.js";
import type { Project } from "./projects.js";
import type { Session } from "./sessions.js";

export interface WorkspaceState {
  models: ModelConnection[];
  projects: Project[];
  sessions: Session[];
  jobs: Job[];
}
