package com.example.robert.common.security;

import com.example.robert.common.security.ProblemResponseWriter;
import com.example.robert.common.exception.JwtAuthenticationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private ProblemResponseWriter problemWriter;

    @InjectMocks
    private JwtFilter jwtFilter;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_shouldContinueChain_whenNoCookiePresent() throws Exception {
        // given
        // request.getCookies() domyślnie zwróci null - nic nie trzeba mockować

        // when
        jwtFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_shouldSetAuthentication_whenTokenIsValid() throws Exception {
        // given
        Cookie tokenCookie = new Cookie("accessToken", "valid-jwt-token");
        when(request.getCookies()).thenReturn(new Cookie[]{tokenCookie});

        when(jwtUtil.extractUsername("valid-jwt-token")).thenReturn("adrian@test.pl");

        UserDetails userDetails = new User(
                "adrian@test.pl",
                "haslo123",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(userDetailsService.loadUserByUsername("adrian@test.pl")).thenReturn(userDetails);

        // when
        jwtFilter.doFilterInternal(request, response, filterChain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("adrian@test.pl");


        verify(filterChain, times(1)).doFilter(request, response);

        verifyNoInteractions(response);
    }

    @Test
    void doFilter_shouldRejectRequest_whenTokenIsNotValid() throws Exception {
        //given
        when(jwtUtil.extractUsername(any())).thenThrow(new JwtAuthenticationException("Nieprawidłowy token", "INVALID_TOKEN"));
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("accessToken", "invalid-jwt-token")});

        //when
        jwtFilter.doFilterInternal(request, response, filterChain);

        //then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(any(), any());

        // Odpowiedź budowana jest jako ProblemDetail (RFC 9457), a maszynowy kod błędu
        // z wyjątku musi trafić do pola "code" - to po nim frontend rozpoznaje sytuację.
        ArgumentCaptor<ProblemDetail> problemCaptor = ArgumentCaptor.forClass(ProblemDetail.class);
        verify(problemWriter).write(eq(response), problemCaptor.capture());

        ProblemDetail problem = problemCaptor.getValue();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_TOKEN");
    }
}