package com.resumethinking.platform.matching;

import java.time.Instant;

public record CallbackReceipt(String callbackId, String payloadHash, Instant receivedAt) {}
