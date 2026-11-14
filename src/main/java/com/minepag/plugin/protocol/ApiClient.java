package com.minepag.plugin.protocol;

import com.minepag.plugin.polling.PollTiming;
import com.minepag.plugin.protocol.AgentProtocol.AckRequest;
import com.minepag.plugin.protocol.AgentProtocol.AckResponse;
import com.minepag.plugin.protocol.AgentProtocol.ErrorDetail;
import com.minepag.plugin.protocol.AgentProtocol.HeartbeatRequest;
import com.minepag.plugin.protocol.AgentProtocol.HeartbeatResponse;
import com.minepag.plugin.protocol.AgentProtocol.LinkRequest;
import com.minepag.plugin.protocol.AgentProtocol.LinkResponse;
import com.minepag.plugin.protocol.AgentProtocol.PairRequest;
import com.minepag.plugin.protocol.AgentProtocol.PairResponse;
import com.minepag.plugin.protocol.AgentProtocol.PullBatch;
import com.minepag.plugin.protocol.AgentProtocol.PullRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Cliente HTTP bloqueante do protocolo do agente. Chame sempre fora da thread principal. */
public interface ApiClient {
    ApiOutcome<PairResponse> pair(PairRequest request);

    ApiOutcome<PullBatch> pull(PullRequest request);

    ApiOutcome<AckResponse> ack(AckRequest request);

    ApiOutcome<HeartbeatResponse> heartbeat(HeartbeatRequest request);

    ApiOutcome<LinkResponse> link(LinkRequest request);

    /** Revoga o próprio token na MinePag (resposta 204). */
    ApiOutcome<Boolean> unpair();

    /** Libera as threads do HttpClient sem esperar as requisições em andamento. */
    void shutdown();

    Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    int MAX_RESPONSE_BYTES = 1024 * 1024;

    static ApiClient create(URI baseUrl, Supplier<Optional<String>> serverToken, String userAgent) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                // Em HTTP puro (só localhost) o HttpClient tentaria upgrade h2c, que o servidor Node derruba.
                .version("http".equals(baseUrl.getScheme()) ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                .build();
        String base = baseUrl.toString().replaceAll("/+$", "");

        return new ApiClient() {
            @Override
            public ApiOutcome<PairResponse> pair(PairRequest request) {
                return post(
                        http,
                        base + AgentProtocol.PAIR_PATH,
                        Optional.empty(),
                        userAgent,
                        request,
                        (body, clockOffset) -> AgentProtocol.parsePairResponse(body));
            }

            @Override
            public ApiOutcome<PullBatch> pull(PullRequest request) {
                return authenticatedPost(
                        AgentProtocol.PULL_PATH,
                        request,
                        (body, clockOffset) -> AgentProtocol.parsePullResponse(body).withLeasesShiftedBy(clockOffset));
            }

            @Override
            public ApiOutcome<AckResponse> ack(AckRequest request) {
                return authenticatedPost(AgentProtocol.ACK_PATH, request, (body, clockOffset) -> AgentProtocol.parseAckResponse(body));
            }

            @Override
            public ApiOutcome<HeartbeatResponse> heartbeat(HeartbeatRequest request) {
                return authenticatedPost(AgentProtocol.HEARTBEAT_PATH, request, (body, clockOffset) -> AgentProtocol.parseHeartbeatResponse(body));
            }

            @Override
            public ApiOutcome<LinkResponse> link(LinkRequest request) {
                return authenticatedPost(AgentProtocol.LINK_PATH, request, (body, clockOffset) -> AgentProtocol.parseLinkResponse(body));
            }

            @Override
            public ApiOutcome<Boolean> unpair() {
                return authenticatedPost(AgentProtocol.UNPAIR_PATH, Map.of(), (body, clockOffset) -> Boolean.TRUE);
            }

            @Override
            public void shutdown() {
                http.shutdown();
            }

            private <T> ApiOutcome<T> authenticatedPost(String path, Object body, BiFunction<String, Duration, T> parseSuccess) {
                Optional<String> token = serverToken.get();
                if (token.isEmpty()) {
                    return new ApiOutcome.HttpFailure<>(401, "unauthenticated", "Servidor não pareado.", OptionalLong.empty());
                }
                return post(http, base + path, token, userAgent, body, parseSuccess);
            }
        };
    }

    private static <T> ApiOutcome<T> post(
            HttpClient http,
            String url,
            Optional<String> token,
            String userAgent,
            Object body,
            BiFunction<String, Duration, T> parseSuccess) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(AgentProtocol.toJson(body), StandardCharsets.UTF_8));
        token.ifPresent(value -> request.header("Authorization", "Bearer " + value));

        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return new ApiOutcome.NetworkFailure<>("Resposta da API maior que o limite.");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    return new ApiOutcome.Ok<>(parseSuccess.apply(text, localMinusServerClock(response, Instant.now())));
                } catch (RuntimeException invalidBody) {
                    return new ApiOutcome.NetworkFailure<>("Resposta inválida da API: " + invalidBody.getMessage());
                }
            }
            Optional<ErrorDetail> error = AgentProtocol.parseError(text);
            return new ApiOutcome.HttpFailure<>(
                    status,
                    error.map(ErrorDetail::code).orElse("http_" + status),
                    AgentProtocol.truncate(error.map(ErrorDetail::message).orElse("HTTP " + status), 300),
                    PollTiming.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
        } catch (IOException failure) {
            return new ApiOutcome.NetworkFailure<>(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ApiOutcome.NetworkFailure<>("Requisição interrompida.");
        }
    }

    /** Diferença entre o relógio local e o da MinePag, pelo cabeçalho {@code Date}; zero se ele faltar ou for inválido. */
    private static Duration localMinusServerClock(HttpResponse<?> response, Instant localNow) {
        return response.headers().firstValue("Date").map(value -> {
            try {
                return Duration.between(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(), localNow);
            } catch (DateTimeParseException invalidDate) {
                return Duration.ZERO;
            }
        }).orElse(Duration.ZERO);
    }
}
