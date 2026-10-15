package com.resumethinking.platform.config;

import com.resumethinking.platform.auth.AuthController;
import com.resumethinking.platform.auth.AuthService;
import com.resumethinking.platform.auth.InvalidCredentialsException;
import com.resumethinking.platform.auth.JwtService;
import com.resumethinking.platform.auth.LoginCommand;
import com.resumethinking.platform.auth.ResetPasswordCommand;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class AuthenticationHttpBoundaryTest {
 @Autowired MockMvc mvc;
 @MockitoBean AuthService authService;
 @MockitoBean JwtService jwt;

 @Test void missingBearerReturnsUnauthorizedApiError() throws Exception {
  mvc.perform(get("/api/v2/auth/me"))
    .andExpect(status().isUnauthorized())
    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
    .andExpect(jsonPath("$.retryable").value(false));
 }

 @Test void invalidBearerReturnsUnauthorizedApiError() throws Exception {
  mvc.perform(get("/api/v2/auth/me").header("Authorization", "Bearer invalid"))
    .andExpect(status().isUnauthorized())
    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
 }

 @Test void rejectedLoginReturnsGenericInvalidCredentialsError() throws Exception {
  when(authService.login(any(LoginCommand.class))).thenThrow(new InvalidCredentialsException());

  mvc.perform(post("/api/v2/auth/login")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"identifier\":\"not-a-real-user\",\"password\":\"long-enough-password\"}"))
    .andExpect(status().isUnauthorized())
    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    .andExpect(jsonPath("$.message").value("INVALID_CREDENTIALS"))
    .andExpect(jsonPath("$.retryable").value(false));
 }

 @Test void malformedRegistrationJsonReturnsValidationApiError() throws Exception {
  mvc.perform(post("/api/v2/auth/register").contentType(MediaType.APPLICATION_JSON).content("{bad"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }

 @Test void invalidRoleEnumReturnsValidationApiError() throws Exception {
  mvc.perform(post("/api/v2/auth/register")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\"user1\",\"email\":\"user1@example.test\",\"password\":\"StrongPassphrase1\",\"role\":\"NOPE\"}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }

 @Test void loopbackPasswordResetReturnsNoContent() throws Exception {
  mvc.perform(post("/api/v2/auth/password-reset")
    .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"identifier\":\"alice\",\"newPassword\":\"replacement-password-123\"}"))
    .andExpect(status().isNoContent());
 }

 @Test void disabledOrNonLoopbackPasswordResetReturnsSafeNotFoundResponse() throws Exception {
  doThrow(new ResourceNotFoundException()).when(authService).resetLocalPassword(any(ResetPasswordCommand.class), eq("192.168.1.20"));

  mvc.perform(post("/api/v2/auth/password-reset")
    .with(request -> { request.setRemoteAddr("192.168.1.20"); return request; })
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"identifier\":\"alice\",\"newPassword\":\"replacement-password-123\"}"))
    .andExpect(status().isNotFound())
    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
 }

 @Test void unknownPasswordResetIdentifierReturnsSafeNotFoundResponse() throws Exception {
  doThrow(new ResourceNotFoundException()).when(authService).resetLocalPassword(any(ResetPasswordCommand.class), eq("127.0.0.1"));

  mvc.perform(post("/api/v2/auth/password-reset")
    .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"identifier\":\"nobody\",\"newPassword\":\"replacement-password-123\"}"))
    .andExpect(status().isNotFound())
    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
 }

 @Test void confirmPasswordIsRejectedAsAnUnknownRequestField() throws Exception {
  mvc.perform(post("/api/v2/auth/password-reset")
    .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"identifier\":\"alice\",\"newPassword\":\"replacement-password-123\",\"confirmPassword\":\"replacement-password-123\"}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }
}
