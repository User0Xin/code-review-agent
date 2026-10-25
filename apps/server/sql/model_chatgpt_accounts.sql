CREATE TABLE IF NOT EXISTS `model_chatgpt_accounts` (
  `model_connection_id` text PRIMARY KEY NOT NULL,
  `chatgpt_account_id` text NOT NULL,
  FOREIGN KEY (`model_connection_id`) REFERENCES `model_connections`(`id`),
  FOREIGN KEY (`chatgpt_account_id`) REFERENCES `chatgpt_accounts`(`id`)
);
