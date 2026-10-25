CREATE TABLE IF NOT EXISTS `model_connections` (
	`id` text PRIMARY KEY NOT NULL,
	`name` text NOT NULL,
	`base_url` text NOT NULL,
	`model` text NOT NULL,
	`enabled` integer NOT NULL,
	`api_key_secret_id` text,
	`archived_at` text,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL,
	FOREIGN KEY (`api_key_secret_id`) REFERENCES `secrets`(`id`) ON UPDATE no action ON DELETE set null,
	CONSTRAINT "model_enabled_check" CHECK("model_connections"."enabled" IN (0, 1))
);
