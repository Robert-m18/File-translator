/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security.ratelimit;

import com.example.filetranslator.common.security.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;

/**
 * Źródło kubełków limitu dla danego klucza.
 *
 * Wydzielone do interfejsu, bo magazyn stanu limitów to decyzja wdrożeniowa, a nie
 * projektowa: przy jednej instancji wystarcza pamięć procesu, przy kilku potrzebny jest
 * wspólny magazyn (Redis), inaczej każda instancja liczy limit osobno i realny próg
 * jest tylokrotnie wyższy, ile mamy instancji.
 *
 * RateLimitFilter nie wie, która implementacja jest wstrzyknięta - wybór robi
 * właściwość app.rate-limit.store.
 */
public interface BucketProvider {

    /**
     * @param key    identyfikator limitowanego klienta (np. "/auth/login|203.0.113.7")
     * @param policy reguła, według której kubełek ma zostać utworzony przy pierwszym użyciu
     */
    Bucket resolveBucket(String key, RateLimitProperties.Policy policy);

    /**
     * Wspólna definicja limitu - obie implementacje muszą tworzyć identyczne kubełki,
     * inaczej przełączenie magazynu po cichu zmieniłoby zachowanie aplikacji.
     */
    static Bandwidth toBandwidth(RateLimitProperties.Policy policy) {
        return Bandwidth.builder()
                .capacity(policy.capacity())
                // Cała pula wraca jednorazowo po upływie okna - czytelniejsze dla użytkownika
                // ("5 rejestracji na godzinę") niż odnawianie ciągłe.
                .refillIntervally(policy.capacity(), policy.period())
                .build();
    }

    static BucketConfiguration toConfiguration(RateLimitProperties.Policy policy) {
        return BucketConfiguration.builder()
                .addLimit(toBandwidth(policy))
                .build();
    }
}
