package com.resumethinking.platform.interviews;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalInterviewCallbackController {
    private final InterviewSessionService service;

    public InternalInterviewCallbackController(InterviewSessionService service) { this.service = service; }

    @PostMapping("/internal/v4/interview-results")
    public InterviewSessionService.CallbackResponse accept(@RequestBody InterviewAnalysisCallbackRequest request) {
        return service.acceptCallback(request);
    }
}
