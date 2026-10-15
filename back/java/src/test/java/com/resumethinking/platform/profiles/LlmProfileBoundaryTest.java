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


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LlmProfileController.class)
@Import(SecurityConfig.class)
class LlmProfileBoundaryTest {
 @Autowired MockMvc mvc;
 @MockitoBean LlmProfileService profileService;
 @MockitoBean JwtService jwt;
 @MockitoBean AuthService authService;

 @Test void reservedNetworkLiteralsAreRejectedOnProfileCreation() {
  var service = new LlmProfileService(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(),
    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), true);
  for (String endpoint : new String[]{"https://100.64.0.1", "https://198.18.0.1", "https://192.0.0.1", "https://[fc00::1]"}) {
   assertThatThrownBy(() -> service.create("user001", new CreateLlmProfileCommand("x", endpoint, "m", "k")))
     .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MODEL_ENDPOINT_REJECTED");
  }
 }

 @Test void modelsUrlPreservesEncodedBasePath() {
  var service = new LlmProfileService(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(),
    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);
  assertThat(invokeModelsUri(service, java.net.URI.create("https://provider.test/v1%20api"))).isEqualTo(java.net.URI.create("https://provider.test/v1%20api/models"));
 }

 @Test void endpointSyntaxRejectsUserInfoQueryFragmentAndTraversal() {
  var service = new LlmProfileService(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(),
    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);
  for (String endpoint : new String[]{
    "https://user:pass@provider.example/v1",
    "https://provider.example/v1?token=secret",
    "https://provider.example/v1#fragment",
    "https://provider.example/v1/%2e%2e/private",
    "https://provider.example/v1\\private"}) {
   assertThatThrownBy(() -> service.create("user002", new CreateLlmProfileCommand("x", endpoint, "m", "k")))
     .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MODEL_ENDPOINT_REJECTED");
  }
 }

 @Test void bothEdgesOf19818ReservedRangeAreRejected() {
  var service = new LlmProfileService(new com.resumethinking.platform.auth.InMemoryRepositories.LlmProfileRepositoryStub(),
    new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), false);
  for (String endpoint : new String[]{"https://198.18.0.0", "https://198.19.255.255"}) {
   assertThatThrownBy(() -> service.create("user003", new CreateLlmProfileCommand("x", endpoint, "m", "k")))
     .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MODEL_ENDPOINT_REJECTED");
  }
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
 @Test void unknownProfileIdReturnsNotFoundApiError() throws Exception {
  var actor = "user004";
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));
  when(profileService.get(actor, "not-a-profile-id")).thenThrow(new ResourceNotFoundException());
  mvc.perform(get("/api/v2/llm-profiles/not-a-profile-id").header("Authorization", "Bearer token"))
    .andExpect(status().isNotFound())
    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
 }

 @Test void rejectedEndpointReturnsSafeEndpointErrorEnvelope() throws Exception {
  var actor = "user005";
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));
  when(profileService.create(org.mockito.ArgumentMatchers.eq(actor), org.mockito.ArgumentMatchers.any()))
    .thenThrow(new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"));
  mvc.perform(post("/api/v2/llm-profiles").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"displayName\":\"x\",\"endpointUrl\":\"https://100.64.0.1\",\"modelName\":\"m\",\"apiKey\":\"k\"}"))
    .andExpect(status().isUnprocessableEntity())
    .andExpect(jsonPath("$.code").value("MODEL_ENDPOINT_REJECTED"));
 }

 @Test void scanRouteRequiresAuthentication() throws Exception {
  mvc.perform(post("/api/v2/llm-profiles/scan")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"endpointUrl\":\"https://provider.example/v1\",\"apiKey\":\"scan-secret\"}"))
    .andExpect(status().isUnauthorized());
 }

 @Test void rejectedScanEndpointReturnsSafeEndpointErrorEnvelope() throws Exception {
  var actor = "user006";
  var apiKey = "scan-secret";
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));
  when(profileService.scanModels(org.mockito.ArgumentMatchers.any(ScanLlmProfileCommand.class)))
    .thenThrow(new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"));

  var result = mvc.perform(post("/api/v2/llm-profiles/scan").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"endpointUrl\":\"https://100.64.0.1\",\"apiKey\":\"" + apiKey + "\"}"))
    .andExpect(status().isUnprocessableEntity())
    .andExpect(jsonPath("$.code").value("MODEL_ENDPOINT_REJECTED"))
    .andReturn();

  assertThat(result.getResponse().getContentAsString()).doesNotContain(apiKey).doesNotContain("100.64.0.1");
 }

 @Test void scanRejectsBlankAndOversizedSensitiveInputs() throws Exception {
  var actor = "user007";
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));

  mvc.perform(post("/api/v2/llm-profiles/scan").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"endpointUrl\":\"\",\"apiKey\":\" \"}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

  mvc.perform(post("/api/v2/llm-profiles/scan").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"endpointUrl\":\"https://provider.example/v1\",\"apiKey\":\"" + "a".repeat(4097) + "\"}"))
    .andExpect(status().isBadRequest())
     .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }

 @Test void updateAllowsAnOmittedApiKeyToRetainTheSavedKey() throws Exception {
  var actor = "user008";
  var updated = new LlmProfile("profile001", actor, "x", "https://provider.example/v1", "m", new byte[]{1}, new byte[12], false);
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));
  when(profileService.update(org.mockito.ArgumentMatchers.eq(actor), org.mockito.ArgumentMatchers.eq("profile001"), org.mockito.ArgumentMatchers.any(UpdateLlmProfileCommand.class))).thenReturn(updated);

  mvc.perform(put("/api/v2/llm-profiles/profile001").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"displayName\":\"x\",\"endpointUrl\":\"https://provider.example/v1\",\"modelName\":\"m\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.hasApiKey").value(true));
 }

 @Test void updateRejectsAnExplicitNullApiKey() throws Exception {
  var actor = "user009";
  when(jwt.parse("token")).thenReturn(java.util.Optional.of(new JwtService.Claims(actor, UserRole.USER)));

  mvc.perform(put("/api/v2/llm-profiles/profile001").header("Authorization", "Bearer token")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"displayName\":\"x\",\"endpointUrl\":\"https://provider.example/v1\",\"modelName\":\"m\",\"apiKey\":null}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }
}
