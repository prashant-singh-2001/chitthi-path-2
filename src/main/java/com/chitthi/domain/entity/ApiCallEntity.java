package com.chitthi.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_call")
public class ApiCallEntity {

    @Id
    private UUID id;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal units;

    @Column(name = "unit_type", nullable = false, length = 50)
    private String unitType;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    @Column(name = "http_status", nullable = false)
    private Integer httpStatus;

    @Column(name = "est_cost_inr", nullable = false, precision = 10, scale = 4)
    private BigDecimal estCostInr;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public ApiCallEntity() {}

    public ApiCallEntity(UUID id, UUID documentId, String endpoint, BigDecimal units, String unitType, Long latencyMs, Integer httpStatus, BigDecimal estCostInr) {
        this.id = id;
        this.documentId = documentId;
        this.endpoint = endpoint;
        this.units = units;
        this.unitType = unitType;
        this.latencyMs = latencyMs;
        this.httpStatus = httpStatus;
        this.estCostInr = estCostInr;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public BigDecimal getUnits() { return units; }
    public void setUnits(BigDecimal units) { this.units = units; }

    public String getUnitType() { return unitType; }
    public void setUnitType(String unitType) { this.unitType = unitType; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public BigDecimal getEstCostInr() { return estCostInr; }
    public void setEstCostInr(BigDecimal estCostInr) { this.estCostInr = estCostInr; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
