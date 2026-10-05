package io.github.insanedwayne.keycloak.emaildomainpolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal client for Keycloak's admin REST API, signed in as the bootstrap
 * admin of the master realm. Plain HTTP and JSON, so it works with any
 * Keycloak version.
 */
final class KeycloakAdmin {

    static final ObjectMapper JSON = new ObjectMapper();

    record Response(int status, String body, String location) {

        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final String url;
    private final String username;
    private final String password;
    private String token;
    private Instant tokenExpires = Instant.MIN;

    KeycloakAdmin(String url, String username, String password) {
        this.url = url.replaceAll("/+$", "");
        this.username = username;
        this.password = password;
    }

    /** Waits until Keycloak answers; it may still be starting. */
    void awaitStarted(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/realms/master")).build();
                if (http.send(request, BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // Not listening yet.
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Keycloak at " + url + " didn't start within " + timeout);
            }
            Thread.sleep(2000);
        }
    }

    Response get(String path) {
        return send("GET", path, null);
    }

    Response post(String path, Object body) {
        return send("POST", path, body);
    }

    Response put(String path, Object body) {
        return send("PUT", path, body);
    }

    Response delete(String path) {
        return send("DELETE", path, null);
    }

    /** Calls {@code /admin/realms/...} and friends; {@code path} starts after {@code /admin}. */
    private Response send(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + "/admin" + path))
                    .header("Authorization", "Bearer " + token());
            if (body == null) {
                request.method(method, BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            var response = http.send(request.build(), BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Master realm tokens live 60 seconds by default; get a new one well before that. */
    private String token() throws IOException, InterruptedException {
        if (Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        String form = "grant_type=password&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form))
                .build();
        var response = http.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Admin sign-in failed: " + response.statusCode() + " " + response.body());
        }
        token = JSON.readTree(response.body()).get("access_token").asText();
        tokenExpires = Instant.now().plusSeconds(30);
        return token;
    }
}
