package com.resumethinking.platform.auth;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
 @RestController static class FailingController {
  @GetMapping("/jpa-lock") String jpa(){throw new OptimisticLockException("stale");}
  @GetMapping("/spring-lock") String spring(){throw new ObjectOptimisticLockingFailureException("resume", "stale");}
  @GetMapping("/unsupported-file") String unsupported(){throw new IllegalArgumentException("UNSUPPORTED_FILE");}
  @GetMapping("/payload-too-large") String tooLarge(){throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");}
 }
 @Test void optimisticLockFailuresUseVersionConflictEnvelope() throws Exception {
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new FailingController()).setControllerAdvice(new ApiExceptionHandler()).build();
  mvc.perform(get("/jpa-lock")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  mvc.perform(get("/spring-lock")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test void uploadPolicyFailuresUseContractStatuses() throws Exception {
   MockMvc mvc=MockMvcBuilders.standaloneSetup(new FailingController()).setControllerAdvice(new ApiExceptionHandler()).build();
   mvc.perform(get("/unsupported-file")).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE"));
   mvc.perform(get("/payload-too-large")).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
  }
}
