package com.agent.codereviewagent.integration.github;

import com.agent.codereviewagent.service.DiscussionMissingException;
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

    record Thread(String id, boolean resolved, long repositoryId, long number, long root) { }

    Thread find(long repositoryId, long number, long commentId) {
        String[] name = api.repository(repositoryId).split("/", -1);
        ObjectNode variables = api.object().put("owner", name[0]).put("repo", name[1]).put("pr", number);
        Set<String> cursors = new HashSet<>();
        do {
            JsonNode page = api.graphql(FIND, variables).path("repository").path("pullRequest").path("reviewThreads");
            for (JsonNode thread : nodes(page)) {
                JsonNode comments = thread.path("comments").path("nodes");
                if (!comments.isArray() || comments.size() != 1 || !thread.path("isResolved").isBoolean()) {
                    throw new IllegalStateException("GitHub 讨论首条评论或状态不完整");
                }
                long root = comments.get(0).path("fullDatabaseId").asLong(-1);
                if (root <= 0) { throw new IllegalStateException("GitHub 讨论首条评论缺少编号"); }
                if (root == commentId) {
                    return new Thread(GithubClient.required(thread, "id"), thread.get("isResolved").booleanValue(),
                            repositoryId, number, commentId);
                }
            }
            if (!advance(page, variables, cursors)) { break; }
        } while (true);
        // GraphQL 确认此 PR 的连接可读，且已读完全部线程。
        throw new DiscussionMissingException();
    }

    private JsonNode queryThread(Thread thread, String query, ObjectNode variables) {
        try {
            return api.graphql(query, variables);
        } catch (GithubApi.GraphqlNotFound error) {
            find(thread.repositoryId(), thread.number(), thread.root()); // 再查可读的 PR，确认是删除还是请求失败。
            throw error;
        }
    }

    List<String> replies(Thread thread) {
        List<String> replies = new ArrayList<>();
        ObjectNode variables = api.object().put("id", thread.id());
        Set<String> cursors = new HashSet<>();
        do {
            JsonNode node = queryThread(thread, REPLIES, variables).path("node");
            if (node.isNull()) {
                // 定位线程和读取回复之间也可能被删除；单独的 null 不能证明已经删除。
                find(thread.repositoryId(), thread.number(), thread.root());
            }
            JsonNode page = node.path("comments");
            for (JsonNode note : nodes(page)) {
                if (note.path("fullDatabaseId").asLong() != thread.root() && !note.path("viewerDidAuthor").asBoolean()) {
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
        if (!queryThread(thread, RESOLVE, api.object().put("id", thread.id()))
                .path("resolveReviewThread").path("thread").path("isResolved").asBoolean()) {
            throw new IllegalStateException("GitHub 讨论未能标记为已解决");
        }
    }
}
