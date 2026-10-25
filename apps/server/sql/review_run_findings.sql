CREATE TABLE IF NOT EXISTS `review_run_findings` (
	`id` text PRIMARY KEY NOT NULL,
	`run_id` text NOT NULL,
	`finding_id` text NOT NULL,
	`session_id` text NOT NULL,
	`verdict` text NOT NULL,
	`title` text NOT NULL,
	`body` text NOT NULL,
	`path` text NOT NULL,
	`line` integer,
	`severity` text NOT NULL,
	`category` text NOT NULL,
	`evidence` text NOT NULL,
	FOREIGN KEY (`run_id`) REFERENCES `review_runs`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`finding_id`) REFERENCES `findings`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`run_id`,`session_id`) REFERENCES `review_runs`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`finding_id`,`session_id`) REFERENCES `findings`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "assessment_verdict_check" CHECK("review_run_findings"."verdict" IN ('open','resolved','withdrawn')),
	CONSTRAINT "assessment_line_check" CHECK("review_run_findings"."line" IS NULL OR "review_run_findings"."line" > 0),
	CONSTRAINT "assessment_severity_check" CHECK("review_run_findings"."severity" IN ('high','medium','low')),
	CONSTRAINT "assessment_category_check" CHECK("review_run_findings"."category" IN ('bug','security','standard'))
);

CREATE UNIQUE INDEX IF NOT EXISTS `run_finding_unique` ON `review_run_findings` (`run_id`,`finding_id`);

CREATE UNIQUE INDEX IF NOT EXISTS `assessment_session_unique` ON `review_run_findings` (`id`,`session_id`);

CREATE TRIGGER IF NOT EXISTS review_run_findings_immutable_update BEFORE UPDATE ON review_run_findings BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;

CREATE TRIGGER IF NOT EXISTS review_run_findings_immutable_delete BEFORE DELETE ON review_run_findings BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;
