package com.example.filetranslator;

import com.example.filetranslator.common.security.RateLimitProperties;
import com.example.filetranslator.common.security.ratelimit.BucketProvider;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class BucketProviderTest {

    @Test
    void intervally_wholeCapacityReturnsAtOnce_afterFullPeriod() {
        var clock = new MockTimeMeter();
        var policy = new RateLimitProperties.Policy(
                "/auth/**", null, 5, Duration.ofMinutes(1),
                RateLimitProperties.RefillStrategy.INTERVALLY);

        Bucket bucket = Bucket.builder()
                .addLimit(BucketProvider.toBandwidth(policy))
                .withCustomTimePrecision(clock)
                .build();

        // zużywamy cały limit
        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryConsume(1)).isTrue();
        }
        assertThat(bucket.tryConsume(1)).isFalse();

        // po 30s (połowa okna) - dalej pusto, cała pula wraca dopiero po pełnym okresie
        clock.addSeconds(30);
        assertThat(bucket.tryConsume(1)).isFalse();

        // po pełnej minucie - cała piątka wraca naraz
        clock.addSeconds(31);
        assertThat(bucket.tryConsumeAndReturnRemaining(5).isConsumed()).isTrue();
    }

    @Test
    void greedy_tokensReturnGradually() {
        var clock = new MockTimeMeter();
        var policy = new RateLimitProperties.Policy(
                "/translations", "POST", 5, Duration.ofMinutes(1),
                RateLimitProperties.RefillStrategy.GREEDY);

        Bucket bucket = Bucket.builder()
                .addLimit(BucketProvider.toBandwidth(policy))
                .withCustomTimePrecision(clock)
                .build();

        for (int i = 0; i < 5; i++) {
            bucket.tryConsume(1);
        }
        assertThat(bucket.tryConsume(1)).isFalse();

        // greedy: 1 żeton co 12s (60s / 5) - po 12s powinien wrócić dokładnie 1
        clock.addSeconds(12);
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse(); // drugiego jeszcze nie ma
    }
}
