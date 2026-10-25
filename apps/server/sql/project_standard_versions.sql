CREATE TABLE IF NOT EXISTS `project_standard_versions` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text NOT NULL,
	`version` integer NOT NULL,
	`body` text NOT NULL,
	`source_experience_id` text,
	`created_at` text NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_experience_id`) REFERENCES `experiences`(`id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "standard_version_positive" CHECK("project_standard_versions"."version" > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS `standard_version_unique` ON `project_standard_versions` (`project_id`,`version`);

CREATE UNIQUE INDEX IF NOT EXISTS `standard_project_unique` ON `project_standard_versions` (`id`,`project_id`);

CREATE TRIGGER IF NOT EXISTS project_standard_versions_immutable_update BEFORE UPDATE ON project_standard_versions BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;

CREATE TRIGGER IF NOT EXISTS project_standard_versions_immutable_delete BEFORE DELETE ON project_standard_versions BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;
