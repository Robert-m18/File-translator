package com.example.filetranslator;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * Podnosi silniki, na których chodzi produkcja - PostgreSQL, Redis i MinIO - raz na przebieg
 * testów i wskazuje na nie konfigurację Springa.
 *
 * DLACZEGO TO ISTNIEJE. Wcześniej ten sam zestaw testów uruchamiał się w CI DWA RAZY: raz na H2
 * i kubełkach w pamięci, raz w osobnym jobie na kontenerach usług GitHub Actions, z konfiguracją
 * podmienianą zmiennymi środowiskowymi. Drugi przebieg był jedynym miejscem, gdzie w ogóle
 * wykonywały się migracje Liquibase na docelowym silniku, FOR UPDATE SKIP LOCKED, porównania
 * tekstu z rozróżnianiem wielkości liter, RedisBucketProvider i S3ObjectStore - i jedynym,
 * którego NIE dawało się uruchomić lokalnie bez ręcznego stawiania infrastruktury i wyklikiwania
 * zmiennych z pliku ci.yml. Testcontainers robi to samo, tylko że sam i wszędzie tak samo.
 *
 * JAK TO WCHODZI DO KONFIGURACJI - PRZEZ WŁAŚCIWOŚCI SYSTEMOWE, A NIE PRZEZ DRUGI PROFIL.
 * To dokładnie ta decyzja, którą wcześniej zapisano w ci.yml dla zmiennych środowiskowych:
 * @ActiveProfiles("test") jest wpisane na sztywno w każdej klasie testowej, a drugi plik
 * z konfiguracją rozjeżdżałby się z pierwszym przy każdej zmianie. Właściwości systemowe stoją
 * w kolejności pierwszeństwa Springa WYŻEJ niż application-test.yml, więc podmieniają tylko to,
 * co ma być inne, i dziedziczą całą resztę. Testy nie wiedzą o tej klasie ani jednym importem.
 *
 * DLACZEGO LauncherSessionListener, A NIE @DynamicPropertySource czy @Testcontainers.
 * Tamte trzeba dopisać do KAŻDEJ klasy testowej (jest ich blisko czterdzieści) albo do wspólnej
 * klasy bazowej, po której musiałyby dziedziczyć wszystkie - a klasa, która o tym zapomni, po cichu
 * wstanie na H2 z application-test.yml i przejdzie, sprawdzając nie ten silnik. Nasłuchiwacz sesji
 * JUnita wykonuje się RAZ, przed odkryciem jakiegokolwiek testu, i obejmuje cały przebieg bez
 * jednej adnotacji. Rejestracja idzie przez ServiceLoader:
 * META-INF/services/org.junit.platform.launcher.LauncherSessionListener.
 *
 * JEDEN KOMPLET KONTENERÓW NA CAŁY PRZEBIEG, nie jeden na klasę. Spring cache'uje konteksty
 * testowe i nie zamyka ich do końca JVM-a, więc kontenerów per klasa byłoby kilkanaście naraz.
 * Konsekwencja do zapamiętania: baza, Redis i kubełek są WSPÓLNE dla wszystkich klas - dokładnie
 * tak, jak było w jobie "integration", i dlatego przenosi się to bez zmian w testach.
 *
 * Kontenery NIE są tu zatrzymywane. Robi to Ryuk (kontener-sprzątacz Testcontainers), który
 * kasuje je po śmierci JVM-a także wtedy, gdy przebieg zostanie przerwany - a jawne zatrzymanie
 * na zamknięciu sesji ścigałoby się z zamykaniem pul połączeń w cache'owanych kontekstach Springa
 * i dosypywało błędów do logu na sam koniec zielonego buildu.
 *
 * Ponowne używanie kontenerów jest świadomie wyłączone. Zostawia ono kontenery przy życiu
 * między przebiegami, co dla pamięci podręcznej limitera jest szkodliwe: kubełki mają termin
 * ważności i przeżywają przebieg, więc testy limitera zaczynają dostawać odmowę z powodu limitu
 * tam, gdzie oczekują odmowy uwierzytelnienia - objaw wygląda jak regresja limitera, a nie jak
 * pozostałość po poprzednim uruchomieniu.
 */
public class TestInfrastructure implements LauncherSessionListener {

    /**
     * Wartość "h2" (ustawiana przez profil mavenowy -Ph2) wyłącza kontenery i zostawia
     * konfigurację z application-test.yml, czyli H2 w pamięci. Szybka pętla lokalna bez Dockera;
     * CI tej ścieżki nie używa, więc zielone -Ph2 nie jest obietnicą zielonego CI.
     */
    private static final String ENGINE_PROPERTY = "test.database";

    private static final String BUCKET = "file-translator";

    /** Wersje obrazów takie same jak w docker-compose.yml - testy mają sprawdzać to, co stoi lokalnie. */
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String MINIO_IMAGE = "minio/minio:latest";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if ("h2".equalsIgnoreCase(System.getProperty(ENGINE_PROPERTY))) {
            return;
        }

        PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("userapitest")
                .withUsername("postgres")
                .withPassword("postgres");
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        MinIOContainer minio = new MinIOContainer(MINIO_IMAGE)
                .withUserName("minioadmin")
                .withPassword("minioadmin");

        // Równolegle, bo są od siebie niezależne, a start trzech obrazów po kolei to jedyny
        // realny koszt tego rozwiązania w stosunku do H2.
        Startables.deepStart(postgres, redis, minio).join();

        createBucket(minio);

        System.setProperty("spring.datasource.url", postgres.getJdbcUrl());
        System.setProperty("spring.datasource.username", postgres.getUsername());
        System.setProperty("spring.datasource.password", postgres.getPassword());
        // Jawnie, bo application-test.yml wskazuje sterownik H2 - sama zmiana URL-a
        // zostawiłaby ustawiony org.h2.Driver.
        System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");

        // RedisBucketProvider łączy się w konstruktorze, więc samo podniesienie kontekstu
        // sprawdza konfigurację połączenia; RateLimitTest sprawdza potem liczenie kubełków.
        System.setProperty("app.rate-limit.store", "redis");
        System.setProperty("app.rate-limit.redis-url",
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));

        // Magazyn plików na prawdziwym S3 (MinIO) zamiast wariantu w pamięci - jedyne miejsce,
        // gdzie S3ObjectStore jest w ogóle wykonywany.
        System.setProperty("app.storage.type", "s3");
        System.setProperty("app.storage.endpoint", minio.getS3URL());
        System.setProperty("app.storage.bucket", BUCKET);
        System.setProperty("app.storage.region", "eu-central-1");
        System.setProperty("app.storage.access-key", minio.getUserName());
        System.setProperty("app.storage.secret-key", minio.getPassword());
    }

    /**
     * Kubełek zakładamy tutaj, tak jak robi to usługa minio-init w docker-compose i osobny krok
     * w CI. Aplikacja go NIE tworzy i nie ma tego robić: prawo do zakładania kubełków jest na
     * produkcji uprawnieniem administracyjnym.
     *
     * Klient budowany tak samo jak w S3ClientConfig, i te same dwa ustawienia są tu warunkiem
     * działania: adresowanie path-style (MinIO nie obsługuje wirtualnego hosta, więc żądanie
     * poleciałoby do nazwy, której DNS nie rozwiązuje) oraz region, którym MinIO się nie
     * przejmuje, ale bez którego SDK nie podpisze żądania.
     */
    private void createBucket(MinIOContainer minio) {
        try (S3Client client = S3Client.builder()
                .region(Region.of("eu-central-1"))
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(minio.getS3URL()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // Kontener jest świeży, więc w praktyce nie wystąpi; przechwycone, żeby ewentualne
            // ponowne wywołanie nie wywracało całego przebiegu na stanie, który jest poprawny.
        }
    }
}
