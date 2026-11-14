package com.minepag.plugin.polling;

import com.minepag.plugin.protocol.AgentProtocol;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.OptionalLong;
import java.util.function.DoubleSupplier;

public final class PollTiming {
    private PollTiming() {}

    public static final long BACKOFF_BASE_SECONDS = 5;
    public static final long MAX_BACKOFF_SECONDS = 300;
    public static final long MAX_RETRY_AFTER_SECONDS = 3600;
    private static final int MAX_BACKOFF_EXPONENT = 16;

    public static long clampPollSeconds(long seconds) {
        return Math.clamp(seconds, AgentProtocol.MIN_POLL_SECONDS, AgentProtocol.MAX_POLL_SECONDS);
    }

    /** O servidor pode pedir intervalos maiores; o lojista só pode deixar o plugin mais lento, nunca mais rápido. */
    public static long effectivePollSeconds(long serverSeconds, long configuredSeconds) {
        return clampPollSeconds(Math.max(serverSeconds, configuredSeconds));
    }

    /** Backoff exponencial com full jitter: aleatório em [1, min(máximo, base · 2^(falhas−1))]. */
    public static long backoffSeconds(int consecutiveFailures, DoubleSupplier random) {
        if (consecutiveFailures <= 0) return 0;
        int exponent = Math.min(consecutiveFailures - 1, MAX_BACKOFF_EXPONENT);
        long ceiling = Math.min(MAX_BACKOFF_SECONDS, BACKOFF_BASE_SECONDS << exponent);
        return Math.max(1, Math.round(random.getAsDouble() * ceiling));
    }

    public static long delayAfterFailure(int consecutiveFailures, OptionalLong retryAfterSeconds, DoubleSupplier random) {
        long backoff = backoffSeconds(Math.max(1, consecutiveFailures), random);
        return retryAfterSeconds.isPresent() ? Math.max(1, retryAfterSeconds.getAsLong()) : backoff;
    }

    /** Aceita os dois formatos do cabeçalho: segundos ou data HTTP (RFC 1123). */
    public static OptionalLong parseRetryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return OptionalLong.empty();
        String value = header.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return OptionalLong.of(Math.min(Long.parseLong(value), MAX_RETRY_AFTER_SECONDS));
            } catch (NumberFormatException overflow) {
                return OptionalLong.of(MAX_RETRY_AFTER_SECONDS);
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long seconds = Duration.between(now, retryAt).toSeconds();
            return OptionalLong.of(Math.clamp(seconds, 0, MAX_RETRY_AFTER_SECONDS));
        } catch (DateTimeParseException invalidDate) {
            return OptionalLong.empty();
        }
    }
}
