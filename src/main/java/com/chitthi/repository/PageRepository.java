package com.chitthi.repository;

import com.chitthi.domain.entity.PageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, UUID> {

    List<PageEntity> findByDocumentIdOrderByPageNoAsc(UUID documentId);

    Optional<PageEntity> findByDocumentIdAndPageNo(UUID documentId, Integer pageNo);

    /**
     * Trigram fuzzy search across original Indic script scoped to document owner.
     */
    @Query(value = "SELECT p.* FROM page p JOIN document d ON p.document_id = d.id " +
            "WHERE d.owner_id = :ownerId AND (p.original_text % :query OR p.original_text ILIKE '%' || :query || '%') " +
            "ORDER BY similarity(p.original_text, :query) DESC LIMIT 50", nativeQuery = true)
    List<PageEntity> searchOriginalTextTrgm(@Param("ownerId") String ownerId, @Param("query") String query);

    /**
     * Postgres full-text search across translated English text scoped to document owner.
     */
    @Query(value = "SELECT p.* FROM page p JOIN document d ON p.document_id = d.id " +
            "WHERE d.owner_id = :ownerId AND p.text_search_en @@ plainto_tsquery('english', :query) LIMIT 50", nativeQuery = true)
    List<PageEntity> searchTranslatedTextFullText(@Param("ownerId") String ownerId, @Param("query") String query);
}
