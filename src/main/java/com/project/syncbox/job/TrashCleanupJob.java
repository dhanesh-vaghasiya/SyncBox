package com.project.syncbox.job;

import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.repository.FileMetadataRepository;
import com.project.syncbox.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TrashCleanupJob {

    private final FileMetadataRepository fileMetadataRepository;
    private final FileService fileService;
    private final com.project.syncbox.repository.FolderRepository folderRepository;

    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledCleanup() {
        cleanup();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startupCleanup() {
        cleanup();
    }

    private void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minus(30, ChronoUnit.DAYS);

        // find and permanently delete expired files
        List<FileMetadata> expiredFiles = fileMetadataRepository.findByDeletedAtBefore(cutoff);
        
        System.out.println("TrashCleanupJob: Found " + expiredFiles.size() + " expired files in trash.");
        
        for (FileMetadata metadata : expiredFiles) {
            try {
                fileService.permanentlyDeleteFile(metadata);
                System.out.println("Permanently deleted: " + metadata.getStoredName());
            } catch (IOException e) {
                System.err.println("Failed to permanently delete file: " + metadata.getStoredName());
                e.printStackTrace();
            }
        }

        // Delete expired folders
        List<com.project.syncbox.model.Folder> expiredFolders = folderRepository.findByDeletedAtBefore(cutoff);
        System.out.println("TrashCleanupJob: Found " + expiredFolders.size() + " expired folders in trash.");
        for (com.project.syncbox.model.Folder folder : expiredFolders) {
            try {
                folderRepository.delete(folder);
                System.out.println("Permanently deleted folder: " + folder.getName());
            } catch (Exception e) {
                System.err.println("Failed to permanently delete folder: " + folder.getName());
                e.printStackTrace();
            }
        }
    }
}
