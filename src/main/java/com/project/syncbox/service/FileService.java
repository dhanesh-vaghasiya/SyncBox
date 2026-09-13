package com.project.syncbox.service;

import com.project.syncbox.exception.DuplicateFileException;
import com.project.syncbox.handler.FileWebSocketHandler;
import com.project.syncbox.model.FileMetadata;
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

@Service
public class FileService {

    private final String UPLOAD_DIR = "./uploads/";
    private final FileWebSocketHandler webSocketHandler;
    private final FileMetadataRepository fileMetadataRepository;
    private final UserRepository userRepository;
    private final com.project.syncbox.repository.UploadSessionRepository uploadSessionRepository;

    public FileService(FileWebSocketHandler webSocketHandler, 
                       FileMetadataRepository fileMetadataRepository, 
                       UserRepository userRepository,
                       com.project.syncbox.repository.UploadSessionRepository uploadSessionRepository) {
        this.webSocketHandler = webSocketHandler;
        this.fileMetadataRepository = fileMetadataRepository;
        this.userRepository = userRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        File directory = new File(UPLOAD_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void saveFile(MultipartFile file, String username, boolean force) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot save empty file.");
        }
        
        byte[] bytes = file.getBytes();
        
        // Calculate checksum
        String checksum = calculateChecksum(bytes);
        
        if (!force && fileMetadataRepository.existsByUserAndChecksumAndDeletedAtIsNull(user, checksum)) {
            throw new DuplicateFileException("DUPLICATE_CHECKSUM");
        }
        
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "unknown";
        }
        
        String storedFilename = getUniqueFilename(originalFilename, user);
        
        Path path = Paths.get(UPLOAD_DIR + storedFilename);
        Files.write(path, bytes);
        
        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalName(originalFilename);
        metadata.setStoredName(storedFilename);
        metadata.setSize(file.getSize());
        metadata.setContentType(file.getContentType());
        metadata.setUploadedAt(LocalDateTime.now());
        metadata.setUser(user);
        metadata.setChecksum(checksum);
        
        fileMetadataRepository.save(metadata);
        
        // Broadcast the update to all connected clients
        webSocketHandler.broadcastFileUpdate();
    }
    
    private String calculateChecksum(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Could not calculate checksum", e);
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

    public List<FileMetadata> getFileList(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return fileMetadataRepository.findAllByUserAndDeletedAtIsNull(user);
    }

    public List<FileMetadata> getTrashedFileList(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return fileMetadataRepository.findAllByUserAndDeletedAtIsNotNull(user);
    }

    public Resource loadFileAsResource(String filename, String username) throws MalformedURLException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                
        Optional<FileMetadata> metadata = fileMetadataRepository.findByUserAndStoredName(user, filename);
        if (metadata.isEmpty()) {
            return null; // File doesn't exist or doesn't belong to the user
        }

        Path file = Paths.get(UPLOAD_DIR).resolve(filename);
        Resource resource = new UrlResource(file.toUri());

        if (resource.exists() || resource.isReadable()) {
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
            return true;
        }
        return false;
    }

    public void permanentlyDeleteFile(FileMetadata metadata) throws IOException {
        Path file = Paths.get(UPLOAD_DIR).resolve(metadata.getStoredName());
        Files.deleteIfExists(file); // Ignore result, the file might already be missing from disk
        fileMetadataRepository.delete(metadata);
    }

    public com.project.syncbox.model.UploadSession createUploadSession(String originalFilename, String contentType, long totalSize, int totalChunks, String username) throws IOException {
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

        uploadSessionRepository.save(session);

        // Create temporary directory for chunks
        Path tempDir = Paths.get(UPLOAD_DIR + "temp_" + session.getId());
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        return session;
    }

    public void uploadChunk(String sessionId, int chunkIndex, MultipartFile file, String username) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        com.project.syncbox.model.UploadSession session = uploadSessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        Path tempDir = Paths.get(UPLOAD_DIR + "temp_" + session.getId());
        if (!Files.exists(tempDir)) {
            throw new IOException("Temporary directory for session not found.");
        }

        Path chunkPath = tempDir.resolve("part_" + chunkIndex);
        Files.write(chunkPath, file.getBytes());
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

    public boolean checkFileExists(String checksum, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return fileMetadataRepository.existsByUserAndChecksumAndDeletedAtIsNull(user, checksum);
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

        // Calculate Checksum
        byte[] mergedBytes = Files.readAllBytes(mergedFilePath);
        String checksum = calculateChecksum(mergedBytes);

        if (fileMetadataRepository.existsByUserAndChecksumAndDeletedAtIsNull(user, checksum)) {
            if ("NONE".equals(duplicateAction)) {
                // Keep the temp dir and merged file so they can retry
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                // Find and soft-delete existing files with this checksum
                java.util.List<FileMetadata> allFiles = fileMetadataRepository.findAllByUserAndDeletedAtIsNull(user);
                for (FileMetadata fm : allFiles) {
                    if (checksum.equals(fm.getChecksum())) {
                        fm.setDeletedAt(LocalDateTime.now());
                        fileMetadataRepository.save(fm);
                    }
                }
            }
            // If "KEEP_BOTH", we just proceed and generate a new file entry
        }

        // Generate final stored name
        String storedFilename = getUniqueFilename(session.getOriginalName(), user);
        Path finalPath = Paths.get(UPLOAD_DIR + storedFilename);

        // Move merged file to final path
        Files.move(mergedFilePath, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Cleanup chunks
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Files.deleteIfExists(tempDir.resolve("part_" + i));
        }
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
        fileMetadataRepository.save(metadata);

        uploadSessionRepository.delete(session);

        webSocketHandler.broadcastFileUpdate();
    }
}
