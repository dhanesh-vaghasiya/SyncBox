package com.project.syncbox.service.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Primary
public class B2StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(B2StorageService.class);

    private final LocalStorageService localStorageService;

    @Value("${storage.provider:b2}")
    private String storageProvider;

    @Value("${storage.b2.key-id:}")
    private String keyId;

    @Value("${storage.b2.application-key:}")
    private String applicationKey;

    @Value("${storage.b2.bucket-name:}")
    private String bucketName;

    @Value("${storage.b2.endpoint:}")
    private String endpoint;

    @Value("${storage.b2.region:}")
    private String region;

    private S3Client s3Client;
    private boolean useCloud = false;

    public B2StorageService(LocalStorageService localStorageService) {
        this.localStorageService = localStorageService;
    }

    @PostConstruct
    public void init() {
        if ("local".equalsIgnoreCase(storageProvider)) {
            log.info("Storage provider explicitly configured to LOCAL. Backblaze B2 disabled.");
            useCloud = false;
            return;
        }

        if (keyId == null || keyId.trim().isEmpty() ||
            applicationKey == null || applicationKey.trim().isEmpty() ||
            bucketName == null || bucketName.trim().isEmpty()) {
            log.warn("Backblaze B2 storage provider selected, but credentials are incomplete: " +
                    "[keyId present={}, applicationKey present={}, bucketName present={}]. " +
                    "SyncBox will fall back to local disk storage until B2 credentials are provided.",
                    (keyId != null && !keyId.trim().isEmpty()),
                    (applicationKey != null && !applicationKey.trim().isEmpty()),
                    (bucketName != null && !bucketName.trim().isEmpty()));
            useCloud = false;
            return;
        }

        try {
            String endpointUrl = endpoint != null ? endpoint.trim() : "";
            if (!endpointUrl.isEmpty() && !endpointUrl.startsWith("http://") && !endpointUrl.startsWith("https://")) {
                endpointUrl = "https://" + endpointUrl;
            }
            if (endpointUrl.isEmpty()) {
                endpointUrl = "https://s3.us-west-004.backblazeb2.com";
            }

            String resolvedRegion = resolveRegion(region, endpointUrl);

            AwsBasicCredentials credentials = AwsBasicCredentials.create(keyId.trim(), applicationKey.trim());
            S3Configuration serviceConfiguration = S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build();

            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpointUrl))
                    .region(Region.of(resolvedRegion))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .serviceConfiguration(serviceConfiguration)
                    .build();

            this.useCloud = true;
            log.info("Initialized Backblaze B2 Cloud Storage provider [bucket={}, endpoint={}, region={}]",
                    bucketName, endpointUrl, resolvedRegion);
        } catch (Exception e) {
            log.error("Failed to initialize Backblaze B2 S3 Client: {}. Falling back to local storage.", e.getMessage(), e);
            this.useCloud = false;
        }
    }

    private String resolveRegion(String explicitRegion, String endpointUrl) {
        if (explicitRegion != null && !explicitRegion.trim().isEmpty()) {
            return explicitRegion.trim();
        }
        // Extract region from endpoint e.g., https://s3.us-west-004.backblazeb2.com
        Pattern pattern = Pattern.compile("s3[.-]([a-zA-Z0-9-]+)\\.backblazeb2\\.com");
        Matcher matcher = pattern.matcher(endpointUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "us-west-004";
    }

    @Override
    public void uploadFile(String storedName, Path localFilePath, String contentType) throws IOException {
        if (!useCloud) {
            localStorageService.uploadFile(storedName, localFilePath, contentType);
            return;
        }

        try {
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(storedName)
                    .contentType(contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream")
                    .build();

            s3Client.putObject(putReq, localFilePath);
            log.info("Uploaded file to Backblaze B2: key={}", storedName);
        } catch (Exception e) {
            log.error("Failed to upload file to Backblaze B2 for key: {}", storedName, e);
            throw new IOException("Backblaze B2 upload failed: " + e.getMessage(), e);
        } finally {
            // Remove local temporary file once uploaded or on failure to prevent disk buildup
            Files.deleteIfExists(localFilePath);
        }
    }

    @Override
    public Resource loadAsResource(String storedName, String originalFilename, Long fileSize) {
        if (!useCloud) {
            return localStorageService.loadAsResource(storedName, originalFilename, fileSize);
        }

        S3Resource s3Resource = new S3Resource(s3Client, bucketName, storedName, originalFilename, fileSize);
        if (s3Resource.exists()) {
            return s3Resource;
        }

        // Fallback for legacy files uploaded before B2 configuration
        if (localStorageService.exists(storedName)) {
            log.info("File {} found in local storage fallback; loading from local disk", storedName);
            return localStorageService.loadAsResource(storedName, originalFilename, fileSize);
        }

        return null;
    }

    @Override
    public void deleteFile(String storedName) throws IOException {
        if (useCloud) {
            try {
                DeleteObjectRequest deleteReq = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(storedName)
                        .build();
                s3Client.deleteObject(deleteReq);
                log.info("Deleted object from Backblaze B2: key={}", storedName);
            } catch (Exception e) {
                log.warn("Failed to delete object from Backblaze B2: {}", storedName, e);
            }
        }
        // Also cleanup from local disk if present
        localStorageService.deleteFile(storedName);
    }

    @Override
    public void copyFile(String sourceStoredName, String destinationStoredName) throws IOException {
        if (!useCloud) {
            localStorageService.copyFile(sourceStoredName, destinationStoredName);
            return;
        }

        try {
            CopyObjectRequest copyReq = CopyObjectRequest.builder()
                    .sourceBucket(bucketName)
                    .sourceKey(sourceStoredName)
                    .destinationBucket(bucketName)
                    .destinationKey(destinationStoredName)
                    .build();

            s3Client.copyObject(copyReq);
            log.info("Copied object in Backblaze B2: {} -> {}", sourceStoredName, destinationStoredName);
        } catch (Exception e) {
            log.error("Failed to copy object in Backblaze B2 from {} to {}: {}",
                    sourceStoredName, destinationStoredName, e.getMessage(), e);
            throw new IOException("Backblaze B2 copy failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String storedName) {
        if (!useCloud) {
            return localStorageService.exists(storedName);
        }

        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(storedName).build());
            return true;
        } catch (NoSuchKeyException e) {
            return localStorageService.exists(storedName);
        } catch (Exception e) {
            return localStorageService.exists(storedName);
        }
    }

    @Override
    public String getStorageProviderName() {
        return useCloud ? "BACKBLAZE_B2" : "LOCAL";
    }

    public boolean isCloudActive() {
        return useCloud;
    }
}
