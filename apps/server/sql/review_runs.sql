CREATE TABLE IF NOT EXISTS `review_runs` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text NOT NULL,
	`session_id` text NOT NULL,
	`snapshot_id` text NOT NULL,
	`standard_version_id` text NOT NULL,
	`model_connection_id` text NOT NULL,
	`execution_status` text NOT NULL,
	`superseded_at` text,
	`trigger` text NOT NULL,
	`summary` text NOT NULL,
	`coverage_json` text NOT NULL,
	`input_context_json` text NOT NULL,
	`error` text,
	`created_at` text NOT NULL,
	`completed_at` text,
	`published_at` text,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`snapshot_id`) REFERENCES `pr_snapshots`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`standard_version_id`) REFERENCES `project_standard_versions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`model_connection_id`) REFERENCES `model_connections`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`,`project_id`) REFERENCES `sessions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`snapshot_id`,`session_id`) REFERENCES `pr_snapshots`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`standard_version_id`,`project_id`) REFERENCES `project_standard_versions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "run_execution_check" CHECK("review_runs"."execution_status" IN ('queued','running','completed','failed','cancelled') AND ("review_runs"."execution_status" != 'cancelled' OR "review_runs"."superseded_at" IS NOT NULL)),
	CONSTRAINT "run_json_check" CHECK(json_valid("review_runs"."coverage_json") AND json_valid("review_runs"."input_context_json"))
);

CREATE INDEX IF NOT EXISTS `runs_session_created_idx` ON `review_runs` (`session_id`,`created_at`,`id`);

CREATE UNIQUE INDEX IF NOT EXISTS `run_session_unique` ON `review_runs` (`id`,`session_id`);

CREATE TRIGGER IF NOT EXISTS run_input_immutable BEFORE UPDATE OF session_id, project_id, snapshot_id, standard_version_id, model_connection_id, input_context_json ON review_runs BEGIN SELECT RAISE(ABORT, 'review input is immutable'); END;

CREATE TRIGGER IF NOT EXISTS run_result_immutable BEFORE UPDATE OF summary, coverage_json ON review_runs WHEN OLD.execution_status = 'completed' AND (NEW.summary != OLD.summary OR NEW.coverage_json != OLD.coverage_json) BEGIN SELECT RAISE(ABORT, 'completed review result is immutable'); END;
