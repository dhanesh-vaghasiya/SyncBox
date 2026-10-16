package com.project.syncbox.service;

import com.project.syncbox.exception.DuplicateFileException;
import com.project.syncbox.handler.FileWebSocketHandler;
import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.model.Folder;
import com.project.syncbox.model.User;
import com.project.syncbox.repository.FileMetadataRepository;
import com.project.syncbox.repository.UserRepository;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.project.syncbox.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final String UPLOAD_DIR = "./uploads/";
    private final FileWebSocketHandler webSocketHandler;
    private final FileMetadataRepository fileMetadataRepository;
    private final UserRepository userRepository;
    private final com.project.syncbox.repository.UploadSessionRepository uploadSessionRepository;

    private final com.project.syncbox.repository.FolderRepository folderRepository;
    private final StorageService storageService;

    public FileService(FileWebSocketHandler webSocketHandler, 
                       FileMetadataRepository fileMetadataRepository, 
                       UserRepository userRepository,
                       com.project.syncbox.repository.UploadSessionRepository uploadSessionRepository,
                       com.project.syncbox.repository.FolderRepository folderRepository,
                       StorageService storageService) {
        this.webSocketHandler = webSocketHandler;
        this.fileMetadataRepository = fileMetadataRepository;
        this.userRepository = userRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.folderRepository = folderRepository;
        this.storageService = storageService;
        File directory = new File(UPLOAD_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void saveFile(MultipartFile file, String username, boolean force, Long folderId) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot save empty file.");
        }
        
        Path tempPath = Paths.get(UPLOAD_DIR, java.util.UUID.randomUUID().toString() + ".tmp");
        file.transferTo(tempPath.toFile());
        
        try {
            String checksum = calculateChecksum(tempPath);
            
            if (!force && checkFileExists(checksum, username, folderId)) {
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            }
            
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "unknown";
            }
            
            String storedFilename = getUniqueFilename(originalFilename, user);
            storageService.uploadFile(storedFilename, tempPath, file.getContentType());
            
            FileMetadata metadata = new FileMetadata();
            metadata.setOriginalName(originalFilename);
            metadata.setStoredName(storedFilename);
            metadata.setSize(file.getSize());
            metadata.setContentType(file.getContentType());
            metadata.setUploadedAt(LocalDateTime.now());
            metadata.setUser(user);
            metadata.setChecksum(checksum);
            if (folderId != null) {
                Folder folder = folderRepository.findByIdAndUserAndDeletedAtIsNull(folderId, user)
                        .orElseThrow(() -> new IllegalArgumentException("Folder not found"));
                metadata.setFolder(folder);
            }
            
            fileMetadataRepository.save(metadata);
            
            webSocketHandler.broadcastFileUpdate();
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }
    
    private String calculateChecksum(byte[] bytes) {
        try {
            int L = bytes.length;
            int CHUNK_SIZE = 1048576; // 1MB
            byte[] bufferToHash;

            if (L <= 3 * CHUNK_SIZE) {
                bufferToHash = bytes;
            } else {
                bufferToHash = new byte[3 * CHUNK_SIZE];
                System.arraycopy(bytes, 0, bufferToHash, 0, CHUNK_SIZE);
                int mid = L / 2;
                System.arraycopy(bytes, mid - (CHUNK_SIZE / 2), bufferToHash, CHUNK_SIZE, CHUNK_SIZE);
                System.arraycopy(bytes, L - CHUNK_SIZE, bufferToHash, 2 * CHUNK_SIZE, CHUNK_SIZE);
            }

            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bufferToHash);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to calculate checksum", e);
        }
    }

    private String calculateChecksum(Path path) throws IOException {
        try {
            long L = Files.size(path);
            int CHUNK_SIZE = 1048576; // 1MB
            byte[] bufferToHash;

            if (L <= 3 * CHUNK_SIZE) {
                bufferToHash = Files.readAllBytes(path);
            } else {
                bufferToHash = new byte[3 * CHUNK_SIZE];
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path.toFile(), "r")) {
                    // First 1MB
                    raf.seek(0);
                    raf.readFully(bufferToHash, 0, CHUNK_SIZE);
                    // Middle 1MB
                    long mid = L / 2;
                    raf.seek(mid - (CHUNK_SIZE / 2));
                    raf.readFully(bufferToHash, CHUNK_SIZE, CHUNK_SIZE);
                    // Last 1MB
                    raf.seek(L - CHUNK_SIZE);
                    raf.readFully(bufferToHash, 2 * CHUNK_SIZE, CHUNK_SIZE);
                }
            }

            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bufferToHash);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to calculate checksum", e);
        }
    }
    
    private String getUniqueFilename(String originalFilename, User user) {
        String baseName = originalFilename;
        String extension = "";
        
        int dotIndex = originalFilename.lastIndexOf(".");
        if (dotIndex > 0) {
            baseName = originalFilename.substring(0, dotIndex);
            extension = originalFilename.substring(dotIndex);
        }
        
        String newFilename = originalFilename;
        int counter = 1;
        while (fileMetadataRepository.findByUserAndStoredName(user, newFilename).isPresent()) {
            newFilename = baseName + "[" + counter + "]" + extension;
            counter++;
        }
        return newFilename;
    }

    public List<FileMetadata> getFileList(String username, Long folderId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (folderId == null) {
            return fileMetadataRepository.findAllByUserAndFolderIsNullAndDeletedAtIsNull(user);
        } else {
            return fileMetadataRepository.findAllByUserAndFolderIdAndDeletedAtIsNull(user, folderId);
        }
    }

    public List<FileMetadata> getRecentFiles(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return fileMetadataRepository.findTop20ByUserAndDeletedAtIsNullOrderByUploadedAtDesc(user);
    }

    public List<FileMetadata> getTrashedFileList(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return fileMetadataRepository.findTopLevelTrashedFiles(user);
    }

    public Resource loadFileAsResource(String filename, String username) throws MalformedURLException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                
        Optional<FileMetadata> metadata = fileMetadataRepository.findByUserAndStoredName(user, filename);
        if (metadata.isEmpty()) {
            return null; // File doesn't exist or doesn't belong to the user
        }

        Resource resource = storageService.loadAsResource(
                metadata.get().getStoredName(), 
                metadata.get().getOriginalName(), 
                metadata.get().getSize());

        if (resource != null && (resource.exists() || resource.isReadable())) {
            log.info("Download file userId={} file={}", user.getId(), metadata.get().getOriginalName());
            return resource;
        } else {
            return null;
        }
    }

    public boolean deleteFile(String filename, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                
        Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByUserAndStoredName(user, filename);
        if (metadataOpt.isPresent()) {
            FileMetadata metadata = metadataOpt.get();
            metadata.setDeletedAt(LocalDateTime.now());
            fileMetadataRepository.save(metadata);
            webSocketHandler.broadcastFileUpdate();
            log.info("Delete file userId={} file={}", user.getId(), metadata.getOriginalName());
            return true;
        }
        return false;
    }

    public boolean restoreFile(String filename, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                
        Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByUserAndStoredName(user, filename);
        if (metadataOpt.isPresent()) {
            FileMetadata metadata = metadataOpt.get();
            metadata.setDeletedAt(null);
            fileMetadataRepository.save(metadata);
            webSocketHandler.broadcastFileUpdate();
            log.info("Restore file userId={} file={}", user.getId(), metadata.getOriginalName());
            return true;
        }
        return false;
    }

    public boolean permanentlyDeleteFile(String filename, String username) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                
        Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByUserAndStoredName(user, filename);
        if (metadataOpt.isPresent()) {
            permanentlyDeleteFile(metadataOpt.get());
            webSocketHandler.broadcastFileUpdate();
            log.info("Permanent delete file userId={} file={}", user.getId(), metadataOpt.get().getOriginalName());
            return true;
        }
        return false;
    }

    public void permanentlyDeleteFile(FileMetadata metadata) throws IOException {
        storageService.deleteFile(metadata.getStoredName());
        fileMetadataRepository.delete(metadata);
    }

    public boolean moveFile(String filename, Long targetFolderId, String username, String duplicateAction) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        FileMetadata metadata = fileMetadataRepository.findByUserAndStoredName(user, filename)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));

        if (checkFileExists(metadata.getChecksum(), username, targetFolderId)) {
            if ("NONE".equals(duplicateAction)) {
                log.info("Move conflict file={} duplicateAction=NONE", metadata.getOriginalName());
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                log.info("Move conflict file={} duplicateAction=REPLACE", metadata.getOriginalName());
                List<FileMetadata> allFilesInFolder = getFileList(username, targetFolderId);
                for (FileMetadata fm : allFilesInFolder) {
                    if (metadata.getChecksum().equals(fm.getChecksum()) && !fm.getId().equals(metadata.getId())) {
                        fm.setDeletedAt(LocalDateTime.now());
                        fileMetadataRepository.save(fm);
                    }
                }
            } else {
                log.info("Move conflict file={} duplicateAction=KEEP_BOTH", metadata.getOriginalName());
            }
            // KEEP_BOTH does nothing, just proceeds to move
        }

        if (targetFolderId != null) {
            Folder targetFolder = folderRepository.findByIdAndUserAndDeletedAtIsNull(targetFolderId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Target folder not found"));
            metadata.setFolder(targetFolder);
        } else {
            metadata.setFolder(null);
        }

        fileMetadataRepository.save(metadata);
        webSocketHandler.broadcastFileUpdate();
        log.info("Move file userId={} file={} toFolderId={}", user.getId(), metadata.getOriginalName(), targetFolderId);
        return true;
    }

    public FileMetadata copyFile(String filename, Long targetFolderId, String username, String duplicateAction) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        FileMetadata sourceMetadata = fileMetadataRepository.findByUserAndStoredName(user, filename)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));

        if (checkFileExists(sourceMetadata.getChecksum(), username, targetFolderId)) {
            if ("NONE".equals(duplicateAction)) {
                log.info("Copy conflict file={} duplicateAction=NONE", sourceMetadata.getOriginalName());
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                log.info("Copy conflict file={} duplicateAction=REPLACE", sourceMetadata.getOriginalName());
                List<FileMetadata> allFilesInFolder = getFileList(username, targetFolderId);
                for (FileMetadata fm : allFilesInFolder) {
                    if (sourceMetadata.getChecksum().equals(fm.getChecksum())) {
                        fm.setDeletedAt(LocalDateTime.now());
                        fileMetadataRepository.save(fm);
                    }
                }
            } else {
                log.info("Copy conflict file={} duplicateAction=KEEP_BOTH", sourceMetadata.getOriginalName());
            }
        }

        Folder targetFolder = null;
        if (targetFolderId != null) {
            targetFolder = folderRepository.findByIdAndUserAndDeletedAtIsNull(targetFolderId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Target folder not found"));
        }

        String newStoredName = getUniqueFilename(sourceMetadata.getOriginalName(), user);
        storageService.copyFile(sourceMetadata.getStoredName(), newStoredName);

        FileMetadata newMetadata = new FileMetadata();
        newMetadata.setOriginalName(sourceMetadata.getOriginalName());
        newMetadata.setStoredName(newStoredName);
        newMetadata.setSize(sourceMetadata.getSize());
        newMetadata.setContentType(sourceMetadata.getContentType());
        newMetadata.setUploadedAt(LocalDateTime.now());
        newMetadata.setUser(user);
        newMetadata.setChecksum(sourceMetadata.getChecksum());
        newMetadata.setFolder(targetFolder);

        fileMetadataRepository.save(newMetadata);
        webSocketHandler.broadcastFileUpdate();
        log.info("Copy file userId={} file={} toFolderId={}", user.getId(), newMetadata.getOriginalName(), targetFolderId);
        return newMetadata;
    }

    // Overloaded method for recursive copyFolder without passing duplicateAction (defaults to KEEP_BOTH for folder structure copying)
    public FileMetadata copyFile(String filename, Long targetFolderId, String username) throws IOException {
        return copyFile(filename, targetFolderId, username, "KEEP_BOTH");
    }
    public com.project.syncbox.model.UploadSession createUploadSession(String originalFilename, String contentType, long totalSize, int totalChunks, String username, Long folderId) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (originalFilename == null) {
            originalFilename = "unknown";
        }

        com.project.syncbox.model.UploadSession session = new com.project.syncbox.model.UploadSession();
        session.setId(java.util.UUID.randomUUID().toString());
        session.setUser(user);
        session.setOriginalName(originalFilename);
        session.setContentType(contentType);
        session.setTotalSize(totalSize);
        session.setTotalChunks(totalChunks);
        session.setCreatedAt(LocalDateTime.now());
        
        if (folderId != null) {
            Folder folder = folderRepository.findByIdAndUserAndDeletedAtIsNull(folderId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Folder not found"));
            session.setFolder(folder);
        }

        uploadSessionRepository.save(session);

        // Create temporary directory for chunks
        Path tempDir = Paths.get(UPLOAD_DIR + "temp_" + session.getId());
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        log.info("Upload session created uploadId={} userId={} file={}", session.getId(), user.getId(), session.getOriginalName());
        return session;
    }

    public void uploadChunk(String sessionId, int chunkIndex, MultipartFile file, String username) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        com.project.syncbox.model.UploadSession session = uploadSessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        Path tempDir = Paths.get(UPLOAD_DIR + "temp_" + session.getId());
        if (!Files.exists(tempDir)) {
            log.error("Chunk upload failure uploadId={} chunk={}/{} reason=\"Temp dir not found\"", session.getId(), chunkIndex + 1, session.getTotalChunks());
            throw new IOException("Temporary directory for session not found.");
        }

        Path chunkPath = tempDir.resolve("part_" + chunkIndex);
        Files.write(chunkPath, file.getBytes());
        log.info("Chunk uploaded uploadId={} chunk={}/{}", session.getId(), chunkIndex + 1, session.getTotalChunks());
    }

    public com.project.syncbox.dto.SessionStatusDTO getSessionStatus(String sessionId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        com.project.syncbox.model.UploadSession session = uploadSessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        Path tempDir = Paths.get(UPLOAD_DIR + "temp_" + session.getId());
        java.util.List<Integer> uploadedChunks = new java.util.ArrayList<>();

        if (Files.exists(tempDir)) {
            for (int i = 0; i < session.getTotalChunks(); i++) {
                if (Files.exists(tempDir.resolve("part_" + i))) {
                    uploadedChunks.add(i);
                }
            }
        }
        
        return new com.project.syncbox.dto.SessionStatusDTO(session, uploadedChunks);
    }

    public boolean checkFileExists(String checksum, String username, Long folderId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (folderId == null) {
            return fileMetadataRepository.existsByUserAndFolderIsNullAndChecksumAndDeletedAtIsNull(user, checksum);
        } else {
            return fileMetadataRepository.existsByUserAndFolderIdAndChecksumAndDeletedAtIsNull(user, folderId, checksum);
        }
    }

    public void completeUpload(String sessionId, String duplicateAction, String username) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        com.project.syncbox.model.UploadSession session = uploadSessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        Path tempDir = Paths.get(UPLOAD_DIR + "temp_" + session.getId());
        if (!Files.exists(tempDir)) {
            throw new IOException("Temporary directory for session not found.");
        }

        // Verify all chunks
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Path chunkPath = tempDir.resolve("part_" + i);
            if (!Files.exists(chunkPath)) {
                throw new IllegalArgumentException("Missing chunk: " + i);
            }
        }

        // Merge chunks
        Path mergedFilePath = tempDir.resolve("merged_file");
        try (java.io.OutputStream out = Files.newOutputStream(mergedFilePath)) {
            for (int i = 0; i < session.getTotalChunks(); i++) {
                Path chunkPath = tempDir.resolve("part_" + i);
                Files.copy(chunkPath, out);
            }
        }

        // Calculate Checksum directly from disk stream
        String checksum = calculateChecksum(mergedFilePath);

        Long folderId = session.getFolder() != null ? session.getFolder().getId() : null;

        if (checkFileExists(checksum, username, folderId)) {
            if ("NONE".equals(duplicateAction)) {
                log.info("Upload conflict uploadId={} file={} duplicateAction=NONE", session.getId(), session.getOriginalName());
                // Keep the temp dir and merged file so they can retry
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                log.info("Upload conflict uploadId={} file={} duplicateAction=REPLACE", session.getId(), session.getOriginalName());
                // Find and soft-delete existing files with this checksum in this folder
                java.util.List<FileMetadata> allFilesInFolder = getFileList(username, folderId);
                for (FileMetadata fm : allFilesInFolder) {
                    if (checksum.equals(fm.getChecksum())) {
                        fm.setDeletedAt(LocalDateTime.now());
                        fileMetadataRepository.save(fm);
                    }
                }
            } else {
                log.info("Upload conflict uploadId={} file={} duplicateAction=KEEP_BOTH", session.getId(), session.getOriginalName());
            }
            // If "KEEP_BOTH", we just proceed and generate a new file entry
        }

        // Generate final stored name
        String storedFilename = getUniqueFilename(session.getOriginalName(), user);

        // Upload merged file to storage (Backblaze B2 cloud storage or local)
        storageService.uploadFile(storedFilename, mergedFilePath, session.getContentType());

        // Cleanup chunks
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Files.deleteIfExists(tempDir.resolve("part_" + i));
        }
        Files.deleteIfExists(mergedFilePath);
        Files.deleteIfExists(tempDir);

        // Save FileMetadata
        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalName(session.getOriginalName());
        metadata.setStoredName(storedFilename);
        metadata.setSize(session.getTotalSize());
        metadata.setContentType(session.getContentType());
        metadata.setUploadedAt(LocalDateTime.now());
        metadata.setUser(user);
        metadata.setChecksum(checksum);
        metadata.setFolder(session.getFolder());
        fileMetadataRepository.save(metadata);

        uploadSessionRepository.delete(session);

        webSocketHandler.broadcastFileUpdate();
        
        long durationMs = java.time.Duration.between(session.getCreatedAt(), LocalDateTime.now()).toMillis();
        log.info("Upload completed uploadId={} userId={} durationMs={}", session.getId(), user.getId(), durationMs);
    }
}
