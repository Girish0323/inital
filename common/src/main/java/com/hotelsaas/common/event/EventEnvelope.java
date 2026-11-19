package com.hotelsaas.common.event;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
    UUID eventId,
    String eventType,
    Instant occurredAt,
    String tenantId,
    String correlationId,
    T payload) {
  public EventEnvelope(String eventType, String tenantId, String correlationId, T payload) {
    this(UUID.randomUUID(), eventType, Instant.now(), tenantId, correlationId, payload);
  }
}
