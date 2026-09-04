package com.project.syncbox.repository;

import com.project.syncbox.model.UploadSession;
import com.project.syncbox.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {
    Optional<UploadSession> findByIdAndUser(String id, User user);
}
