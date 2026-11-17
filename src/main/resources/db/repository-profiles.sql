-- 仓库平台身份与 URL 均不可重复，令牌保持在服务端连接配置中。
CREATE TABLE IF NOT EXISTS repository_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, remote_id INTEGER NOT NULL,
    full_name TEXT NOT NULL, url TEXT NOT NULL UNIQUE,
    standard_paths TEXT NOT NULL, spec_paths TEXT NOT NULL, UNIQUE(platform,remote_id)
);
CREATE TABLE IF NOT EXISTS repository_skill (
    profile_id INTEGER NOT NULL REFERENCES repository_profile(id), slot TEXT NOT NULL,
    content TEXT NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY(profile_id,slot)
);
