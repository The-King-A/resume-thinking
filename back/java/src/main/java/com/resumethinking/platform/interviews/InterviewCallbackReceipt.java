package com.resumethinking.platform.interviews;

import java.time.Instant;

public record InterviewCallbackReceipt(String callbackId, String payloadHash, Instant receivedAt) { }
