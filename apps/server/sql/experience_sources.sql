CREATE TABLE IF NOT EXISTS `experience_sources` (
	`id` text PRIMARY KEY NOT NULL,
	`experience_id` text NOT NULL,
	`session_id` text NOT NULL,
	`project_id` text NOT NULL,
	`evidence_json` text NOT NULL,
	FOREIGN KEY (`experience_id`) REFERENCES `experiences`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`experience_id`,`project_id`) REFERENCES `experiences`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`,`project_id`) REFERENCES `sessions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "experience_evidence_json_check" CHECK(json_valid("experience_sources"."evidence_json"))
);

CREATE UNIQUE INDEX IF NOT EXISTS `experience_source_unique` ON `experience_sources` (`experience_id`,`session_id`);

CREATE TRIGGER IF NOT EXISTS experience_sources_immutable_update BEFORE UPDATE ON experience_sources BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;

CREATE TRIGGER IF NOT EXISTS experience_sources_immutable_delete BEFORE DELETE ON experience_sources BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;
