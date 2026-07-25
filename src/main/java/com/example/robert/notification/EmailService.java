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
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Wysyłka maili transakcyjnych (na razie: potwierdzenie rejestracji).
 * @Async - request rejestracji nie czeka na odpowiedź serwera SMTP.
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
                        @Value("${spring.mail.username}") String mailFrom) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.frontendUrl = frontendUrl;
        this.mailFrom = mailFrom;
    }

    @Async
    public void sendVerificationEmail(String toEmail, String name, String token) {
        String confirmationLink = frontendUrl + "/confirm-email?token=" + token;

        Context context = new Context();
        context.setVariable("name", name);
        context.setVariable("confirmationLink", confirmationLink);
        // Template is located at src/main/resources/templates/email.html
        // Use template name "email" so Thymeleaf resolves email.html
        String htmlBody = templateEngine.process("email", context);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "utf-8");
            helper.setTo(toEmail);
            helper.setFrom(mailFrom);
            helper.setSubject("Potwierdź rejestrację");
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("Wysłano mail weryfikacyjny");
        } catch (MessagingException | MailException e) {
            // MailException jest kluczowa: mailSender.send() rzuca właśnie ją (niekontrolowaną)
            // przy niedostępnym serwerze SMTP. Łapanie samego MessagingException nie obejmowało
            // najczęstszego przypadku awarii - wyjątek uciekał do wątku puli @Async.
            //
            // Świadomie nie rzucamy dalej: błąd wysyłki maila nie może wywrócić rejestracji,
            // która została już zatwierdzona (@Async leci po commicie transakcji).
            // Docelowo: retry (Spring Retry) + tabela outbox ze statusem wysyłki.
            log.error("Nie udało się wysłać maila weryfikacyjnego", e);
        }
    }
}