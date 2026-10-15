package com.resumethinking.platform.matching;

import org.springframework.web.bind.annotation.*;

@RestController
public class InternalAnalysisCallbackController {
    private final MatchTaskService service;
    public InternalAnalysisCallbackController(MatchTaskService service) { this.service = service; }
    @PostMapping("/internal/v2/analysis-results")
    public MatchTaskService.CallbackResponse accept(@RequestBody AnalysisCallbackRequest request) { return service.acceptCallback(request); }

    @PostMapping("/internal/v3/analysis-results")
    public MatchTaskService.CallbackResponse acceptV3(@RequestBody V3AnalysisCallbackRequest request) {
        return service.acceptV3Callback(request);
    }
}
