/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.provider;

import lombok.Getter;

/**
 * Dostawca tłumaczenia odmówił albo nie odpowiedział.
 *
 * Niesie DWIE informacje, obie potrzebne workerowi:
 *
 *  - code - stabilny, maszynowy identyfikator przyczyny; ląduje w kolumnie last_error
 *    i w logu. Nie wychodzi do klienta API (komunikat obcego systemu w odpowiedzi HTTP
 *    to wyciek informacji), ale to po nim operator rozpoznaje, czy problem leży po stronie
 *    aplikacji, czy dostawcy;
 *
 *  - retryable - czy ponowienie ma sens. To jest różnica między "serwer dostawcy miał
 *    czkawkę" a "klucz API jest nieprawidłowy": pierwsze samo przejdzie, drugie będzie
 *    wracać identycznie do wyczerpania prób, tylko wolniej i z pełnym backoffem.
 *    Ta flaga jest jedynym, co worker musi wiedzieć o protokole dostawcy.
 */
@Getter
public class TranslationProviderException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public TranslationProviderException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public TranslationProviderException(String code, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }
}
