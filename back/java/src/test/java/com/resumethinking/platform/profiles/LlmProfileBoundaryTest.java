package com.resumethinking.platform.profiles;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.auth.AuthService;
import com.resumethinking.platform.auth.JwtService;
import com.resumethinking.platform.config.SecurityConfig;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmProfileBoundaryTest {
 @Test void reservedNetworkLiteralsAreRejectedOnProfileCreation() {
  var service = new LlmProfileService(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(),
    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
  for (String endpoint : new String[]{"https://100.64.0.1", "https://198.18.0.1", "https://192.0.0.1", "https://[fc00::1]"}) {
   assertThatThrownBy(() -> service.create(UUID.randomUUID(), new CreateLlmProfileCommand("x", endpoint, "m", "k")))
     .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MODEL_ENDPOINT_REJECTED");
  }
 }

 @Test void modelsUrlPreservesEncodedBasePath() {
  var service = new LlmProfileService(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(),
    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);
  assertThat(invokeModelsUri(service, java.net.URI.create("https://provider.test/v1%20api"))).isEqualTo(java.net.URI.create("https://provider.test/v1%20api/models"));
 }

 private static java.net.URI invokeModelsUri(LlmProfileService service, java.net.URI base) {
  try {
   var method = LlmProfileService.class.getDeclaredMethod("modelsUri", java.net.URI.class);
   method.setAccessible(true);
   return (java.net.URI) method.invoke(service, base);
  } catch (java.lang.reflect.InvocationTargetException e) {
   if (e.getCause() instanceof RuntimeException runtime) throw runtime;
   throw new RuntimeException(e.getCause());
  } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
 }
}

@WebMvcTest(controllers = LlmProfileController.class)
@Import(SecurityConfig.class)
class LlmProfileHttpBoundaryTest {
 @Autowired MockMvc mvc;
 @MockitoBean LlmProfileService profileService;
 @MockitoBean JwtService jwt;
 @MockitoBean AuthService authService;

 @Test void invalidProfileIdReturnsValidationApiError() throws Exception {
  var actor = UUID.randomUUID();
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));
  mvc.perform(get("/api/v1/llm-profiles/not-a-uuid").header("Authorization", "Bearer token"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }

 @Test void rejectedEndpointReturnsSafeEndpointErrorEnvelope() throws Exception {
  var actor = UUID.randomUUID();
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));
  when(profileService.create(org.mockito.ArgumentMatchers.eq(actor), org.mockito.ArgumentMatchers.any()))
    .thenThrow(new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"));
  mvc.perform(post("/api/v1/llm-profiles").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"displayName\":\"x\",\"endpointUrl\":\"https://100.64.0.1\",\"modelName\":\"m\",\"apiKey\":\"k\"}"))
    .andExpect(status().isUnprocessableEntity())
    .andExpect(jsonPath("$.code").value("MODEL_ENDPOINT_REJECTED"));
 }
}
