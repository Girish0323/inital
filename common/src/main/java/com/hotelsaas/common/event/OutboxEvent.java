package com.hotelsaas.common.event;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
  @Id private UUID id = UUID.randomUUID();

  @Column(nullable = false)
  private String eventType;

  @Column(nullable = false, length = 14)
  private String tenantId;

  @Lob
  @Column(nullable = false)
  private String payload;

  @Column(nullable = false)
  private Instant occurredAt = Instant.now();

  private Instant publishedAt;

  protected OutboxEvent() {}

  public OutboxEvent(String eventType, String tenantId, String payload) {
    this.eventType = eventType;
    this.tenantId = tenantId;
    this.payload = payload;
  }

  public UUID getId() {
    return id;
  }

  public String getEventType() {
    return eventType;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void markPublished(Instant at) {
    publishedAt = at;
  }
}
