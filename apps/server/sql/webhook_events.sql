CREATE TABLE IF NOT EXISTS `webhook_events` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text,
	`provider` text,
	`delivery_id` text NOT NULL,
	`event_json` text NOT NULL,
	`received_at` integer NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "webhook_event_json_check" CHECK(json_valid("webhook_events"."event_json"))
);

CREATE UNIQUE INDEX IF NOT EXISTS `webhook_delivery_unique` ON `webhook_events` (`project_id`,`delivery_id`);
