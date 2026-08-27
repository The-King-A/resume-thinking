package com.resumethinking.platform.matching;

import org.springframework.web.bind.annotation.*;

@RestController
public class InternalAnalysisCallbackController {
    private final MatchTaskService service;
    public InternalAnalysisCallbackController(MatchTaskService service) { this.service = service; }
    @PostMapping("/internal/v1/analysis-results")
    public MatchTaskService.CallbackResponse accept(@RequestBody AnalysisCallbackRequest request) { return service.acceptCallback(request); }
}
