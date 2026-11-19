package com.hotelsaas.common.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
  @Id
  @Column(nullable = false, updatable = false)
  private UUID id = UUID.randomUUID();

  @Column(name = "tenant_id", length = 14, nullable = false)
  private String tenantId;

  @CreatedBy
  @Column(name = "created_by", updatable = false)
  private String createdBy;

  @CreatedDate
  @Column(name = "created_date", updatable = false)
  private Instant createdDate;

  @LastModifiedBy
  @Column(name = "updated_by")
  private String updatedBy;

  @LastModifiedDate
  @Column(name = "updated_date")
  private Instant updatedDate;

  @Column(nullable = false, length = 20)
  private String status = "ACTIVE";

  @Version private long version;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public UUID getId() {
    return id;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String v) {
    tenantId = v;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedDate() {
    return createdDate;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public Instant getUpdatedDate() {
    return updatedDate;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public long getVersion() {
    return version;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant v) {
    deletedAt = v;
  }
}
