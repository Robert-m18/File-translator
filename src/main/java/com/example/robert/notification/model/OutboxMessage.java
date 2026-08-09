/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Jeden mail czekający na wysłanie.
 *
 * Wiersz powstaje w transakcji operacji, która mail zamawia (np. przyjęcia rejestracji),
 * więc zamiar wysyłki jest trwały od chwili commitu. Szczegóły i uzasadnienie wzorca:
 * changelog 0005-mail-outbox.xml.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

    public enum Status {
        /** Do wysłania (także po nieudanej próbie - wtedy z odsuniętym nextRetryAt). */
        NEW,
        SENT,
        /** Poddaliśmy się po maksymalnej liczbie prób. Wymaga interwencji człowieka. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MailTemplate template;

    /** Zmienne szablonu jako JSON. Treść maila powstaje dopiero przy wysyłce. */
    @Column(nullable = false, length = 1000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NEW;

    @Column(nullable = false)
    private int attempts = 0;

    /**
     * Kiedy najwcześniej wolno wziąć ten wiersz do wysyłki.
     *
     * Pełni dwie role naraz: nośnika backoffu po porażce i rezerwacji wiersza przez
     * instancję, która właśnie zabiera się do wysyłki (patrz OutboxPublisher).
     */
    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public OutboxMessage(String recipient, MailTemplate template, String payload, Instant now) {
        this.recipient = recipient;
        this.template = template;
        this.payload = payload;
        this.status = Status.NEW;
        this.attempts = 0;
        // Gotowy do wysłania natychmiast - publisher zabierze go przy najbliższym cyklu
        this.nextRetryAt = now;
        this.createdAt = now;
    }
}
