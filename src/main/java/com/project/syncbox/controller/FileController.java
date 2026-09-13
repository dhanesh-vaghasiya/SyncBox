package com.project.syncbox.controller;

import com.project.syncbox.exception.DuplicateFileException;
import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(
            @RequestParam("file") MultipartFile file, 
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            Principal principal) {
        try {
            fileService.saveFile(file, principal.getName(), force);
            return ResponseEntity.ok("File uploaded successfully: " + file.getOriginalFilename());
        } catch (DuplicateFileException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to upload file.");
        }
    }

    @PostMapping("/upload/session")
    public ResponseEntity<com.project.syncbox.model.UploadSession> createUploadSession(
            @RequestParam("originalName") String originalName,
            @RequestParam("contentType") String contentType,
            @RequestParam("totalSize") long totalSize,
            @RequestParam("totalChunks") int totalChunks,
            Principal principal) {
        try {
            com.project.syncbox.model.UploadSession session = fileService.createUploadSession(
                    originalName, contentType, totalSize, totalChunks, principal.getName());
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/upload/session/{sessionId}")
    public ResponseEntity<com.project.syncbox.dto.SessionStatusDTO> getSessionStatus(
            @PathVariable String sessionId,
            Principal principal) {
        try {
            com.project.syncbox.dto.SessionStatusDTO status = fileService.getSessionStatus(sessionId, principal.getName());
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/upload/session/{sessionId}/part/{chunkIndex}")
    public ResponseEntity<String> uploadChunk(
            @PathVariable String sessionId,
            @PathVariable int chunkIndex,
            @RequestParam("file") MultipartFile file,
            Principal principal) {
        try {
            fileService.uploadChunk(sessionId, chunkIndex, file, principal.getName());
            return ResponseEntity.ok("Chunk uploaded");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to upload chunk.");
        }
    }

    @PostMapping("/upload/session/{sessionId}/complete")
    public ResponseEntity<String> completeUpload(
            @PathVariable String sessionId,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            Principal principal) {
        try {
            fileService.completeUpload(sessionId, force, principal.getName());
            return ResponseEntity.ok("File uploaded successfully");
        } catch (DuplicateFileException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to complete upload.");
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<FileMetadata>> listFiles(Principal principal) {
        return ResponseEntity.ok(fileService.getFileList(principal.getName()));
    }

    @GetMapping("/trash")
    public ResponseEntity<List<FileMetadata>> listTrashedFiles(Principal principal) {
        return ResponseEntity.ok(fileService.getTrashedFileList(principal.getName()));
    }

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename, Principal principal) {
        try {
            Resource resource = fileService.loadFileAsResource(filename, principal.getName());
            if (resource != null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{filename:.+}")
    public ResponseEntity<String> deleteFile(@PathVariable String filename, Principal principal) {
        System.out.println("Attempting to delete file: " + filename);
        boolean deleted = fileService.deleteFile(filename, principal.getName());
        if (deleted) {
            return ResponseEntity.ok("File moved to trash successfully");
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{filename:.+}/restore")
    public ResponseEntity<String> restoreFile(@PathVariable String filename, Principal principal) {
        boolean restored = fileService.restoreFile(filename, principal.getName());
        if (restored) {
            return ResponseEntity.ok("File restored successfully");
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{filename:.+}/permanent")
    public ResponseEntity<String> permanentlyDeleteFile(@PathVariable String filename, Principal principal) {
        try {
            boolean deleted = fileService.permanentlyDeleteFile(filename, principal.getName());
            if (deleted) {
                return ResponseEntity.ok("File permanently deleted");
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Failed to permanently delete file.");
        }
    }
}
