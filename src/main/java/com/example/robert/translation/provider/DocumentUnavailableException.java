/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.provider;

/**
 * Dokument, po który wracamy z zapisanym uchwytem, nie istnieje już u dostawcy.
 *
 * WŁASNY TYP, A NIE KOD BŁĘDU, bo worker musi na to zareagować INACZEJ niż na zwykłą
 * porażkę: ma wyczyścić zapisany uchwyt, żeby kolejne podejście wgrało dokument od nowa.
 * Gdyby zostawił uchwyt, każde następne podejście pytałoby o ten sam nieistniejący dokument
 * aż do wyczerpania prób - czyli zlecenie umierałoby, mimo że wystarczy zacząć jeszcze raz.
 *
 * DLACZEGO TEN STAN W OGÓLE ISTNIEJE: DeepL pozwala pobrać przetłumaczony dokument TYLKO RAZ
 * i kasuje go zaraz potem. Jeśli proces padnie po pobraniu, a przed zapisaniem wyniku
 * w magazynie, dokumentu nie ma już skąd wziąć. Jedynym wyjściem jest wgranie go ponownie -
 * i to kosztuje drugi raz. Stąd kolejność w workerze: pobranie, zapis do magazynu, dopiero
 * potem markDone. Okno, w którym da się stracić opłacone tłumaczenie, jest wtedy najwęższe,
 * jakie się da uzyskać bez transakcji rozpiętej na dostawcę.
 *
 * Ponawialny (retryable), bo ponowienie faktycznie ma sens - tyle że zaczyna od zera.
 */
public class DocumentUnavailableException extends TranslationProviderException {

    public DocumentUnavailableException(String message) {
        super("TRANSLATION_DOCUMENT_GONE", true, message);
    }
}
