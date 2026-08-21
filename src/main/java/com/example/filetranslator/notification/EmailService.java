/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Wysyłka maili transakcyjnych: potwierdzenie rejestracji, reset hasła oraz
 * powiadomienie o próbie rejestracji na istniejący adres.
 *
 * Ta klasa tylko rozmawia z SMTP i nie podejmuje żadnych decyzji o ponawianiu.
 * Kto, kiedy i ile razy próbuje wysłać - o tym decyduje OutboxPublisher.
 * Wołanie jej wprost z obsługi żądania HTTP byłoby błędem: żądanie czekałoby na SMTP,
 * a nieudana wysyłka nie zostawiłaby po sobie śladu. Maile zamawia się przez MailOutbox.
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String frontendUrl;
    private final String mailFrom;

    @Autowired
    public EmailService(JavaMailSender mailSender,
                        TemplateEngine templateEngine,
                        @Value("${app.frontend.url}") String frontendUrl,
                        // Adres nadawcy jest osobnym ustawieniem, a nie loginem SMTP. U części
                        // dostawców login bywa adresem, ale u innych jest kluczem API albo nazwą
                        // użytkownika technicznego, a lokalnie dowolnym ciągiem znaków. Użycie
                        // loginu jako nadawcy kończy się odrzuceniem koperty przez serwer i tym,
                        // że nie wychodzi żadna wiadomość - a wtedy nie da się potwierdzić
                        // rejestracji ani, w konsekwencji, zalogować.
                        @Value("${app.mail.from}") String mailFrom) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.frontendUrl = frontendUrl;
        this.mailFrom = mailFrom;
    }

    public void sendVerificationEmail(String toEmail, String name, String token) {
        Context context = new Context();
        context.setVariable("name", name);
        context.setVariable("confirmationLink", frontendUrl + "/confirm-email?token=" + token);

        send(toEmail, "Potwierdź rejestrację", "email", context, "weryfikacyjnego");
    }

    public void sendPasswordResetEmail(String toEmail, String name, String token) {
        Context context = new Context();
        context.setVariable("name", name);
        context.setVariable("resetLink", frontendUrl + "/reset-password?token=" + token);

        send(toEmail, "Ustaw nowe hasło", "password-reset", context, "z resetem hasła");
    }

    /**
     * Powiadomienie dla właściciela skrzynki, że ktoś próbował zarejestrować konto
     * na jego adres. Nie niesie żadnego tokenu - nie ma nic do aktywowania.
     */
    public void sendAccountExistsEmail(String toEmail) {
        Context context = new Context();
        context.setVariable("forgotPasswordLink", frontendUrl + "/forgot-password");

        send(toEmail, "Próba rejestracji na Twój adres", "account-exists", context,
                "o istniejącym koncie");
    }

    /**
     * Zlecenie tłumaczenia jest gotowe do pobrania.
     *
     * Mail niesie NAZWĘ pliku i link do listy zleceń - nigdy treści tłumaczenia. Powód jest
     * ten sam, dla którego payload skrzynki nadawczej nie może nieść sekretów: wiadomość
     * przechodzi przez serwery poza kontrolą aplikacji i zostaje w skrzynce odbiorcy
     * bezterminowo, czyli dłużej niż retencja samego zlecenia.
     */
    public void sendTranslationDoneEmail(String toEmail, String name, String filename) {
        Context context = new Context();
        context.setVariable("name", name);
        context.setVariable("filename", filename);
        context.setVariable("translationsLink", frontendUrl + "/translations");

        send(toEmail, "Twoje tłumaczenie jest gotowe", "translation-done", context,
                "o gotowym tłumaczeniu");
    }

    /**
     * Wspólna ścieżka wysyłki - synchroniczna i zgłaszająca wyjątek przy niepowodzeniu.
     *
     * Obie te cechy są istotne. Wołającym jest mechanizm skrzynki nadawczej, który musi znać
     * wynik, żeby zapisać status wiadomości i zaplanować ponowienie; przełknięcie wyjątku
     * oznaczałoby oznaczanie jako wysłane wiadomości, które nigdy nie wyszły, czyli odwrócenie
     * sensu całej skrzynki. Osobny wątek nie jest tu potrzebny, bo wysyłka i tak nie odbywa się
     * na wątku obsługującym żądanie HTTP.
     */
    private void send(String toEmail, String subject, String template, Context context, String opis) {
        String htmlBody = templateEngine.process(template, context);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "utf-8");
            helper.setTo(toEmail);
            helper.setFrom(mailFrom);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.debug("Wysłano mail {}", opis);

        } catch (MessagingException e) {
            // Wyjątek kontrolowany nie jest przepuszczany dalej, żeby nie zaśmiecać sygnatur
            // w górę - wysyłka łapie wyjątek ogólny i zapisuje powód porażki.
            throw new MailDeliveryException("Nie udało się zbudować maila " + opis, e);
        }
        // Wyjątek niekontrolowany leci wyżej sam - zgłasza go wysyłka przy niedostępnym
        // albo odrzucającym serwerze pocztowym, czyli w najczęstszym przypadku awarii.

    }

    /** Porażka wysyłki. Łapana przez OutboxPublisher, który decyduje o ponowieniu. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
