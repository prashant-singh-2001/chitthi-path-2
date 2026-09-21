package com.chitthi.repository;

import com.chitthi.domain.entity.StageTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StageTaskRepository extends JpaRepository<StageTaskEntity, UUID> {
    Optional<StageTaskEntity> findByIdempotencyKey(String idempotencyKey);
    List<StageTaskEntity> findByPageId(UUID pageId);
    List<StageTaskEntity> findByPageIdAndStage(UUID pageId, String stage);
    List<StageTaskEntity> findByStatus(String status);
}
