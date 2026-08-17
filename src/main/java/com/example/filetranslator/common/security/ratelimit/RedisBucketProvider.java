/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security.ratelimit;

import com.example.filetranslator.common.security.RateLimitProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Limity trzymane w Redisie - wspólne dla wszystkich instancji aplikacji.
 *
 * Włączane przez app.rate-limit.store=redis. Potrzebne dopiero wtedy, gdy aplikacja
 * działa w więcej niż jednej instancji: bez wspólnego magazynu każdy pod liczy limit
 * osobno, więc napastnik dostaje tyle razy więcej prób, ile jest instancji.
 *
 * Bucket4j nie realizuje tego przez odczyt-modyfikacja-zapis (który przy równoległych
 * żądaniach gubiłby część zliczeń), tylko operacjami compare-and-swap po stronie Redisa.
 * Dzięki temu limit jest dokładny nawet przy jednoczesnych żądaniach do różnych instancji.
 *
 * Klucze wygasają automatycznie (ExpirationAfterWriteStrategy), więc Redis nie zapełnia się
 * wpisami po adresach IP, które już nie wracają.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "store", havingValue = "redis")
public class RedisBucketProvider implements BucketProvider {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, byte[]> connection;
    private final ProxyManager<String> proxyManager;

    public RedisBucketProvider(RateLimitProperties properties) {
        String url = properties.redisUrl();
        if (url == null || url.isBlank()) {
            // Lepiej wywalić się przy starcie niż odkryć brak konfiguracji dopiero wtedy,
            // gdy limiter okaże się nieaktywny podczas ataku.
            throw new IllegalStateException(
                    "app.rate-limit.store=redis wymaga ustawienia app.rate-limit.redis-url");
        }

        this.redisClient = RedisClient.create(url);
        // Klucze jako tekst (czytelne przy podglądzie), wartości binarnie -
        // bucket4j serializuje stan kubełka do własnego formatu bajtowego.
        this.connection = redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));

        this.proxyManager = LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)))
                .build();

        log.info("Limity żądań: magazyn Redis ({})", url);
    }

    @Override
    public Bucket resolveBucket(String key, RateLimitProperties.Policy policy) {
        // Konfiguracja przekazana jako dostawca - użyta tylko przy pierwszym utworzeniu klucza.
        return proxyManager.builder().build(key, () -> BucketProvider.toConfiguration(policy));
    }

    @PreDestroy
    void shutdown() {
        connection.close();
        redisClient.shutdown();
    }
}
