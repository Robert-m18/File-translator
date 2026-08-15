package com.example.robert.translation.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kontrakt magazynu obiektowego i budowa kluczy.
 *
 * Sprawdzany na wariancie w pamięci, bo to jedyna implementacja działająca bez stojącej
 * infrastruktury. S3ObjectStore przechodzi ten sam kontrakt w jobie "integration" (MinIO
 * w kontenerze) - dokładnie ta sama sytuacja co z RedisBucketProvider, który też jest
 * wykonywany wyłącznie tam. Asercje są celowo o ZACHOWANIU, a nie o mapie w środku, więc
 * przenoszą się na drugą implementację bez zmian.
 *
 * Bez kontekstu Springa: to jest kod bez zależności na kontener, a każdy kontekst testowy
 * to kolejna pula połączeń trzymana do końca JVM-a.
 */
class ObjectStoreTest {

    private static final String CONTENT_TYPE = "text/plain; charset=UTF-8";

    private InMemoryObjectStore store;
    private String prefix;
    private String sourceKey;

    @BeforeEach
    void setUp() {
        store = new InMemoryObjectStore();
        prefix = ObjectKeys.jobPrefix(42L, ObjectKeys.newStorageId());
        sourceKey = ObjectKeys.sourceKey(prefix, ".txt");
    }

    private void put(String key, String content) {
        store.put(key, content.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }

    @Test
    @DisplayName("Klucz niesie identyfikator właściciela, UUID zlecenia i rolę pliku")
    void keys_shouldFollowLayout() {
        assertThat(sourceKey).matches("users/42/jobs/[0-9a-f-]{36}/source\\.txt");
        assertThat(ObjectKeys.resultKey(prefix, ".txt")).matches("users/42/jobs/[0-9a-f-]{36}/result\\.txt");
        assertThat(ObjectKeys.prefixOf(sourceKey)).isEqualTo(prefix);
    }

    /**
     * Klucz bez ukośnika nie powstał w ObjectKeys, a wyliczony z niego "prefiks" byłby pusty -
     * czyli kasowanie po nim objęłoby CAŁY kubełek. Odmowa jest tu jedyną bezpieczną
     * odpowiedzią; wynik pusty byłby cichą katastrofą.
     */
    @Test
    @DisplayName("Prefiks z klucza bez ukośnika jest odrzucany, a nie zwracany jako pusty")
    void prefixOf_shouldRejectKeyWithoutSlash() {
        assertThatThrownBy(() -> ObjectKeys.prefixOf("cos-bez-ukosnika"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Zapisany obiekt daje się odczytać w całości i strumieniem")
    void put_shouldBeReadableBothWays() throws IOException {
        put(sourceKey, "Ala ma kota");

        assertThat(new String(store.read(sourceKey), StandardCharsets.UTF_8)).isEqualTo("Ala ma kota");

        try (StoredObject opened = store.open(sourceKey)) {
            assertThat(opened.size()).isEqualTo(11);
            assertThat(opened.contentType()).isEqualTo(CONTENT_TYPE);
            assertThat(new String(opened.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("Ala ma kota");
        }
    }

    /**
     * Brakujący obiekt to ObjectMissingException, NIE awaria magazynu. To rozróżnienie jest
     * całym powodem istnienia dwóch typów wyjątków: pierwsze wraca do użytkownika jako
     * 410 CONTENT_EXPIRED, drugie jako 500. Zlanie ich w jedno dawałoby błąd serwera przy
     * stanie, który jest przewidziany (reguła wygasania na kubełku).
     */
    @Test
    @DisplayName("Brak obiektu to ObjectMissingException, nie awaria")
    void missingObject_shouldHaveOwnException() {
        assertThatThrownBy(() -> store.read(sourceKey)).isInstanceOf(ObjectMissingException.class);
        assertThatThrownBy(() -> store.open(sourceKey)).isInstanceOf(ObjectMissingException.class);
        assertThat(store.exists(sourceKey)).isFalse();
    }

    /**
     * Kopia jest NIEZALEŻNA od oryginału - to warunek, na którym stoi kasowanie zleceń bez
     * liczenia referencji. Gdyby kopiowanie tworzyło wskazanie zamiast kopii, skasowanie
     * zlecenia, które trafiło w cache, zabrałoby wynik temu, z którego skopiowano.
     */
    @Test
    @DisplayName("Kopia przeżywa skasowanie oryginału")
    void copy_shouldBeIndependentOfSource() {
        put(sourceKey, "wynik");

        String otherPrefix = ObjectKeys.jobPrefix(42L, ObjectKeys.newStorageId());
        String copyKey = ObjectKeys.resultKey(otherPrefix, ".txt");
        store.copy(sourceKey, copyKey);

        store.deletePrefix(prefix);

        assertThat(store.exists(sourceKey)).isFalse();
        assertThat(new String(store.read(copyKey), StandardCharsets.UTF_8)).isEqualTo("wynik");
    }

    /**
     * Kasowanie obejmuje WSZYSTKO pod prefiksem zlecenia i NIC poza nim. Pierwsze, bo zlecenie
     * ma dwa pliki i zostawienie źródła byłoby zostawieniem treści użytkownika. Drugie, bo
     * prefiks jest budowany z identyfikatora właściciela - pomyłka kasowałaby cudze pliki.
     */
    @Test
    @DisplayName("Kasowanie prefiksu zabiera oba pliki zlecenia i nie rusza sąsiadów")
    void deletePrefix_shouldRemoveExactlyTheJob() {
        put(sourceKey, "źródło");
        put(ObjectKeys.resultKey(prefix, ".txt"), "wynik");

        String otherJob = ObjectKeys.sourceKey(ObjectKeys.jobPrefix(42L, ObjectKeys.newStorageId()), ".txt");
        String otherUser = ObjectKeys.sourceKey(ObjectKeys.jobPrefix(99L, ObjectKeys.newStorageId()), ".txt");
        put(otherJob, "inne zlecenie");
        put(otherUser, "inny użytkownik");

        store.deletePrefix(prefix);

        assertThat(store.exists(sourceKey)).isFalse();
        assertThat(store.exists(ObjectKeys.resultKey(prefix, ".txt"))).isFalse();
        assertThat(store.exists(otherJob)).isTrue();
        assertThat(store.exists(otherUser)).isTrue();
    }

    /**
     * Magazyn oddaje to, co dostał w chwili zapisu. Bez kopiowania tablicy wołający mógłby
     * zmienić treść "zapisanego" pliku, modyfikując swoją tablicę po fakcie - a to jest
     * zachowanie, którego prawdziwy magazyn nie ma i którego test na atrapie nie wykryłby
     * inaczej niż wprost.
     */
    @Test
    @DisplayName("Zapisana treść nie zmienia się razem z tablicą wołającego")
    void put_shouldCopyContent() {
        byte[] content = "oryginał".getBytes(StandardCharsets.UTF_8);
        store.put(sourceKey, content, CONTENT_TYPE);

        content[0] = 'X';

        assertThat(new String(store.read(sourceKey), StandardCharsets.UTF_8)).isEqualTo("oryginał");
    }
}
