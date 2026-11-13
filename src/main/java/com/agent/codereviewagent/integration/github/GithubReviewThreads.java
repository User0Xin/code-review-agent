package com.agent.codereviewagent.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Review thread 的解决状态只在 GraphQL 中提供；以首条评论的 REST 编号找到持久化的讨论。 */
final class GithubReviewThreads {
    private static final String FIND = """
            query ReviewThreads($owner:String!, $repo:String!, $pr:Int!, $after:String) {
              repository(owner:$owner,name:$repo) { pullRequest(number:$pr) {
                reviewThreads(first:100,after:$after) {
                  nodes { id isResolved comments(first:1) { nodes { fullDatabaseId } } }
                  pageInfo { hasNextPage endCursor }
                }
              } }
            }
            """;
    private static final String REPLIES = """
            query ThreadReplies($id:ID!, $after:String) {
              node(id:$id) { ... on PullRequestReviewThread {
                comments(first:100,after:$after) {
                  nodes { fullDatabaseId viewerDidAuthor body }
                  pageInfo { hasNextPage endCursor }
                }
              } }
            }
            """;
    private static final String RESOLVE = """
            mutation Resolve($id:ID!) {
              resolveReviewThread(input:{threadId:$id}) { thread { isResolved } }
            }
            """;
    private final GithubApi api;

    GithubReviewThreads(GithubApi api) { this.api = api; }

    record Thread(String id, boolean resolved) { }

    Thread find(long repositoryId, long number, long commentId) {
        String[] name = api.repository(repositoryId).split("/", -1);
        ObjectNode variables = api.object().put("owner", name[0]).put("repo", name[1]).put("pr", number);
        Set<String> cursors = new HashSet<>();
        do {
            JsonNode page = api.graphql(FIND, variables).path("repository").path("pullRequest").path("reviewThreads");
            for (JsonNode thread : nodes(page)) {
                if (thread.path("comments").path("nodes").path(0).path("fullDatabaseId").asLong() == commentId) {
                    return new Thread(GithubClient.required(thread, "id"), thread.path("isResolved").asBoolean());
                }
            }
            if (!advance(page, variables, cursors)) { break; }
        } while (true);
        throw new IllegalStateException("找不到该 GitHub PR 对应的讨论");
    }

    List<String> replies(Thread thread, long root) {
        List<String> replies = new ArrayList<>();
        ObjectNode variables = api.object().put("id", thread.id());
        Set<String> cursors = new HashSet<>();
        do {
            JsonNode page = api.graphql(REPLIES, variables).path("node").path("comments");
            for (JsonNode note : nodes(page)) {
                if (note.path("fullDatabaseId").asLong() != root && !note.path("viewerDidAuthor").asBoolean()) {
                    replies.add(GithubClient.required(note, "body"));
                }
            }
            if (!advance(page, variables, cursors)) { break; }
        } while (true);
        return List.copyOf(replies);
    }

    private static JsonNode nodes(JsonNode page) {
        if (!page.path("nodes").isArray() || !page.path("pageInfo").path("hasNextPage").isBoolean()) {
            throw new IllegalStateException("GitHub 讨论分页响应不完整");
        }
        return page.get("nodes");
    }

    private static boolean advance(JsonNode page, ObjectNode variables, Set<String> seen) {
        if (!page.get("pageInfo").get("hasNextPage").asBoolean()) { return false; }
        String next = GithubClient.required(page.get("pageInfo"), "endCursor");
        if (!seen.add(next)) { throw new IllegalStateException("GitHub 讨论分页游标未前进"); }
        variables.put("after", next);
        return true;
    }

    void resolve(Thread thread) {
        if (thread.resolved()) { return; }
        if (!api.graphql(RESOLVE, api.object().put("id", thread.id()))
                .path("resolveReviewThread").path("thread").path("isResolved").asBoolean()) {
            throw new IllegalStateException("GitHub 讨论未能标记为已解决");
        }
    }
}
