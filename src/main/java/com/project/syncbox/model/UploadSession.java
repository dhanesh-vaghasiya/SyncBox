package com.project.syncbox.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "upload_sessions")
@Data
@NoArgsConstructor
public class UploadSession {

    @Id
    @Column(nullable = false, unique = true)
    private String id; // UUID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "total_size", nullable = false)
    private Long totalSize;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
