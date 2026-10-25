CREATE TABLE IF NOT EXISTS `jobs` (
	`id` text PRIMARY KEY NOT NULL,
	`kind` text NOT NULL,
	`key` text NOT NULL,
	`project_id` text,
	`session_id` text,
	`run_id` text,
	`payload` text NOT NULL,
	`status` text NOT NULL,
	`attempts` integer DEFAULT 0 NOT NULL,
	`max_attempts` integer DEFAULT 3 NOT NULL,
	`available_at` integer NOT NULL,
	`locked_at` integer,
	`error` text,
	`created_at` integer NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`run_id`) REFERENCES `review_runs`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`,`project_id`) REFERENCES `sessions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`run_id`,`session_id`) REFERENCES `review_runs`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "job_kind_check" CHECK("jobs"."kind" IN ('review','reply','chat','summary','publish','event')),
	CONSTRAINT "job_status_check" CHECK("jobs"."status" IN ('queued','running','completed','failed')),
	CONSTRAINT "job_attempts_check" CHECK("jobs"."attempts" >= 0 AND "jobs"."max_attempts" > 0),
	CONSTRAINT "job_payload_check" CHECK(json_valid("jobs"."payload"))
);

CREATE UNIQUE INDEX IF NOT EXISTS `jobs_key_unique` ON `jobs` (`key`);

CREATE INDEX IF NOT EXISTS `jobs_claim_idx` ON `jobs` (`status`,`available_at`,`created_at`);
