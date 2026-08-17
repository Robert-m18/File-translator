/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

/**
 * Magazyn zgodny z S3: MinIO lokalnie, AWS S3 na produkcji.
 *
 * JEDNA IMPLEMENTACJA NA OBA, bo MinIO mówi tym samym protokołem - różnicę robi wyłącznie
 * endpoint i styl adresowania kubełka, oba ustawiane przy budowie klienta (S3ClientConfig).
 * Dzięki temu ścieżka wykonywana lokalnie jest DOKŁADNIE tą, która pojedzie na produkcję,
 * a nie jej podobną.
 *
 * Wyjątki SDK są tłumaczone na dwa nasze typy i to rozróżnienie jest celowe: brak obiektu
 * to stan do pokazania użytkownikowi (ObjectMissingException), wszystko inne to awaria
 * infrastruktury (ObjectStoreException). Zlanie ich w jeden dawałoby 500 tam, gdzie należy
 * się czytelne "pliku już nie ma".
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3")
public class S3ObjectStore implements ObjectStore {

    /** Sufit narzucony przez API S3 na jedno żądanie DeleteObjects. */
    private static final int DELETE_BATCH = 1000;

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStore(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.bucket = properties.bucket();
        log.info("Magazyn plików: S3, kubełek {}", bucket);
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw new ObjectStoreException("Nie udało się zapisać pliku w magazynie", e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ObjectMissingException("Pliku nie ma już w magazynie");
        } catch (S3Exception e) {
            throw new ObjectStoreException("Nie udało się odczytać pliku z magazynu", e);
        }
    }

    @Override
    public StoredObject open(String key) {
        try {
            ResponseInputStream<GetObjectResponse> stream = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());

            GetObjectResponse response = stream.response();
            return new StoredObject(stream, response.contentLength(), response.contentType());
        } catch (NoSuchKeyException e) {
            throw new ObjectMissingException("Pliku nie ma już w magazynie");
        } catch (S3Exception e) {
            throw new ObjectStoreException("Nie udało się otworzyć pliku z magazynu", e);
        }
    }

    /**
     * Kopiowanie po stronie usługi - bajty NIE przechodzą przez aplikację. Przy trafieniu
     * w deduplikację to jest różnica między jednym żądaniem sterującym a pobraniem
     * i odesłaniem całego pliku.
     */
    @Override
    public void copy(String sourceKey, String targetKey) {
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(sourceKey)
                    .destinationBucket(bucket)
                    .destinationKey(targetKey)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new ObjectMissingException("Pliku do skopiowania nie ma już w magazynie");
        } catch (S3Exception e) {
            throw new ObjectStoreException("Nie udało się skopiować pliku w magazynie", e);
        }
    }

    /**
     * Kasowanie po prefiksie: wylistowanie plus DeleteObjects paczkami po 1000.
     *
     * S3 nie ma katalogów, więc "skasuj folder" nie istnieje jako operacja - trzeba wymienić
     * klucze. Paginacja jest obowiązkowa nawet tutaj, gdzie pod prefiksem zlecenia leżą
     * najwyżej dwa obiekty: przy powtórzonym tłumaczeniu innego formatu byłoby ich więcej,
     * a wersja "pobierz pierwszą stronę i skasuj" cicho zostawiałaby resztę.
     */
    @Override
    public void deletePrefix(String prefix) {
        try {
            List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH);

            for (S3Object object : s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build()).contents()) {

                batch.add(ObjectIdentifier.builder().key(object.key()).build());
                if (batch.size() == DELETE_BATCH) {
                    deleteBatch(batch);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                deleteBatch(batch);
            }
        } catch (S3Exception e) {
            throw new ObjectStoreException("Nie udało się usunąć plików z magazynu", e);
        }
    }

    private void deleteBatch(List<ObjectIdentifier> keys) {
        s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(keys).build())
                .build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HeadObject na nieistniejącym kluczu bywa zgłaszane jako gołe 404 zamiast
            // NoSuchKeyException - odpowiedź HEAD nie ma ciała, więc SDK nie ma z czego
            // odczytać kodu błędu. Bez tej gałęzi sprawdzenie istnienia rzucałoby awarię
            // magazynu dla pliku, którego po prostu nie ma.
            if (e.statusCode() == 404) {
                return false;
            }
            throw new ObjectStoreException("Nie udało się sprawdzić pliku w magazynie", e);
        }
    }
}
