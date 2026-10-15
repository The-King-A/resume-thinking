package com.resumethinking.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.auth.JwtService;
import com.resumethinking.platform.matching.InternalAnalysisCallbackController;
import com.resumethinking.platform.matching.MatchTaskService;
import com.resumethinking.platform.matching.V3AnalysisCallbackRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalAnalysisCallbackController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.python-internal-service-token=test-internal-token-123456789012")
class InternalCallbackSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean MatchTaskService service;
    @MockitoBean JwtService jwt;

    @BeforeEach
    void setUp() {
        when(jwt.parse(anyString())).thenReturn(Optional.empty());
        when(service.acceptCallback(any())).thenReturn(new MatchTaskService.CallbackResponse("ACCEPTED", true));
        when(service.acceptV3Callback(any(V3AnalysisCallbackRequest.class)))
                .thenReturn(new MatchTaskService.CallbackResponse("ACCEPTED", true));
    }

    @Test
    void validInternalTokenAllowsCallbackWithoutJwt() throws Exception {
        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_PATH)
                        .header(SecurityConfig.INTERNAL_TOKEN_HEADER, "test-internal-token-123456789012")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ACCEPTED"));
    }

    @Test
    void missingOrWrongInternalTokenIsUnauthorized() throws Exception {
        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_PATH).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_PATH)
                        .header(SecurityConfig.INTERNAL_TOKEN_HEADER, "wrong-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void aValidUserJwtCannotBypassInternalToken() throws Exception {
        when(jwt.parse("user-jwt")).thenReturn(Optional.of(new JwtService.Claims("user001", com.resumethinking.platform.auth.UserRole.USER)));
        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_PATH)
                        .header("Authorization", "Bearer user-jwt")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void placeholderConfigurationFailsClosed() throws Exception {
        var filter = new SecurityConfig.InternalServiceTokenFilter("replace-with-shared-service-token", new ObjectMapper());
        var request = new MockHttpServletRequest("POST", SecurityConfig.INTERNAL_CALLBACK_PATH);
        request.addHeader(SecurityConfig.INTERNAL_TOKEN_HEADER, "replace-with-shared-service-token");
        var response = new MockHttpServletResponse();
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            filter.doFilter(request, response, (req, res) -> called.set(true));
            org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(401);
            org.assertj.core.api.Assertions.assertThat(called).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void v3CallbackUsesTheSameFailClosedInternalAuthentication() throws Exception {
        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_V3_PATH)
                        .header(SecurityConfig.INTERNAL_TOKEN_HEADER, "test-internal-token-123456789012")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ACCEPTED"));

        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_V3_PATH)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        when(jwt.parse("user-jwt-v3")).thenReturn(Optional.of(
                new JwtService.Claims("user001", com.resumethinking.platform.auth.UserRole.USER)));
        mvc.perform(post(SecurityConfig.INTERNAL_CALLBACK_V3_PATH)
                        .header("Authorization", "Bearer user-jwt-v3")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void shortOrWhitespaceConfigurationFailsClosed() throws Exception {
        for (String token : java.util.List.of("t".repeat(31), "t".repeat(31) + " ", "t".repeat(16) + "\t" + "t".repeat(16))) {
            var filter = new SecurityConfig.InternalServiceTokenFilter(token, new ObjectMapper());
            var request = new MockHttpServletRequest("POST", SecurityConfig.INTERNAL_CALLBACK_PATH);
            request.addHeader(SecurityConfig.INTERNAL_TOKEN_HEADER, token);
            var response = new MockHttpServletResponse();
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                filter.doFilter(request, response, (req, res) -> called.set(true));
                org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(401);
                org.assertj.core.api.Assertions.assertThat(called).isFalse();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
