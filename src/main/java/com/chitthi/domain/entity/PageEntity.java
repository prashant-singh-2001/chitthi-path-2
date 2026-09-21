package com.chitthi.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "page", uniqueConstraints = {
        @UniqueConstraint(name = "uq_document_page", columnNames = {"document_id", "page_no"})
})
public class PageEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Column(name = "image_key", nullable = false, length = 500)
    private String imageKey;

    @Column(nullable = false, length = 50)
    private String status;

    @Column(name = "original_text", columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "translated_text", columnDefinition = "TEXT")
    private String translatedText;

    @Column(name = "text_hash", length = 64)
    private String textHash;

    @Column(nullable = false)
    private Boolean edited = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public PageEntity() {}

    public PageEntity(UUID id, DocumentEntity document, Integer pageNo, String imageKey, String status) {
        this.id = id;
        this.document = document;
        this.pageNo = pageNo;
        this.imageKey = imageKey;
        this.status = status;
        this.edited = false;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DocumentEntity getDocument() { return document; }
    public void setDocument(DocumentEntity document) { this.document = document; }

    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }

    public String getImageKey() { return imageKey; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }

    public String getTranslatedText() { return translatedText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }

    public String getTextHash() { return textHash; }
    public void setTextHash(String textHash) { this.textHash = textHash; }

    public Boolean getEdited() { return edited; }
    public void setEdited(Boolean edited) { this.edited = edited; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
