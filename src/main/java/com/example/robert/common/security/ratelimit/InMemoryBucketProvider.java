/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.security.ratelimit;

import com.example.robert.common.security.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Limity trzymane w pamięci procesu. Domyślna implementacja.
 *
 * Wystarcza przy jednej instancji aplikacji - a taki jest obecny sposób wdrożenia.
 * Zaleta wobec Redisa: zero zależności zewnętrznych, zero opóźnienia sieciowego
 * i brak dodatkowego punktu awarii na ścieżce każdego żądania do /auth/**.
 *
 * Ograniczenie jest realne i trzeba je znać: przy trzech instancjach za load balancerem
 * każda liczy własne kubełki, więc limit "10 na minutę" staje się faktycznie 30 na minutę.
 * Wtedy przełączamy app.rate-limit.store na redis.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryBucketProvider implements BucketProvider {

    /**
     * Cache zamiast zwykłej mapy: liczba kluczy rośnie z liczbą adresów IP, więc przy ataku
     * rozproszonym ConcurrentHashMap skończyłaby się OutOfMemoryError - limiter sam stałby się
     * wektorem ataku. Wpisy wygasają po godzinie bezczynności, rozmiar ma twardy limit.
     */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    public InMemoryBucketProvider() {
        log.info("Limity żądań: magazyn w pamięci procesu (poprawny tylko dla pojedynczej instancji)");
    }

    @Override
    public Bucket resolveBucket(String key, RateLimitProperties.Policy policy) {
        return buckets.get(key, k -> Bucket.builder()
                .addLimit(BucketProvider.toBandwidth(policy))
                .build());
    }
}
