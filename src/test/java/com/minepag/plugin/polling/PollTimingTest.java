package com.minepag.plugin.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PollTimingTest {
    @Test
    void backoffCeilingGrowsExponentiallyUpToTheMaximum() {
        long[] expectedCeilings = {5, 10, 20, 40, 80, 160, 300, 300};
        for (int failures = 1; failures <= expectedCeilings.length; failures++) {
            assertEquals(expectedCeilings[failures - 1], PollTiming.backoffSeconds(failures, () -> 1.0));
        }
        assertEquals(300, PollTiming.backoffSeconds(Integer.MAX_VALUE, () -> 1.0), "sem overflow em muitas falhas");
    }

    @Test
    void backoffUsesFullJitterWithOneSecondFloor() {
        assertEquals(0, PollTiming.backoffSeconds(0, () -> 0.5));
        assertEquals(1, PollTiming.backoffSeconds(5, () -> 0.0));
        Random random = new Random(42);
        for (int sample = 0; sample < 1000; sample++) {
            long delay = PollTiming.backoffSeconds(4, random::nextDouble);
            assertTrue(delay >= 1 && delay <= 40, "fora do intervalo: " + delay);
        }
    }

    @Test
    void retryAfterWinsOverBackoff() {
        assertEquals(120, PollTiming.delayAfterFailure(1, OptionalLong.of(120), () -> 1.0));
        assertEquals(1, PollTiming.delayAfterFailure(1, OptionalLong.of(0), () -> 1.0));
        assertEquals(10, PollTiming.delayAfterFailure(2, OptionalLong.empty(), () -> 1.0));
    }

    @Test
    void parsesRetryAfterInSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-10-12T15:00:00Z");
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(90).atOffset(ZoneOffset.UTC));

        assertEquals(OptionalLong.of(30), PollTiming.parseRetryAfter(" 30 ", now));
        assertEquals(OptionalLong.of(90), PollTiming.parseRetryAfter(httpDate, now));
        assertEquals(OptionalLong.of(0), PollTiming.parseRetryAfter(
                DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(10).atOffset(ZoneOffset.UTC)), now));
        assertEquals(OptionalLong.of(PollTiming.MAX_RETRY_AFTER_SECONDS), PollTiming.parseRetryAfter("999999999999999999999", now));
        assertEquals(OptionalLong.empty(), PollTiming.parseRetryAfter("amanhã", now));
        assertEquals(OptionalLong.empty(), PollTiming.parseRetryAfter(null, now));
    }

    @Test
    void serverIntervalCanOnlyBeSlowedDownLocally() {
        assertEquals(30, PollTiming.effectivePollSeconds(30, 10));
        assertEquals(60, PollTiming.effectivePollSeconds(30, 60));
        assertEquals(300, PollTiming.effectivePollSeconds(900, 30));
        assertEquals(5, PollTiming.effectivePollSeconds(1, 1));
    }
}
