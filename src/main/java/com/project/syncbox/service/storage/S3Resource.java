package com.project.syncbox.service.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Spring Resource implementation backed by an S3-compatible cloud object (e.g. Backblaze B2).
 * Streams content directly from S3 without loading entire file into memory.
 */
public class S3Resource extends AbstractResource {

    private static final Logger log = LoggerFactory.getLogger(S3Resource.class);

    private final S3Client s3Client;
    private final String bucket;
    private final String key;
    private final String filename;
    private final Long contentLength;

    public S3Resource(S3Client s3Client, String bucket, String key, String filename, Long contentLength) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.key = key;
        this.filename = (filename != null && !filename.isBlank()) ? filename : key;
        this.contentLength = contentLength;
    }

    @Override
    public String getDescription() {
        return "Backblaze B2 Object [" + bucket + "/" + key + "]";
    }

    @Override
    public InputStream getInputStream() throws IOException {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3Client.getObject(getObjectRequest);
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found in Backblaze B2: " + key, e);
        } catch (Exception e) {
            log.error("Failed to fetch object stream from Backblaze B2 for key: {}", key, e);
            throw new IOException("Failed to obtain InputStream from Backblaze B2: " + key, e);
        }
    }

    @Override
    public boolean exists() {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.debug("HeadObject check failed for key: {}", key, e);
            return false;
        }
    }

    @Override
    public long contentLength() throws IOException {
        if (contentLength != null && contentLength >= 0) {
            return contentLength;
        }
        try {
            return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
        } catch (Exception e) {
            return super.contentLength();
        }
    }

    @Override
    public String getFilename() {
        return filename;
    }
}
