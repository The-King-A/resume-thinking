package com.resumethinking.platform.config;

import com.resumethinking.platform.auth.*;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

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
