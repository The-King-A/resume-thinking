package com.resumethinking.platform.config;

import com.resumethinking.platform.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityBoundaryTest {
 @Test void validBearerEstablishesAuthenticatedSecurityContext() throws Exception {
  var user = new User("user1", "user1@example.test", "hash", UserRole.USER);
  var jwt = new JwtService(java.util.Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes()));
  var token = jwt.issue(user); var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer " + token);
  SecurityContextHolder.clearContext();
  new SecurityConfig.JwtFilter(jwt).doFilter(request, new MockHttpServletResponse(), (req,res) -> assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull());
  assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
  SecurityContextHolder.clearContext();
 }
}

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class AuthenticationHttpBoundaryTest {
 @Autowired MockMvc mvc;
 @MockitoBean AuthService authService;
 @MockitoBean JwtService jwt;

 @Test void missingBearerReturnsUnauthorizedApiError() throws Exception {
  mvc.perform(get("/api/v1/auth/me"))
    .andExpect(status().isUnauthorized())
    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
    .andExpect(jsonPath("$.retryable").value(false));
 }

 @Test void invalidBearerReturnsUnauthorizedApiError() throws Exception {
  mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer invalid"))
    .andExpect(status().isUnauthorized())
    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
 }

 @Test void malformedRegistrationJsonReturnsValidationApiError() throws Exception {
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register")
    .contentType(MediaType.APPLICATION_JSON).content("{bad"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }

 @Test void invalidRoleEnumReturnsValidationApiError() throws Exception {
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\"user1\",\"email\":\"user1@example.test\",\"password\":\"StrongPassphrase1\",\"role\":\"NOPE\"}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }
}
