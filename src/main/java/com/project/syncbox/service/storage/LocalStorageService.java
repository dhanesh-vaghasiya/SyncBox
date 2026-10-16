package com.project.syncbox.service.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path uploadDirectory;

    public LocalStorageService(@Value("${storage.local.upload-dir:./uploads/}") String uploadDir) {
        this.uploadDirectory = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            if (!Files.exists(this.uploadDirectory)) {
                Files.createDirectories(this.uploadDirectory);
            }
            log.info("Initialized LocalStorageService at path: {}", this.uploadDirectory);
        } catch (IOException e) {
            log.error("Failed to initialize upload directory: {}", this.uploadDirectory, e);
        }
    }

    public Path getUploadDirectory() {
        return uploadDirectory;
    }

    @Override
    public void uploadFile(String storedName, Path localFilePath, String contentType) throws IOException {
        Path targetPath = uploadDirectory.resolve(storedName);
        if (!localFilePath.equals(targetPath)) {
            Files.move(localFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public Resource loadAsResource(String storedName, String originalFilename, Long fileSize) {
        try {
            Path file = uploadDirectory.resolve(storedName);
            if (!Files.exists(file)) {
                return null;
            }
            Resource resource = new UrlResource(file.toUri()) {
                @Override
                public String getFilename() {
                    return originalFilename != null && !originalFilename.isBlank() ? originalFilename : super.getFilename();
                }

                @Override
                public long contentLength() throws IOException {
                    if (fileSize != null && fileSize >= 0) {
                        return fileSize;
                    }
                    return super.contentLength();
                }
            };

            if (resource.exists() || resource.isReadable()) {
                return resource;
            }
        } catch (MalformedURLException e) {
            log.warn("Malformed URL for local file: {}", storedName, e);
        }
        return null;
    }

    @Override
    public void deleteFile(String storedName) throws IOException {
        Path file = uploadDirectory.resolve(storedName);
        Files.deleteIfExists(file);
    }

    @Override
    public void copyFile(String sourceStoredName, String destinationStoredName) throws IOException {
        Path sourcePath = uploadDirectory.resolve(sourceStoredName);
        Path targetPath = uploadDirectory.resolve(destinationStoredName);
        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean exists(String storedName) {
        Path file = uploadDirectory.resolve(storedName);
        return Files.exists(file);
    }

    @Override
    public String getStorageProviderName() {
        return "LOCAL";
    }
}
