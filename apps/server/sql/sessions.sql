CREATE TABLE IF NOT EXISTS `sessions` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text NOT NULL,
	`pr_number` integer NOT NULL,
	`title` text NOT NULL,
	`url` text NOT NULL,
	`state` text NOT NULL,
	`current_snapshot_id` text,
	`ci_status` text NOT NULL,
	`last_review_at` text,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`current_snapshot_id`) REFERENCES `pr_snapshots`(`id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "sessions_state_check" CHECK("sessions"."state" IN ('open','closed','merged')),
	CONSTRAINT "sessions_number_check" CHECK("sessions"."pr_number" > 0)
);

CREATE INDEX IF NOT EXISTS `sessions_project_idx` ON `sessions` (`project_id`);

CREATE UNIQUE INDEX IF NOT EXISTS `sessions_pr_unique` ON `sessions` (`project_id`,`pr_number`);

CREATE UNIQUE INDEX IF NOT EXISTS `sessions_project_unique` ON `sessions` (`id`,`project_id`);

CREATE TRIGGER IF NOT EXISTS session_snapshot_owner BEFORE UPDATE OF current_snapshot_id ON sessions WHEN NEW.current_snapshot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pr_snapshots WHERE id = NEW.current_snapshot_id AND session_id = NEW.id) BEGIN SELECT RAISE(ABORT, 'snapshot belongs to another session'); END;
