package com.project.syncbox.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "file_metadata",
    indexes = {
        @Index(name = "idx_user_checksum", columnList = "user_id, checksum"),
        @Index(name = "idx_folder_id", columnList = "folder_id")
    },
    uniqueConstraints = {
        // A file name must be unique within its parent folder for a given user.
        // We'll enforce this primarily in the service layer since folder_id can be null,
        // but it's good to note.
    }
)
@Data
@NoArgsConstructor
public class FileMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "stored_name", nullable = false, unique = true)
    private String storedName;

    @Column(nullable = false)
    private Long size;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "checksum")
    private String checksum;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private Folder folder;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

}
