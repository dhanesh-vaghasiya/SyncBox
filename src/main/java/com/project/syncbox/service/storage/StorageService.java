package com.project.syncbox.service.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Path;

public interface StorageService {

    /**
     * Uploads a local file to storage under the given storedName.
     *
     * @param storedName    the unique identifier/key for the stored file
     * @param localFilePath path to the local file to upload
     * @param contentType   MIME type of the file
     * @throws IOException if the upload fails
     */
    void uploadFile(String storedName, Path localFilePath, String contentType) throws IOException;

    /**
     * Loads the stored file as a Spring Resource for downloading/streaming.
     *
     * @param storedName       unique key/filename of the stored file
     * @param originalFilename original user-friendly filename
     * @param fileSize         size of the file in bytes (if known)
     * @return Resource representing the file, or null if not found
     */
    Resource loadAsResource(String storedName, String originalFilename, Long fileSize);

    /**
     * Permanently deletes a file from storage.
     *
     * @param storedName unique key/filename of the stored file
     * @throws IOException if deletion fails
     */
    void deleteFile(String storedName) throws IOException;

    /**
     * Copies a file within storage from source key to destination key.
     *
     * @param sourceStoredName      source stored file key
     * @param destinationStoredName destination stored file key
     * @throws IOException if copy fails
     */
    void copyFile(String sourceStoredName, String destinationStoredName) throws IOException;

    /**
     * Checks if a file exists in storage.
     *
     * @param storedName unique key/filename
     * @return true if the file exists in storage, false otherwise
     */
    boolean exists(String storedName);

    /**
     * Returns the name of the active storage provider (e.g. "BACKBLAZE_B2" or "LOCAL").
     */
    String getStorageProviderName();
}
