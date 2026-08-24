package com.project.syncbox.service;

import com.project.syncbox.handler.FileWebSocketHandler;
import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.repository.FileMetadataRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class FileService {

    private final String UPLOAD_DIR = "./uploads/";
    private final FileWebSocketHandler webSocketHandler;
    private final FileMetadataRepository fileMetadataRepository;

    public FileService(FileWebSocketHandler webSocketHandler, FileMetadataRepository fileMetadataRepository) {
        this.webSocketHandler = webSocketHandler;
        this.fileMetadataRepository = fileMetadataRepository;
        File directory = new File(UPLOAD_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void saveFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot save empty file.");
        }
        
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "unknown";
        }
        
        String storedFilename = getUniqueFilename(originalFilename);
        
        byte[] bytes = file.getBytes();
        Path path = Paths.get(UPLOAD_DIR + storedFilename);
        Files.write(path, bytes);
        
        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalName(originalFilename);
        metadata.setStoredName(storedFilename);
        metadata.setSize(file.getSize());
        metadata.setContentType(file.getContentType());
        metadata.setUploadedAt(LocalDateTime.now());
        
        fileMetadataRepository.save(metadata);
        
        // Broadcast the update to all connected clients
        webSocketHandler.broadcastFileUpdate();
    }
    
    private String getUniqueFilename(String originalFilename) {
        String baseName = originalFilename;
        String extension = "";
        
        int dotIndex = originalFilename.lastIndexOf(".");
        if (dotIndex > 0) {
            baseName = originalFilename.substring(0, dotIndex);
            extension = originalFilename.substring(dotIndex);
        }
        
        String newFilename = originalFilename;
        int counter = 1;
        while (fileMetadataRepository.findByStoredName(newFilename).isPresent()) {
            newFilename = baseName + "[" + counter + "]" + extension;
            counter++;
        }
        return newFilename;
    }

    public List<FileMetadata> getFileList() {
        return fileMetadataRepository.findAll();
    }

    public Resource loadFileAsResource(String filename) throws MalformedURLException {
        Path file = Paths.get(UPLOAD_DIR).resolve(filename);
        Resource resource = new UrlResource(file.toUri());

        if (resource.exists() || resource.isReadable()) {
            return resource;
        } else {
            return null;
        }
    }

    public boolean deleteFile(String filename) throws IOException {
        Path file = Paths.get(UPLOAD_DIR).resolve(filename);
        boolean deleted = Files.deleteIfExists(file);
        if (deleted) {
            Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByStoredName(filename);
            metadataOpt.ifPresent(fileMetadataRepository::delete);
            webSocketHandler.broadcastFileUpdate();
        }
        return deleted;
    }
}
