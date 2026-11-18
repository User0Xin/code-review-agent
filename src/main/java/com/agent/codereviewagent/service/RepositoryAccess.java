package com.agent.codereviewagent.service;

import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.config.GithubProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/** 用户输入只用于定位仓库；所有携带 token 的请求仍发往管理员配置的平台 API。 */
@Service
public class RepositoryAccess {
    private final RepositoryClient github;
    private final GitlabClient gitlab;
    private final GithubProperties githubSettings;
    private final GitlabProperties gitlabSettings;

    public RepositoryAccess(@Qualifier("githubClient") RepositoryClient github, GitlabClient gitlab,
                            GithubProperties githubSettings, GitlabProperties gitlabSettings) {
        this.github = github; this.gitlab = gitlab; this.githubSettings = githubSettings; this.gitlabSettings = gitlabSettings;
    }

    public record Address(String platform, String fullName, String url) {}
    public Address address(String url) {
        URI uri;
        try { uri = URI.create(url == null ? "" : url.trim()); }
        catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("无效的仓库链接"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawPath().contains("%")) {
            throw new IllegalArgumentException("仓库链接必须是受信任平台的 HTTPS 仓库地址");
        }
        var gh = URI.create(githubSettings.getBaseUrl());
        var gl = URI.create(gitlabSettings.getBaseUrl());
        String ghHost = "api.github.com".equalsIgnoreCase(gh.getHost()) ? "github.com" : gh.getHost();
        boolean isGithub = uri.getHost().equalsIgnoreCase(ghHost) && port(uri) == port(gh);
        boolean isGitlab = uri.getHost().equalsIgnoreCase(gl.getHost()) && port(uri) == port(gl);
        if (isGithub == isGitlab) { throw new IllegalArgumentException("仓库主机不在受信任平台配置中或平台不唯一"); }
        String prefix = (isGithub ? gh.getPath().replaceFirst("/api/v3/?$", "") : gl.getPath().replaceFirst("/api/v4/?$", ""))
                .replaceFirst("/$", "") + "/";
        if (!uri.getPath().startsWith(prefix)) { throw new IllegalArgumentException("仓库所在实例路径不匹配"); }
        String name = uri.getPath().substring(prefix.length()).replaceFirst("/$", "").replaceFirst("\\.git$", "");
        String[] segments = name.split("/", -1);
        if (name.length() > 250 || segments.length < 2 || (isGithub && segments.length != 2)
                || Arrays.stream(segments).anyMatch(part -> !part.matches("[A-Za-z0-9_.-]+") || part.equals("..") || part.equals("."))) {
            throw new IllegalArgumentException("请输入仓库主页链接");
        }
        return new Address(isGithub ? "github" : "gitlab", name,
                "https://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + prefix + name);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443); }
    public long resolve(Address address) {
        try {
            long id = (address.platform.equals("github") ? github : gitlab).resolveId(address.fullName);
            if (id > 0) { return id; }
        } catch (RuntimeException unavailable) {
            // 平台错误可能含内部地址或异常正文，工作台仅返回可操作的说明。
        }
        throw new PlatformUnavailable();
    }
    public static class PlatformUnavailable extends RuntimeException {
        public PlatformUnavailable() { super("无法确认仓库身份，请核对链接和服务端访问权限"); }
    }

    public RepositoryClient client(RepositoryProfile profile) {
        return (profile.platform().equals("github") ? github : gitlab).bound(profile.remoteId(), profile.fullName());
    }
}
