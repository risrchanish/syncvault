package com.syncvault.fileservice.repository;

import com.syncvault.fileservice.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    List<FileMetadata> findByUserIdAndDeletedFalse(UUID userId);

    Optional<FileMetadata> findByIdAndDeletedFalse(UUID id);

    @Query("SELECT COALESCE(SUM(f.fileSizeBytes), 0) FROM FileMetadata f WHERE f.userId = :userId AND f.deleted = false")
    long sumFileSizeByUserId(@Param("userId") UUID userId);
}
