package com.chitthi.repository;

import com.chitthi.domain.entity.ApiCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ApiCallRepository extends JpaRepository<ApiCallEntity, UUID> {
    List<ApiCallEntity> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);

    @Query("SELECT COALESCE(SUM(a.estCostInr), 0) FROM ApiCallEntity a WHERE a.documentId = :documentId")
    BigDecimal sumCostByDocumentId(@Param("documentId") UUID documentId);
}
