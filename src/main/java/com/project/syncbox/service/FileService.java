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

@Service
public class FileService {

    private final String UPLOAD_DIR = "./uploads/";
    private final FileWebSocketHandler webSocketHandler;
    private final FileMetadataRepository fileMetadataRepository;
    private final UserRepository userRepository;
    private final com.project.syncbox.repository.UploadSessionRepository uploadSessionRepository;

    private final com.project.syncbox.repository.FolderRepository folderRepository;

    public FileService(FileWebSocketHandler webSocketHandler, 
                       FileMetadataRepository fileMetadataRepository, 
                       UserRepository userRepository,
                       com.project.syncbox.repository.UploadSessionRepository uploadSessionRepository,
                       com.project.syncbox.repository.FolderRepository folderRepository) {
        this.webSocketHandler = webSocketHandler;
        this.fileMetadataRepository = fileMetadataRepository;
        this.userRepository = userRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.folderRepository = folderRepository;
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
            Path finalPath = Paths.get(UPLOAD_DIR + storedFilename);
            Files.move(tempPath, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
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

    public boolean moveFile(String filename, Long targetFolderId, String username, String duplicateAction) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        FileMetadata metadata = fileMetadataRepository.findByUserAndStoredName(user, filename)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));

        if (checkFileExists(metadata.getChecksum(), username, targetFolderId)) {
            if ("NONE".equals(duplicateAction)) {
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                List<FileMetadata> allFilesInFolder = getFileList(username, targetFolderId);
                for (FileMetadata fm : allFilesInFolder) {
                    if (metadata.getChecksum().equals(fm.getChecksum()) && !fm.getId().equals(metadata.getId())) {
                        fm.setDeletedAt(LocalDateTime.now());
                        fileMetadataRepository.save(fm);
                    }
                }
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
        return true;
    }

    public FileMetadata copyFile(String filename, Long targetFolderId, String username, String duplicateAction) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        FileMetadata sourceMetadata = fileMetadataRepository.findByUserAndStoredName(user, filename)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));

        if (checkFileExists(sourceMetadata.getChecksum(), username, targetFolderId)) {
            if ("NONE".equals(duplicateAction)) {
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                List<FileMetadata> allFilesInFolder = getFileList(username, targetFolderId);
                for (FileMetadata fm : allFilesInFolder) {
                    if (sourceMetadata.getChecksum().equals(fm.getChecksum())) {
                        fm.setDeletedAt(LocalDateTime.now());
                        fileMetadataRepository.save(fm);
                    }
                }
            }
        }

        Folder targetFolder = null;
        if (targetFolderId != null) {
            targetFolder = folderRepository.findByIdAndUserAndDeletedAtIsNull(targetFolderId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Target folder not found"));
        }

        String newStoredName = getUniqueFilename(sourceMetadata.getOriginalName(), user);
        
        Path sourcePath = Paths.get(UPLOAD_DIR).resolve(sourceMetadata.getStoredName());
        Path targetPath = Paths.get(UPLOAD_DIR).resolve(newStoredName);
        Files.copy(sourcePath, targetPath);

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

    public boolean checkFileExists(String checksum, String username, Long folderId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (folderId == null) {
            return fileMetadataRepository.existsByUserAndFolderIsNullAndChecksumAndDeletedAtIsNull(user, checksum);
        } else {
            return fileMetadataRepository.existsByUserAndFolderIdAndChecksumAndDeletedAtIsNull(user, folderId, checksum);
        }
    }

    public java.util.List<com.project.syncbox.dto.PreflightResponseDTO> preflightBatch(
            java.util.List<com.project.syncbox.dto.PreflightRequestDTO> requests, String username) {
        java.util.List<com.project.syncbox.dto.PreflightResponseDTO> responses = new java.util.ArrayList<>();
        
        for (com.project.syncbox.dto.PreflightRequestDTO req : requests) {
            boolean conflict = checkFileExists(req.getChecksum(), username, req.getFolderId());
            com.project.syncbox.dto.PreflightResponseDTO res = new com.project.syncbox.dto.PreflightResponseDTO();
            res.setClientId(req.getClientId());
            res.setFileName(req.getFileName());
            res.setChecksum(req.getChecksum());
            res.setStatus(conflict ? "SERVER_CONFLICT" : "NEW");
            responses.add(res);
        }
        
        return responses;
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
                // Keep the temp dir and merged file so they can retry
                throw new DuplicateFileException("DUPLICATE_CHECKSUM");
            } else if ("REPLACE".equals(duplicateAction)) {
                // Find and soft-delete existing files with this checksum in this folder
                java.util.List<FileMetadata> allFilesInFolder = getFileList(username, folderId);
                for (FileMetadata fm : allFilesInFolder) {
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
        metadata.setFolder(session.getFolder());
        fileMetadataRepository.save(metadata);

        uploadSessionRepository.delete(session);

        webSocketHandler.broadcastFileUpdate();
    }
}
