package com.example.robert.translation;

import com.example.robert.translation.model.FileType;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.translation.storage.ObjectStore;
import com.example.robert.translation.storage.ObjectStoreException;
import com.example.robert.user.model.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KOLEJNOŚĆ ZAPISU: najpierw obiekt w magazynie, potem wiersz w bazie.
 *
 * DLACZEGO TO MA WŁASNĄ KLASĘ I DLACZEGO JEST JEDNOSTKOWA. Baza i magazyn obiektowy nie mają
 * wspólnej transakcji, więc jedna z tych dwóch operacji może się nie udać - i cała decyzja
 * polega na tym, KTÓRA połówka ma wtedy zostać. Zostawiamy plik bez wiersza: to zajęte
 * miejsce, które sprząta reguła wygasania na kubełku i którego nikt nie zauważy. Odwrotna
 * kolejność zostawiałaby wiersz bez pliku, czyli zlecenie widoczne na liście, którego nie da
 * się ani przetłumaczyć, ani pobrać.
 *
 * Tej własności NIE DA SIĘ sprawdzić przez API: żeby ją zobaczyć, trzeba wywołać awarię
 * MIĘDZY dwoma zapisami, a to wymaga podstawienia atrapy magazynu. Test przez MockMvc
 * z @MockitoBean kosztowałby OSOBNY kontekst Springa, czyli kolejną pulę połączeń trzymaną
 * do końca JVM-a - a suite wywróciła się już raz na wyczerpanym max_connections. Zwykły test
 * jednostkowy odpowiada na to samo pytanie i nie kosztuje ani jednego kontekstu.
 *
 * TransactionTemplate dostaje atrapę menedżera transakcji: szablon woła na nim getTransaction
 * i commit, a treść wywołania zwrotnego wykonuje normalnie - czyli dokładnie to, co tu badamy.
 */
class TranslationServiceStorageTest {

    private static final String CONTENT = "Ala ma kota";

    private TranslationJobRepository repository;
    private ObjectStore objectStore;
    private TranslationService service;
    private User owner;

    @BeforeEach
    void setUp() {
        repository = mock(TranslationJobRepository.class);
        objectStore = mock(ObjectStore.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        service = new TranslationService(repository, properties(), objectStore,
                new SimpleMeterRegistry(), transactionManager);

        owner = new User();
        owner.setId(7L);
    }

    private TranslationProperties properties() {
        return new TranslationProperties(
                false,
                TranslationProperties.Provider.ECHO,
                1_000_000,
                5,
                2,
                3,
                Duration.ofSeconds(1),
                Duration.ofMinutes(2),
                Duration.ofSeconds(1),
                Duration.ofDays(30),
                new TranslationProperties.DeepL("http://localhost", "", Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private void submit() {
        service.submit(owner,
                new UploadedFile("lista.txt", FileType.TXT, CONTENT.getBytes(StandardCharsets.UTF_8)),
                TargetLanguage.EN_GB);
    }

    /**
     * DYSKRYMINUJE ODWRÓCENIE KOLEJNOŚCI. Gdyby wiersz powstawał pierwszy, awaria magazynu
     * zostawiłaby w bazie zlecenie wskazujące na plik, którego nigdy nie było - a użytkownik
     * zobaczyłby je na liście jako czekające na tłumaczenie, które nie ma z czego powstać.
     */
    @Test
    @DisplayName("Awaria magazynu nie zostawia wiersza w bazie")
    void failedUpload_shouldLeaveNoRow() {
        doThrow(new ObjectStoreException("magazyn nie odpowiada", new RuntimeException()))
                .when(objectStore).put(anyString(), any(), anyString());

        assertThatThrownBy(this::submit).isInstanceOf(ObjectStoreException.class);

        verify(repository, never()).save(any());
        // Limit dobowy też nie jest wtedy w ogóle sprawdzany - nie ma czego naliczać
        verify(repository, never()).sumBilledCharsSince(anyLong(), any());
    }

    /**
     * Druga strona tej samej reguły: gdy zawiedzie BAZA, plik zostaje. To jest przyjęta cena
     * kolejności - osierocony obiekt wygasa sam przez regułę na kubełku, a do tego czasu
     * nikomu nie przeszkadza.
     */
    @Test
    @DisplayName("Plik trafia do magazynu przed zapisem wiersza")
    void objectIsWrittenBeforeRow() {
        when(repository.existsCached(anyLong(), anyString(), any(), any())).thenReturn(false);
        when(repository.sumBilledCharsSince(anyLong(), any())).thenReturn(0L);
        when(repository.save(any(TranslationJob.class))).thenThrow(new IllegalStateException("baza padła"));

        assertThatThrownBy(this::submit).isInstanceOf(IllegalStateException.class);

        InOrder order = inOrder(objectStore, repository);
        order.verify(objectStore).put(anyString(), any(), anyString());
        order.verify(repository).save(any(TranslationJob.class));
    }
}
