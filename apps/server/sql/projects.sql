CREATE TABLE IF NOT EXISTS `projects` (
	`id` text PRIMARY KEY NOT NULL,
	`name` text NOT NULL,
	`provider` text NOT NULL,
	`host` text NOT NULL,
	`repository` text NOT NULL,
	`repository_key` text NOT NULL,
	`model_connection_id` text NOT NULL,
	`standard_version` integer NOT NULL,
	`ignore_patterns_json` text NOT NULL,
	`auto_review` integer NOT NULL,
	`auto_publish` integer NOT NULL,
	`token_secret_id` text,
	`webhook_secret_id` text,
	`archived_at` text,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL,
	FOREIGN KEY (`model_connection_id`) REFERENCES `model_connections`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`token_secret_id`) REFERENCES `secrets`(`id`) ON UPDATE no action ON DELETE set null,
	FOREIGN KEY (`webhook_secret_id`) REFERENCES `secrets`(`id`) ON UPDATE no action ON DELETE set null,
	CONSTRAINT "projects_provider_check" CHECK("projects"."provider" IN ('github', 'gitlab')),
	CONSTRAINT "projects_strategy_check" CHECK("projects"."auto_review" IN (0,1) AND "projects"."auto_publish" IN (0,1)),
	CONSTRAINT "projects_ignore_json_check" CHECK(json_valid("projects"."ignore_patterns_json"))
);

CREATE UNIQUE INDEX IF NOT EXISTS `projects_repository_unique` ON `projects` (`provider`,`host`,`repository_key`);
