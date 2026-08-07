/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.dto;

import com.example.robert.common.validation.EmailNormalizer;
import com.example.robert.common.validation.ValidEmail;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

		@ValidEmail(message = "Nieprawidłowy format email")
		@NotBlank(message = "Email nie może być pusty")
		String email,

		/*
		 * Przy logowaniu sprawdzamy wyłącznie, czy hasło w ogóle podano.
		 * Walidacja polityki hasła (długość, znaki) byłaby tu błędem: zwracałaby 400
		 * zamiast 401 i podpowiadała atakującemu, że podane hasło nie mogło być tym właściwym.
		 * Zablokowałaby też logowanie kontom założonym pod starszą polityką.
		 */
		@NotBlank(message = "Hasło nie może być puste")
		String password
) {
	/*
	 * Normalizacja adresu na granicy aplikacji - patrz EmailNormalizer. Konstruktor
	 * kompaktowy wykonuje się także przy deserializacji z JSON-a, więc wszystko poniżej
	 * (AuthenticationManager, UserDetailsService, zdarzenia logowania, licznik blokady)
	 * widzi już jedną, kanoniczną postać adresu.
	 */
	public LoginRequest {
		email = EmailNormalizer.normalize(email);
	}
}
