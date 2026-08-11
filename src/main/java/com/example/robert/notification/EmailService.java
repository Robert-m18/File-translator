/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

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
                        // Adres nadawcy, a NIE spring.mail.username. To były wcześniej te same
                        // ustawienia i wyglądało to niewinnie, bo u części dostawców login SMTP
                        // faktycznie jest adresem. U SES czy SendGrida jest nim klucz API albo
                        // użytkownik IAM, a lokalnie - dowolny ciąg z docker-compose. Serwer
                        // odrzuca wtedy kopertę na MAIL FROM (553 5.1.3) i nie wychodzi ŻADEN
                        // mail: rejestracji nie da się potwierdzić, więc nie da się też zalogować.
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
     * przechodzi przez serwery, na które nie mamy wpływu, i zostaje w skrzynce odbiorcy
     * bezterminowo, czyli dłużej niż retencja samego zlecenia po naszej stronie.
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
     * Wspólna wysyłka. Synchroniczna i RZUCAJĄCA wyjątek przy porażce.
     *
     * Jedno i drugie jest tu istotne. Wcześniej metody były @Async, a wyjątek był łapany
     * i logowany - bo nie było komu go zgłosić: transakcja rejestracji była już
     * zatwierdzona, a informacja o nieudanej wysyłce nigdzie nie trafiała.
     *
     * Teraz wołającym jest OutboxPublisher, który MUSI znać wynik, żeby zapisać status
     * wiadomości i zaplanować ponowienie. Przełknięcie wyjątku tutaj oznaczałoby oznaczanie
     * jako wysłane maili, które nigdy nie wyszły - czyli dokładne odwrócenie sensu skrzynki
     * nadawczej. Wątek również nie jest już potrzebny: publisher i tak działa poza wątkiem
     * obsługującym żądanie HTTP.
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
            // MessagingException jest kontrolowany, a nie chcemy nim zaśmiecać sygnatur
            // w górę - publisher łapie po prostu Exception i zapisuje powód porażki.
            throw new MailDeliveryException("Nie udało się zbudować maila " + opis, e);
        }
        // MailException (niekontrolowany) leci wyżej sam. To ją rzuca mailSender.send()
        // przy niedostępnym albo odrzucającym serwerze SMTP - czyli w najczęstszym
        // przypadku awarii.
    }

    /** Porażka wysyłki. Łapana przez OutboxPublisher, który decyduje o ponowieniu. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
