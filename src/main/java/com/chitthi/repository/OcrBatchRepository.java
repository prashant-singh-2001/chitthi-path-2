package com.chitthi.repository;

import com.chitthi.domain.entity.OcrBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OcrBatchRepository extends JpaRepository<OcrBatchEntity, UUID> {
    List<OcrBatchEntity> findByDocumentId(UUID documentId);
    Optional<OcrBatchEntity> findBySarvamJobId(String sarvamJobId);
    List<OcrBatchEntity> findByStatusInAndNextPollAtBefore(List<String> statuses, Instant now);
}
