package com.example.filetranslator;

import com.example.filetranslator.common.security.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class RateLimitPropertiesTest {

    @Test
    void nullRefillStrategy_defaultsToIntervally() {
        var policy = new RateLimitProperties.Policy(
                "/auth/**", "POST", 5, Duration.ofMinutes(1), null);

        assertThat(policy.effectiveRefillStrategy())
                .isEqualTo(RateLimitProperties.RefillStrategy.INTERVALLY);
    }
}
