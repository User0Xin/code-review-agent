CREATE TABLE IF NOT EXISTS `pr_snapshots` (
	`id` text PRIMARY KEY NOT NULL,
	`session_id` text NOT NULL,
	`head_sha` text NOT NULL,
	`base_sha` text NOT NULL,
	`start_sha` text,
	`title` text NOT NULL,
	`description` text NOT NULL,
	`url` text NOT NULL,
	`branch` text NOT NULL,
	`base_branch` text NOT NULL,
	`state` text NOT NULL,
	`files_json` text NOT NULL,
	`coverage_json` text NOT NULL,
	`content_hash` text NOT NULL,
	`fetched_at` text NOT NULL,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "snapshot_json_check" CHECK(json_valid("pr_snapshots"."files_json") AND json_valid("pr_snapshots"."coverage_json")),
	CONSTRAINT "snapshot_state_check" CHECK("pr_snapshots"."state" IN ('open','closed','merged'))
);

CREATE UNIQUE INDEX IF NOT EXISTS `snapshot_content_unique` ON `pr_snapshots` (`session_id`,`content_hash`);

CREATE UNIQUE INDEX IF NOT EXISTS `snapshot_session_unique` ON `pr_snapshots` (`id`,`session_id`);

CREATE TRIGGER IF NOT EXISTS pr_snapshots_immutable_update BEFORE UPDATE ON pr_snapshots BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;

CREATE TRIGGER IF NOT EXISTS pr_snapshots_immutable_delete BEFORE DELETE ON pr_snapshots BEGIN SELECT RAISE(ABORT, 'historical records are immutable'); END;
