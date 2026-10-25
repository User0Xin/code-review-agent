CREATE TABLE IF NOT EXISTS `publications` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text NOT NULL,
	`session_id` text NOT NULL,
	`kind` text NOT NULL,
	`source_run_id` text,
	`source_run_finding_id` text,
	`source_message_id` text,
	`idempotency_key` text NOT NULL,
	`marker` text NOT NULL,
	`body` text NOT NULL,
	`target_json` text NOT NULL,
	`snapshot_id` text NOT NULL,
	`state` text NOT NULL,
	`remote_comment_id` text,
	`remote_thread_id` text,
	`published_at` text,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_run_id`) REFERENCES `review_runs`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_run_finding_id`) REFERENCES `review_run_findings`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_message_id`) REFERENCES `messages`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`snapshot_id`) REFERENCES `pr_snapshots`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`,`project_id`) REFERENCES `sessions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_run_id`,`session_id`) REFERENCES `review_runs`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_run_finding_id`,`session_id`) REFERENCES `review_run_findings`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`source_message_id`,`session_id`) REFERENCES `messages`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`snapshot_id`,`session_id`) REFERENCES `pr_snapshots`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "publication_source_check" CHECK(("publications"."kind" = 'summary' AND "publications"."source_run_id" IS NOT NULL AND "publications"."source_run_finding_id" IS NULL AND "publications"."source_message_id" IS NULL) OR ("publications"."kind" = 'finding' AND "publications"."source_run_id" IS NULL AND "publications"."source_run_finding_id" IS NOT NULL AND "publications"."source_message_id" IS NULL) OR ("publications"."kind" = 'message' AND "publications"."source_run_id" IS NULL AND "publications"."source_run_finding_id" IS NULL AND "publications"."source_message_id" IS NOT NULL)),
	CONSTRAINT "publication_state_check" CHECK("publications"."state" IN ('pending','published')),
	CONSTRAINT "publication_target_json_check" CHECK(json_valid("publications"."target_json"))
);

CREATE UNIQUE INDEX IF NOT EXISTS `publications_idempotency_key_unique` ON `publications` (`idempotency_key`);

CREATE UNIQUE INDEX IF NOT EXISTS `publications_marker_unique` ON `publications` (`marker`);

CREATE INDEX IF NOT EXISTS `publications_session_idx` ON `publications` (`session_id`);

CREATE TRIGGER IF NOT EXISTS publication_intent_immutable BEFORE UPDATE OF project_id, session_id, kind, source_run_id, source_run_finding_id, source_message_id, body, marker, idempotency_key, target_json, snapshot_id ON publications BEGIN SELECT RAISE(ABORT, 'publication intent is immutable'); END;
