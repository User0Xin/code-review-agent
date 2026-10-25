CREATE TABLE IF NOT EXISTS `messages` (
	`id` text PRIMARY KEY NOT NULL,
	`project_id` text NOT NULL,
	`session_id` text NOT NULL,
	`finding_id` text,
	`role` text NOT NULL,
	`visibility` text NOT NULL,
	`body` text NOT NULL,
	`reply_to_message_id` text,
	`remote_comment_kind` text,
	`remote_comment_id` text,
	`created_at` text NOT NULL,
	FOREIGN KEY (`project_id`) REFERENCES `projects`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`) REFERENCES `sessions`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`finding_id`) REFERENCES `findings`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`reply_to_message_id`) REFERENCES `messages`(`id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`session_id`,`project_id`) REFERENCES `sessions`(`id`,`project_id`) ON UPDATE no action ON DELETE no action,
	FOREIGN KEY (`finding_id`,`session_id`) REFERENCES `findings`(`id`,`session_id`) ON UPDATE no action ON DELETE no action,
	CONSTRAINT "message_role_check" CHECK("messages"."role" IN ('user','assistant','system')),
	CONSTRAINT "message_visibility_check" CHECK("messages"."visibility" IN ('internal','external'))
);

CREATE INDEX IF NOT EXISTS `messages_session_created_idx` ON `messages` (`session_id`,`created_at`,`id`);

CREATE UNIQUE INDEX IF NOT EXISTS `message_remote_unique` ON `messages` (`session_id`,`remote_comment_kind`,`remote_comment_id`);

CREATE UNIQUE INDEX IF NOT EXISTS `message_session_unique` ON `messages` (`id`,`session_id`);

CREATE TRIGGER IF NOT EXISTS message_reply_owner BEFORE INSERT ON messages WHEN NEW.reply_to_message_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM messages WHERE id = NEW.reply_to_message_id AND session_id = NEW.session_id) BEGIN SELECT RAISE(ABORT, 'reply belongs to another session'); END;
