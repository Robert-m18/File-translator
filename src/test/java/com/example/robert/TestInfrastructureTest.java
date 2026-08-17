package com.example.robert;

import com.example.robert.common.security.ratelimit.BucketProvider;
import com.example.robert.common.security.ratelimit.InMemoryBucketProvider;
import com.example.robert.common.security.ratelimit.RedisBucketProvider;
import com.example.robert.translation.storage.InMemoryObjectStore;
import com.example.robert.translation.storage.ObjectStore;
import com.example.robert.translation.storage.S3ObjectStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprawdza, że testy chodzą na tych silnikach, na których miały chodzić.
 *
 * DLACZEGO TO JEST TEST, A NIE ZAŁOŻENIE. Podmiana konfiguracji na kontenery idzie
 * właściwościami systemowymi (TestInfrastructure), a to znaczy, że każda pomyłka w nazwie
 * klucza, każda zmiana priorytetów w Springu i każde przypadkowe nadpisanie w
 * application-test.yml kończy się tak samo: kontenery wstają, kosztują czas, a aplikacja
 * spokojnie łączy się z H2 i mapą w pamięci. CAŁA SUITE JEST WTEDY ZIELONA, bo każdy test
 * sprawdza swoją logikę, a nie to, co pod nią stoi - i wracamy do stanu sprzed scalenia
 * jobów CI, tyle że bez świadomości tego. To dokładnie ten tryb awarii, o którym mówi
 * uwaga przy app.rate-limit.store w CLAUDE.md: obrona degraduje się, wyglądając zdrowo.
 *
 * Test jest dwustronny, bo profil -Ph2 jest legalnym wariantem, a nie usterką: przy
 * test.database=h2 sprawdza, że NIE stoją za tym kontenery. Jedna asercja w drugą stronę
 * przepuściłaby cichy powrót na H2 w domyślnym przebiegu.
 *
 * Adnotacje są celowo IDENTYCZNE jak w CurrentUserTest (bez własnego @TestPropertySource
 * i bez @MockitoBean), żeby klasa dołączyła do istniejącego kontekstu zamiast tworzyć nowy -
 * każdy kolejny kontekst to kolejna pula połączeń trzymana do końca JVM-a.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestInfrastructureTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BucketProvider bucketProvider;

    @Autowired
    private ObjectStore objectStore;

    private static boolean runningOnH2() {
        return "h2".equalsIgnoreCase(System.getProperty("test.database"));
    }

    /**
     * Nazwa produktu z metadanych sterownika, a nie sam URL: H2 w MODE=PostgreSQL potrafi
     * udawać PostgreSQL-a w składni SQL, ale przedstawia się własną nazwą i to ona rozstrzyga,
     * z czym naprawdę rozmawia Hibernate.
     */
    @Test
    @DisplayName("Baza jest tym silnikiem, który wynika z uruchomionego wariantu")
    void datasource_shouldMatchSelectedEngine() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            assertThat(product).isEqualTo(runningOnH2() ? "H2" : "PostgreSQL");
        }
    }

    /**
     * Limiter i magazyn plików mają w tym projekcie po dwie implementacje, z których jedna
     * jest atrapą na potrzeby przebiegu bez infrastruktury. Sprawdzamy TYP BEANA, bo to
     * jedyne, co odróżnia "wykonaliśmy kod rozmawiający z Redisem i S3" od "wykonaliśmy
     * mapę w pamięci" - żaden test funkcjonalny tego nie widzi, obie implementacje spełniają
     * ten sam kontrakt.
     */
    @Test
    @DisplayName("Limity i magazyn plików wskazują implementacje właściwe dla wariantu")
    void infrastructureBeans_shouldMatchSelectedEngine() {
        if (runningOnH2()) {
            assertThat(bucketProvider).isInstanceOf(InMemoryBucketProvider.class);
            assertThat(objectStore).isInstanceOf(InMemoryObjectStore.class);
        } else {
            assertThat(bucketProvider).isInstanceOf(RedisBucketProvider.class);
            assertThat(objectStore).isInstanceOf(S3ObjectStore.class);
        }
    }
}
