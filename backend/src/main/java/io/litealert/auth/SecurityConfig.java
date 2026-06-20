package io.litealert.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.error.GlobalExceptionHandler.ErrorResponse;
import io.litealert.common.web.TraceIdHolder;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

/**
 * Stateless security: no session, no CSRF (JWT in Authorization header is
 * not exposed to CSRF), no Spring login form. JwtAuthFilter does the work.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // H2 Console needs to load inside an iframe; allow same-origin frames.
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.sameOrigin())
            )
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/api/health", "/api/actuator/**", "/h2-console/**").permitAll()
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/webhook/**").permitAll()        // own auth flow
                .requestMatchers("/", "/index.html",
                        "/assets/**", "/favicon.ico",
                        "/static/**", "/h2-console/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()                              // SPA fallback paths
            )
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((req, res, ex) ->
                        writeError(res, ErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) ->
                        writeError(res, ErrorCode.FORBIDDEN)))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(HttpServletResponse res, ErrorCode code) throws java.io.IOException {
        res.setStatus(code.getStatus());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(
                code.name(), code.getDefaultMessage(),
                TraceIdHolder.current(), List.of());
        objectMapper.writeValue(res.getOutputStream(), body);
    }
}
