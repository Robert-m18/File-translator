package com.example.filetranslator.notification;

import com.example.filetranslator.common.time.DbClock;
import com.example.filetranslator.notification.model.MailTemplate;
import com.example.filetranslator.notification.model.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Znacznik czasu zapisanego wiersza nie może wyprzedzać zegara.
 *
 * To jest regresja na wyścig, który wywracał OutboxTest mniej więcej co czwarty przebieg
 * przez ponad tydzień, za każdym razem inną metodą - stąd fałszywy trop "to interakcja
 * między testami".
 *
 * Mechanizm: kolumny czasowe mają precyzję MIKROSEKUNDOWĄ (TIMESTAMP(6) / DATETIME(6)),
 * a Instant.now() na tej maszynie daje setki nanosekund. Baza zaokrągla nadmiarowe
 * cyfry W GÓRĘ, więc wiersz zapisany "na teraz" lądował w niej ze znacznikiem do pół
 * mikrosekundy w PRZYSZŁOŚCI. Publisher szuka wierszy warunkiem next_retry_at <= :now,
 * więc taki wiersz bywał niewidoczny dla własnego cyklu i ginął zawsze ten zapisany
 * jako ostatni - stąd "wysłano 0 z 1" i zablokowana bariera w teście równoległości.
 *
 * Test został przy skrzynce nadawczej, choć źródło czasu (DbClock) mieszka od tej pory
 * w common/time i służy także kolejce zadań tłumaczenia: to właśnie tutaj wyścig się
 * objawiał i tutaj jest zapisana cała jego historia.
 *
 * Klasa siedzi OSOBNO od OutboxTest świadomie: tamta klasa jest wciąż niestabilna z innego,
 * nieustalonego powodu (patrz CLAUDE.md), a regresja na już zrozumianą przyczynę nie może
 * dzielić losu testu, który bywa czerwony.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxTimestampTest {

    @Autowired
    private OutboxMessageRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private OutboxMessage save(Instant timestamp) {
        OutboxMessage saved = repository.save(new OutboxMessage(
                "znacznik@example.com", MailTemplate.VERIFICATION, "{}", timestamp));
        repository.flush();
        // Bez tego odczytalibyśmy obiekt z kontekstu persistence, czyli wartość sprzed
        // zapisu - a pytanie brzmi właśnie, co baza z niej zrobiła.
        return repository.findById(saved.getId()).orElseThrow();
    }

    /**
     * Sedno: to, co wygenerowało produkcyjne źródło czasu, musi wrócić z bazy BEZ ZMIANY.
     *
     * Asercja jest na równość, nie na "nie później", bo każdy rozjazd między wartością
     * w pamięci a w bazie oznacza, że aplikacja i baza nie zgadzają się co do tego, kiedy
     * jest "teraz" - a cała rezerwacja wierszy stoi na porównaniu tych dwóch.
     */
    @Test
    @DisplayName("Zapisany znacznik wraca z bazy bez zmiany")
    void savedTimestamp_shouldSurviveRoundTripUnchanged() {
        Instant generated = DbClock.now();

        OutboxMessage read = save(generated);

        assertThat(read.getNextRetryAt()).isEqualTo(generated);
        assertThat(read.getCreatedAt()).isEqualTo(generated);
    }

    /**
     * Dowód, że zagrożenie jest realne, a obcinanie w DbClock nie jest przesadną
     * ostrożnością: wartość z cyframi poniżej mikrosekundy WRACA Z BAZY PRZESUNIĘTA W PRZÓD.
     *
     * Gdyby ten test kiedyś zaczął padać, znaczyłoby to, że baza (albo sterownik, albo
     * precyzja kolumny) zmieniła zachowanie na obcinanie - wtedy DbClock przestaje być
     * potrzebny. To jedyny sposób, żeby dowiedzieć się tego inaczej niż przez powrót
     * niestabilnych testów.
     */
    @Test
    @DisplayName("Nieobcięty znacznik wraca z bazy przesunięty w przyszłość")
    void subMicrosecondPrecision_isRoundedUpByDatabase() {
        Instant peryferyjny = Instant.now()
                .truncatedTo(DbClock.COLUMN_PRECISION)
                .plusNanos(600);

        OutboxMessage read = save(peryferyjny);

        assertThat(read.getNextRetryAt())
                .as("baza zaokrągliła w górę, więc znacznik jest teraz w przyszłości")
                .isAfter(peryferyjny);
    }

    @Test
    @DisplayName("Źródło czasu skrzynki nie generuje cyfr poniżej mikrosekundy")
    void clock_shouldNotProduceSubMicrosecondDigits() {
        assertThat(DbClock.now().getNano() % 1_000).isZero();
        assertThat(DbClock.truncate(Instant.now().plusNanos(999)).getNano() % 1_000)
                .isZero();
    }
}
