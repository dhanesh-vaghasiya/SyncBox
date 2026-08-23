package com.project.syncbox.service;

import com.project.syncbox.handler.FileWebSocketHandler;
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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FileService {

    private final String UPLOAD_DIR = "src/main/resources/static/uploads/";
    private final FileWebSocketHandler webSocketHandler;

    public FileService(FileWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
        File directory = new File(UPLOAD_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    public void saveFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot save empty file.");
        }
        byte[] bytes = file.getBytes();
        Path path = Paths.get(UPLOAD_DIR + file.getOriginalFilename());
        Files.write(path, bytes);
        
        // Broadcast the update to all connected clients
        webSocketHandler.broadcastFileUpdate();
    }

    public List<String> getFileList() throws IOException {
        try (Stream<Path> paths = Files.walk(Paths.get(UPLOAD_DIR))) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        }
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
}
