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

    public FileService(FileWebSocketHandler webSocketHandler, FileMetadataRepository fileMetadataRepository, UserRepository userRepository) {
        this.webSocketHandler = webSocketHandler;
        this.fileMetadataRepository = fileMetadataRepository;
        this.userRepository = userRepository;
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
        
        if (!force && fileMetadataRepository.existsByUserAndChecksum(user, checksum)) {
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
        return fileMetadataRepository.findAllByUser(user);
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

    public boolean deleteFile(String filename, String username) throws IOException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
                
        Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByUserAndStoredName(user, filename);
        if (metadataOpt.isPresent()) {
            Path file = Paths.get(UPLOAD_DIR).resolve(filename);
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                fileMetadataRepository.delete(metadataOpt.get());
                webSocketHandler.broadcastFileUpdate();
            }
            return deleted;
        }
        return false;
    }
}
