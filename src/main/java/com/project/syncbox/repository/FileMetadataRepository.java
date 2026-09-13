package com.project.syncbox.repository;

import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    Optional<FileMetadata> findByStoredName(String storedName);
    Optional<FileMetadata> findByUserAndStoredName(User user, String storedName);
    Optional<FileMetadata> findByUserAndFolderIsNullAndOriginalNameAndDeletedAtIsNull(User user, String originalName);
    Optional<FileMetadata> findByUserAndFolderIdAndOriginalNameAndDeletedAtIsNull(User user, Long folderId, String originalName);
    
    List<FileMetadata> findAllByUser(User user);
    List<FileMetadata> findAllByUserAndFolderIsNullAndDeletedAtIsNull(User user);
    List<FileMetadata> findAllByUserAndFolderIdAndDeletedAtIsNull(User user, Long folderId);
    
    @Query("SELECT f FROM FileMetadata f LEFT JOIN f.folder fd WHERE f.user = :user AND f.deletedAt IS NOT NULL AND (fd IS NULL OR fd.deletedAt IS NULL)")
    List<FileMetadata> findTopLevelTrashedFiles(@Param("user") User user);
    
    // For duplicate checking within root or specific folder
    boolean existsByUserAndFolderIsNullAndChecksumAndDeletedAtIsNull(User user, String checksum);
    boolean existsByUserAndFolderIdAndChecksumAndDeletedAtIsNull(User user, Long folderId, String checksum);

    // Get all files matching checksum (regardless of folder) to support generic deduplication/cleanup
    List<FileMetadata> findAllByUserAndChecksumAndDeletedAtIsNull(User user, String checksum);

    List<FileMetadata> findByDeletedAtBefore(java.time.LocalDateTime cutoff);
    
    // Used when a folder is soft-deleted
    List<FileMetadata> findAllByFolderIdAndDeletedAtIsNull(Long folderId);
    
    // Used when restoring a folder
    List<FileMetadata> findAllByFolderIdAndDeletedAtIsNotNull(Long folderId);
    
    // Used when a folder is permanently deleted
    List<FileMetadata> findAllByFolderId(Long folderId);
}
