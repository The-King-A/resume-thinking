package com.resumethinking.platform.matching;

import java.time.Instant;
import java.util.UUID;

public record CallbackReceipt(UUID callbackId, String payloadHash, Instant receivedAt) {}
