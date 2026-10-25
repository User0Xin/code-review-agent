CREATE TABLE IF NOT EXISTS `experiences` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text NOT NULL,
	`kind` text NOT NULL,
	`body` text NOT NULL,
	`status` text NOT NULL,
	`generated_by_job_id` text,
	`accepted_standard_version_id` text,
	`decided_at` text,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`generated_by_job_id`) REFERENCES `jobs`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`accepted_standard_version_id`) REFERENCES `project_standard_versions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`accepted_standard_version_id`,`project_id`) REFERENCES `project_standard_versions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "experience_kind_check" CHECK("experiences"."kind" IN ('experience','standard')),
	CONSTRAINT "experience_status_check" CHECK("experiences"."status" IN ('draft','accepted','rejected'))
);

CREATE INDEX IF NOT EXISTS `experiences_project_status_idx` ON `experiences` (`project_id`,`status`);

CREATE UNIQUE INDEX IF NOT EXISTS `experience_project_unique` ON `experiences` (`id`,`project_id`);
