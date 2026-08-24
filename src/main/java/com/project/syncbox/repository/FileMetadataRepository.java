package com.project.syncbox.repository;

import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    Optional<FileMetadata> findByStoredName(String storedName);
    Optional<FileMetadata> findByUserAndStoredName(User user, String storedName);
    List<FileMetadata> findAllByUser(User user);
}
