package com.chitthi.repository;

import com.chitthi.domain.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
    List<DocumentEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    Optional<DocumentEntity> findByIdAndOwnerId(UUID id, String ownerId);
}
