package com.project.syncbox.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "folders", uniqueConstraints = {
    // A folder name must be unique within its parent folder for a given user.
    // Note: if parent_id is null (root level), standard unique constraints might treat multiple nulls differently depending on DB.
    // PostgreSQL treats nulls as distinct, so we'll need to handle root-level uniqueness in the service layer if necessary.
    @UniqueConstraint(name = "idx_user_parent_name", columnNames = {"user_id", "parent_id", "name"})
})
@Data
@NoArgsConstructor
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Folder parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
