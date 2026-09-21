package com.chitthi.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ocr_batch")
public class OcrBatchEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "page_range", nullable = false, length = 50)
    private String pageRange;

    @Column(name = "sarvam_job_id")
    private String sarvamJobId;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "poll_count", nullable = false)
    private Integer pollCount = 0;

    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public OcrBatchEntity() {}

    public OcrBatchEntity(UUID id, DocumentEntity document, String pageRange, String sarvamJobId, String status) {
        this.id = id;
        this.document = document;
        this.pageRange = pageRange;
        this.sarvamJobId = sarvamJobId;
        this.status = status;
        this.pollCount = 0;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DocumentEntity getDocument() { return document; }
    public void setDocument(DocumentEntity document) { this.document = document; }

    public String getPageRange() { return pageRange; }
    public void setPageRange(String pageRange) { this.pageRange = pageRange; }

    public String getSarvamJobId() { return sarvamJobId; }
    public void setSarvamJobId(String sarvamJobId) { this.sarvamJobId = sarvamJobId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getPollCount() { return pollCount; }
    public void setPollCount(Integer pollCount) { this.pollCount = pollCount; }

    public Instant getNextPollAt() { return nextPollAt; }
    public void setNextPollAt(Instant nextPollAt) { this.nextPollAt = nextPollAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
