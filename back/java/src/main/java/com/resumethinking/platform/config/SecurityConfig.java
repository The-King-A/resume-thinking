package com.resumethinking.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import com.resumethinking.platform.auth.ApiExceptionHandler;
import com.resumethinking.platform.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJsonInputs() {
        return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static final String INTERNAL_CALLBACK_PATH = "/internal/v1/analysis-results";
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwt,
            ObjectMapper objectMapper,
            @Value("${app.python-internal-service-token:${PYTHON_INTERNAL_SERVICE_TOKEN:}}") String internalServiceToken)
            throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new ApiErrorAuthenticationEntryPoint(objectMapper)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/actuator/health").permitAll()
                        .requestMatchers(INTERNAL_CALLBACK_PATH).hasAuthority("ROLE_INTERNAL_SERVICE")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtFilter(jwt), UsernamePasswordAuthenticationFilter.class)
                // Run after JwtFilter so a user JWT cannot bypass the internal credential check.
                .addFilterAfter(new InternalServiceTokenFilter(internalServiceToken, objectMapper), JwtFilter.class);
        return http.build();
    }

    public static class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {
        private final ObjectMapper objectMapper;

        public ApiErrorAuthenticationEntryPoint(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
                throws IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    new ApiExceptionHandler.ApiError("AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", UUID.randomUUID(), false));
        }
    }

    public static class JwtFilter extends OncePerRequestFilter {
        private final JwtService jwt;

        public JwtFilter(JwtService jwt) {
            this.jwt = jwt;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ")) {
                jwt.parse(h.substring(7)).ifPresent(c -> {
                    req.setAttribute("actorId", c.subject());
                    req.setAttribute("role", c.role());
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(c.subject().toString(), null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + c.role()))));
                });
            }
            chain.doFilter(req, res);
        }
    }

    /** Authenticates the private Python-to-Java callback channel. */
    public static final class InternalServiceTokenFilter extends OncePerRequestFilter {
        private final byte[] expectedToken;
        private final ObjectMapper objectMapper;

        public InternalServiceTokenFilter(String expectedToken, ObjectMapper objectMapper) {
            this.expectedToken = expectedToken == null ? new byte[0] : expectedToken.getBytes(StandardCharsets.UTF_8);
            this.objectMapper = objectMapper;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String servletPath = request.getServletPath();
            if (INTERNAL_CALLBACK_PATH.equals(servletPath)) return false;
            String requestUri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (requestUri == null) return true;
            String fullPath = (contextPath == null ? "" : contextPath) + INTERNAL_CALLBACK_PATH;
            return !INTERNAL_CALLBACK_PATH.equals(requestUri) && !fullPath.equals(requestUri);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String supplied = request.getHeader(INTERNAL_TOKEN_HEADER);
            byte[] suppliedBytes = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
            if (expectedToken.length == 0 || supplied == null || !MessageDigest.isEqual(expectedToken, suppliedBytes)) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getWriter(),
                        new ApiExceptionHandler.ApiError("AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", UUID.randomUUID(), false));
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "internal-analysis-worker", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"))));
            chain.doFilter(request, response);
        }
    }
}
