CREATE TABLE IF NOT EXISTS `findings` (
	`id` text PRIMARY KEY NOT NULL,
	`session_id` text NOT NULL,
	`fingerprint` text NOT NULL,
	`current_status` text NOT NULL,
	`first_seen_run_id` text NOT NULL,
	`last_seen_run_id` text NOT NULL,
	`remote_id` text,
	`remote_thread_id` text,
	`published_at` text,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`first_seen_run_id`) REFERENCES `review_runs`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`last_seen_run_id`) REFERENCES `review_runs`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`first_seen_run_id`,`session_id`) REFERENCES `review_runs`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`last_seen_run_id`,`session_id`) REFERENCES `review_runs`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "finding_status_check" CHECK("findings"."current_status" IN ('open','resolved','withdrawn'))
);

CREATE INDEX IF NOT EXISTS `findings_session_status_idx` ON `findings` (`session_id`,`current_status`);

CREATE UNIQUE INDEX IF NOT EXISTS `finding_session_unique` ON `findings` (`id`,`session_id`);
