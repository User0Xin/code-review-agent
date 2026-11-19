package com.agent.codereviewagent.controller;

import com.agent.codereviewagent.config.GitlabProperties;
import com.agent.codereviewagent.integration.gitlab.RestGitlabClient;
import com.agent.codereviewagent.service.DiscussionMissingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 HTTP 边界：只有读完可信列表才能将缺失对象视为已删除。 */
class GitlabRecoveryTransportTest {
    private HttpServer server;
    private RestGitlabClient client;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile boolean removeOnResolve;
    private volatile int deleteStatus = 404;
    private volatile int listStatus = 200;
    private volatile String firstPage = "[]";
    private volatile String secondPage;
    private volatile int secondPageStatus = 200;
    private volatile boolean disconnect;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4", this::respond);
        server.start();
        var settings = new GitlabProperties();
        settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        settings.setToken("test-token");
        settings.setProjectId(9);
        client = new RestGitlabClient(settings, new ObjectMapper());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void missingDiscussionUsesTheSharedRecoverySignalForAllReaders() {
        assertThatThrownBy(() -> client.listReplies(9, 3, 101)).isInstanceOf(DiscussionMissingException.class);
        assertThatThrownBy(() -> client.isDiscussionResolved(9, 3, 101)).isInstanceOf(DiscussionMissingException.class);
        assertThatThrownBy(() -> client.resolveDiscussion(9, 3, 101)).isInstanceOf(DiscussionMissingException.class);
        assertThat(requests).allMatch(request -> request.startsWith("GET "));
    }

    @Test
    void delete404IsIdempotentOnlyAfterReadingTheNoteCollection() {
        assertThatCode(() -> client.deleteNote(9, 3, 101)).doesNotThrowAnyException();
        assertThat(requests).containsExactly("DELETE /api/v4/projects/9/merge_requests/3/notes/101",
                "GET /api/v4/projects/9/merge_requests/3/notes?per_page=100&page=1");
    }

    @Test
    void resolve404ConfirmsWhetherTheLocatedDiscussionStillExists() {
        firstPage = "[{\"id\":\"thread\",\"notes\":[{\"id\":101}]}]";
        assertThatThrownBy(() -> client.resolveDiscussion(9, 3, 101))
                .hasMessageContaining("HTTP 404").isNotInstanceOf(DiscussionMissingException.class);
        removeOnResolve = true;
        assertThatThrownBy(() -> client.resolveDiscussion(9, 3, 101))
                .isInstanceOf(DiscussionMissingException.class);
    }

    @Test
    void successfulDeletionDoesNotNeedACollectionRead() {
        deleteStatus = 204;
        assertThatCode(() -> client.deleteNote(9, 3, 101)).doesNotThrowAnyException();
        assertThat(requests).hasSize(1);
    }

    @Test
    void noteOnTheSecondPageMeansDelete404MustRemainAFailure() {
        firstPage = "[{\"id\":100}]";
        secondPage = "[{\"id\":101}]";
        assertThatThrownBy(() -> client.deleteNote(9, 3, 101))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 404");
        assertThat(requests).anyMatch(request -> request.endsWith("page=2"));
    }

    @Test
    void discussionOnTheSecondPageIsNotMistakenForDeletion() {
        firstPage = "[{\"id\":\"unrelated\",\"notes\":[{\"id\":100}]}]";
        secondPage = "[{\"id\":\"thread\",\"notes\":[{\"id\":101,\"resolved\":true}]}]";
        assertThat(client.isDiscussionResolved(9, 3, 101)).isTrue();
        assertThat(requests).hasSize(2);
    }

    @Test
    void deletionCanBeConfirmedAfterMultiplePages() {
        firstPage = "[{\"id\":100}]";
        secondPage = "[{\"id\":102}]";
        assertThatCode(() -> client.deleteNote(9, 3, 101)).doesNotThrowAnyException();
        assertThat(requests).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 500, 503})
    void unreadableListsAreNeverProofOfDeletion(int status) {
        listStatus = status;
        assertThatThrownBy(() -> client.isDiscussionResolved(9, 3, 101))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(DiscussionMissingException.class);
        assertThatThrownBy(() -> client.deleteNote(9, 3, 101)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500, 503})
    void onlyDelete404MayAttemptMissingNoteRecovery(int status) {
        deleteStatus = status;
        assertThatThrownBy(() -> client.deleteNote(9, 3, 101))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + status);
        assertThat(requests).hasSize(1);
    }

    @Test
    void laterPageFailureCannotConfirmAbsence() {
        secondPage = "[]";
        secondPageStatus = 503;
        assertThatThrownBy(() -> client.isDiscussionResolved(9, 3, 101))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(DiscussionMissingException.class);
        assertThatThrownBy(() -> client.deleteNote(9, 3, 101)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transportFailureCannotConfirmAbsence() {
        disconnect = true;
        assertThatThrownBy(() -> client.isDiscussionResolved(9, 3, 101))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(DiscussionMissingException.class);
        assertThatThrownBy(() -> client.deleteNote(9, 3, 101)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "broken", "[{}]", "[{\"id\":0}]",
            "[{\"id\":\"100\"}]", "[{\"id\":100},{\"id\":100}]"})
    void invalidNoteListsCannotConfirmDeletion(String body) {
        firstPage = body;
        assertThatThrownBy(() -> client.deleteNote(9, 3, 101)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "broken", "[{}]", "[{\"id\":\"thread\",\"notes\":[]}]",
            "[{\"id\":\"thread\",\"notes\":[{}]}]",
            "[{\"id\":\"thread\",\"notes\":[{\"id\":100},{\"id\":100}]}]"})
    void invalidDiscussionListsCannotConfirmDeletion(String body) {
        firstPage = body;
        assertThatThrownBy(() -> client.isDiscussionResolved(9, 3, 101))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(DiscussionMissingException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        exchange.getRequestBody().readAllBytes();
        if (exchange.getRequestMethod().equals("DELETE")) {
            send(exchange, deleteStatus, "{}");
            return;
        }
        if (exchange.getRequestMethod().equals("PUT")) {
            if (removeOnResolve) { firstPage = "[]"; }
            send(exchange, 404, "{}");
            return;
        }
        if (disconnect) { exchange.close(); return; }
        if (!exchange.getRequestURI().getPath().matches("/api/v4/projects/9/merge_requests/3/(notes|discussions)")) {
            send(exchange, 500, "{}");
            return;
        }
        boolean pageTwo = exchange.getRequestURI().getQuery().endsWith("page=2");
        exchange.getResponseHeaders().set("X-Next-Page", !pageTwo && secondPage != null ? "2" : "");
        send(exchange, pageTwo ? secondPageStatus : listStatus, pageTwo ? secondPage : firstPage);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) { exchange.getResponseBody().write(bytes); }
        exchange.close();
    }
}
