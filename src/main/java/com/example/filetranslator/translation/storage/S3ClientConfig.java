/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.List;

/**
 * Klient S3. Wydzielony z S3ObjectStore, żeby budowa klienta i operacje na obiektach
 * były osobnymi rzeczami - tak samo jak DeepLClientConfig stoi obok DeepLTranslationProvider.
 *
 * TRZY USTAWIENIA SĄ TU NIEOCZYWISTE I KAŻDE Z NICH MA SWÓJ TRYB AWARII:
 *
 * 1. POŚWIADCZENIA PODAWANE WPROST, a nie domyślnym łańcuchem dostawców AWS. Ten łańcuch
 *    przeszukuje zmienne środowiskowe, ~/.aws/credentials, metadane instancji i kilka innych
 *    miejsc. Na maszynie deweloperskiej, na której ktoś kiedyś skonfigurował AWS CLI, aplikacja
 *    podłapałaby CUDZE poświadczenia i pisała do cudzego kubełka - działając przy tym bez
 *    jednego błędu. Jawne poświadczenia sprawiają, że źródło tożsamości widać w konfiguracji.
 *
 * 2. STYL ADRESOWANIA "PATH-STYLE" (bucket w ścieżce, nie w nazwie hosta). AWS domyślnie
 *    używa stylu wirtualnego hosta: kubelek.s3.amazonaws.com. MinIO pod adresem
 *    http://minio:9000 tego nie obsługuje - żądanie poleciałoby do kubelek.minio, czyli
 *    do nazwy, której DNS nie rozwiązuje. Objawem jest UnknownHostException wyglądający
 *    na awarię sieci, a nie na złe ustawienie klienta.
 *
 * 3. REGION JEST WYMAGANY NAWET DLA MinIO, któremu jest obojętny: SDK używa go do podpisania
 *    żądania (SigV4) i bez niego nie da się zbudować klienta.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3")
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        requireComplete(properties);

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            // Endpoint podany = usługa zgodna z S3 (MinIO). Puste = prawdziwe AWS,
            // gdzie adres wylicza SDK z regionu i nazwy kubełka.
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }

        return builder.build();
    }

    /**
     * Fail-fast na niekompletnej konfiguracji, z komunikatem złożonym WYŁĄCZNIE Z NAZW pól.
     *
     * Nie przez @Validated na StorageProperties, i to jest ta sama pułapka co przy
     * AdminProperties: BindValidationException składa komunikat z FieldError.toString(),
     * a tam siedzi rejectedValue - więc walidacja pola secretKey wypisałaby klucz tajny
     * do raportu błędu startu, czyli do konsoli i do zbieracza logów.
     *
     * Sprawdzenie jest tutaj, a nie w StorageProperties, bo tylko tutaj wiadomo, że magazyn
     * S3 jest w ogóle włączony - wariant w pamięci nie potrzebuje żadnego z tych ustawień
     * i nie ma powodu wymagać ich od kogoś, kto go nie używa.
     */
    private void requireComplete(StorageProperties properties) {
        List<String> missing = List.of(
                        entry("app.storage.bucket", properties.bucket()),
                        entry("app.storage.region", properties.region()),
                        entry("app.storage.access-key", properties.accessKey()),
                        entry("app.storage.secret-key", properties.secretKey()))
                .stream()
                .filter(e -> e.value() == null || e.value().isBlank())
                .map(Entry::name)
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Magazyn S3 jest włączony (app.storage.type=s3), ale brakuje ustawień: "
                            + String.join(", ", missing));
        }
    }

    private Entry entry(String name, String value) {
        return new Entry(name, value);
    }

    private record Entry(String name, String value) {
    }
}
