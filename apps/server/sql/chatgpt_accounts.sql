CREATE TABLE IF NOT EXISTS `chatgpt_accounts` (
  `id` text PRIMARY KEY NOT NULL,
  `email` text NOT NULL,
  `subject` text NOT NULL,
  `client_id` text NOT NULL UNIQUE,
  `created_at` text NOT NULL,
  `updated_at` text NOT NULL
);
