/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.common.time.DbClock;
import com.example.filetranslator.translation.repository.TranslationJobRepository;
import com.example.filetranslator.translation.storage.ObjectKeys;
import com.example.filetranslator.translation.storage.ObjectStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Nocne usuwanie starych zleceń tłumaczenia razem z ich plikami.
 *
 * Powodem nie jest rozmiar tabeli, tylko to, co zlecenia wskazują: pliki użytkowników, czyli
 * umowy, listy i notatki. Bez retencji wyciek danych obejmowałby wszystko, co ktokolwiek
 * kiedykolwiek przetłumaczył, od pierwszego dnia działania usługi. Użytkownik może skasować
 * zlecenie sam, a to zadanie jest tym, co dzieje się, gdy tego nie zrobi.
 *
 * Kasowane są zlecenia o każdym statusie, w odróżnieniu od skrzynki nadawczej, gdzie wiadomości
 * nieudane muszą przetrwać jako sygnał diagnostyczny. Tutaj taki powód nie zachodzi: nieudane
 * zlecenie sprzed miesiąca nie odpowiada na żadne pytanie, którego nie da się zadać metrykom,
 * a wskazuje dokładnie tę samą treść pliku co udane.
 *
 * Wiek liczony jest od utworzenia, a nie od zakończenia: zlecenie, które nigdy się nie zakończyło,
 * ma pustą datę zakończenia i nigdy nie zostałoby usunięte - czyli akurat najbardziej zapomniane
 * wiersze zostawałyby na zawsze.
 *
 * Zadanie należy do pakietu tłumaczeń, a nie do wspólnego zadania sprzątającego w pakiecie auth:
 * sięganie z tamtego pakietu do repozytorium innej funkcji sklejałoby je przez wnętrze. Każda
 * funkcja odpowiada za retencję własnych danych.
 *
 * Przy wdrożeniu wieloinstancyjnym harmonogram uruchamia się w każdej instancji osobno, więc
 * kasowanie wykona się tyle razy, ile jest instancji. Tutaj jest to nieszkodliwe, bo usuwanie
 * jest idempotentne, ale zadania ze skutkami ubocznymi wymagałyby blokady rozproszonej.
 */
@Slf4j
@Component
public class TranslationCleanupJob {

    private final TranslationJobRepository repository;
    private final TranslationProperties properties;
    private final ObjectStore objectStore;

    /**
     * Transakcja wołana programowo: kasowanie plików musi pozostać poza nią, a adnotacja na
     * metodzie prywatnej tej samej klasy nie zadziałałaby wcale, bo wywołanie własnej metody
     * omija proxy Springa.
     */
    private final TransactionTemplate transaction;

    public TranslationCleanupJob(TranslationJobRepository repository,
                                 TranslationProperties properties,
                                 ObjectStore objectStore,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.objectStore = objectStore;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Uruchamiane w nocy, po sprzątaniu tokenów i skrzynki nadawczej. Godziny są rozsunięte, żeby
     * trzy kasowania nie obciążały bazy jednocześnie.
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupOldJobs() {
        int removed = removeOlderThanRetention();
        log.info("Sprzątanie zleceń tłumaczenia zakończone - usunięto {} zleceń", removed);
    }

    /**
     * Usuwa zlecenia starsze niż skonfigurowana retencja razem z ich plikami.
     *
     * Metoda jest wydzielona z zadania harmonogramu, dzięki czemu test wywołuje samo kasowanie,
     * bez czekania na wyzwalacz czasowy i niezależnie od tego, czy harmonogram jest w danym
     * profilu włączony.
     *
     * Kolejność kroków to klucze, wiersze, dopiero potem pliki. Obowiązuje ten sam niezmiennik co
     * przy kasowaniu pojedynczego zlecenia: stanem pośrednim po awarii jest zawsze plik bez
     * wiersza, nigdy wiersz bez pliku. Klucze trzeba odczytać przed skasowaniem wierszy, bo potem
     * nie ma już skąd wziąć prefiksów.
     *
     * Reguła wygasania na kubełku robi to samo niezależnie od tego zadania i stanowi siatkę
     * bezpieczeństwa, wyłapując również pliki osierocone przy przyjmowaniu zleceń. Nie zastępuje
     * jednak tego kroku: jej termin to druga wartość ustawiona w innym miejscu, a wiąże je
     * wyłącznie uważność. Kasowanie po stronie aplikacji sprawia, że po skróceniu retencji pliki
     * znikają razem z wierszami, a nie dopiero po poprawieniu reguły na kubełku.
     *
     * @return liczba usuniętych zleceń
     */
    public int removeOlderThanRetention() {
        Instant cutoff = DbClock.now().minus(properties.retention());

        List<String> sourceKeys = repository.findSourceKeysCreatedBefore(cutoff);
        Integer removed = transaction.execute(status -> repository.deleteCreatedBefore(cutoff));

        for (String key : sourceKeys) {
            try {
                objectStore.deletePrefix(ObjectKeys.prefixOf(key));
            } catch (RuntimeException e) {
                // Jedno nieudane kasowanie nie może zatrzymać sprzątania pozostałych zleceń.
                // Plik zostaje osierocony i wygaśnie przez regułę na kubełku - dlatego ta reguła
                // jest potrzebna nawet przy działającym kasowaniu.
                log.warn("Nie udało się usunąć plików zlecenia z magazynu: {}", e.toString());
            }
        }

        return removed == null ? 0 : removed;
    }
}
