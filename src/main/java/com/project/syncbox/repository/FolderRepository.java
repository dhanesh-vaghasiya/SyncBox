package com.project.syncbox.repository;

import com.project.syncbox.model.Folder;
import com.project.syncbox.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {
    List<Folder> findAllByUserAndParentIsNullAndDeletedAtIsNull(User user);
    List<Folder> findAllByUserAndParentIdAndDeletedAtIsNull(User user, Long parentId);
    List<Folder> findAllByUserAndDeletedAtIsNull(User user);
    
    @Query("SELECT f FROM Folder f LEFT JOIN f.parent p WHERE f.user = :user AND f.deletedAt IS NOT NULL AND (p IS NULL OR p.deletedAt IS NULL)")
    List<Folder> findTopLevelTrashedFolders(@Param("user") User user);
    
    Optional<Folder> findByIdAndUserAndDeletedAtIsNull(Long id, User user);
    Optional<Folder> findByIdAndUser(Long id, User user);

    boolean existsByUserAndParentIsNullAndNameAndDeletedAtIsNull(User user, String name);
    boolean existsByUserAndParentIdAndNameAndDeletedAtIsNull(User user, Long parentId, String name);

    List<Folder> findAllByParentIdAndDeletedAtIsNull(Long parentId);
    List<Folder> findAllByParentIdAndDeletedAtIsNotNull(Long parentId);
    List<Folder> findAllByParentId(Long parentId);
    
    List<Folder> findByDeletedAtBefore(LocalDateTime cutoff);
}
