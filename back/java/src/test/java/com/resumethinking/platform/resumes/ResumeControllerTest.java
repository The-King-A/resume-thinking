package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*; import java.util.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResumeControllerTest {
 @Test void deleteSendsExactConfirmationAndVersion() throws Exception {
  var service=mock(ResumeLifecycleService.class); var id=UUID.randomUUID(); var owner=UUID.randomUUID();
  var resume=Resume.active(id,owner,"CV",Resume.SourceType.TXT,UserRole.USER,Instant.parse("2026-01-01T00:00:00Z"),2L); when(service.softDelete(any())).thenReturn(resume);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new ResumeController(service)).build();
  mvc.perform(delete("/api/v1/resumes/{id}",id).requestAttr("actorId",owner).requestAttr("role",UserRole.USER).contentType(MediaType.APPLICATION_JSON).content("{\"confirmationText\":\"确认删除简历\",\"expectedVersion\":2}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0));
  verify(service).softDelete(new DeleteResumeCommand(id,owner,UserRole.USER,"确认删除简历",2L));
 }
}
