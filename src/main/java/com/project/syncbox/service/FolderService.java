package com.project.syncbox.service;

import com.project.syncbox.handler.FileWebSocketHandler;
import com.project.syncbox.model.FileMetadata;
import com.project.syncbox.model.Folder;
import com.project.syncbox.model.User;
import com.project.syncbox.repository.FileMetadataRepository;
import com.project.syncbox.repository.FolderRepository;
import com.project.syncbox.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FolderService {

    private static final Logger log = LoggerFactory.getLogger(FolderService.class);

    private final FolderRepository folderRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final UserRepository userRepository;
    private final FileWebSocketHandler webSocketHandler;
    private final com.project.syncbox.service.FileService fileService;

    public FolderService(FolderRepository folderRepository, 
                         FileMetadataRepository fileMetadataRepository, 
                         UserRepository userRepository, 
                         FileWebSocketHandler webSocketHandler,
                         @org.springframework.context.annotation.Lazy com.project.syncbox.service.FileService fileService) {
        this.folderRepository = folderRepository;
        this.fileMetadataRepository = fileMetadataRepository;
        this.userRepository = userRepository;
        this.webSocketHandler = webSocketHandler;
        this.fileService = fileService;
    }

    public Folder createFolder(String name, Long parentId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Folder name cannot be empty");
        }

        Folder parent = null;
        if (parentId != null) {
            parent = folderRepository.findByIdAndUserAndDeletedAtIsNull(parentId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Parent folder not found"));
            
            if (folderRepository.existsByUserAndParentIdAndNameAndDeletedAtIsNull(user, parentId, name)) {
                throw new IllegalArgumentException("A folder with this name already exists in the selected location");
            }
        } else {
            if (folderRepository.existsByUserAndParentIsNullAndNameAndDeletedAtIsNull(user, name)) {
                throw new IllegalArgumentException("A folder with this name already exists in the root");
            }
        }

        Folder folder = new Folder();
        folder.setName(name);
        folder.setParent(parent);
        folder.setUser(user);
        folder.setCreatedAt(LocalDateTime.now());
        
        folderRepository.save(folder);
        webSocketHandler.broadcastFileUpdate();
        log.info("Create folder userId={} folderId={} folderName=\"{}\"", user.getId(), folder.getId(), folder.getName());
        return folder;
    }

    public List<Folder> getSubfolders(Long parentId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (parentId == null) {
            return folderRepository.findAllByUserAndParentIsNullAndDeletedAtIsNull(user);
        } else {
            // Verify parent belongs to user
            folderRepository.findByIdAndUserAndDeletedAtIsNull(parentId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Folder not found"));
            return folderRepository.findAllByUserAndParentIdAndDeletedAtIsNull(user, parentId);
        }
    }

    public List<Folder> getTrashedFolders(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return folderRepository.findTopLevelTrashedFolders(user);
    }

    public List<Folder> getAllActiveFolders(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return folderRepository.findAllByUserAndDeletedAtIsNull(user);
    }

    public Folder renameFolder(Long id, String newName, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder folder = folderRepository.findByIdAndUserAndDeletedAtIsNull(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("Folder name cannot be empty");
        }

        Long parentId = folder.getParent() != null ? folder.getParent().getId() : null;
        
        if (parentId != null) {
            if (folderRepository.existsByUserAndParentIdAndNameAndDeletedAtIsNull(user, parentId, newName) && !folder.getName().equals(newName)) {
                throw new IllegalArgumentException("A folder with this name already exists in the selected location");
            }
        } else {
            if (folderRepository.existsByUserAndParentIsNullAndNameAndDeletedAtIsNull(user, newName) && !folder.getName().equals(newName)) {
                throw new IllegalArgumentException("A folder with this name already exists in the root");
            }
        }

        folder.setName(newName);
        folder.setUpdatedAt(LocalDateTime.now());
        folderRepository.save(folder);
        webSocketHandler.broadcastFileUpdate();
        log.info("Rename folder userId={} folderId={} newName=\"{}\"", user.getId(), folder.getId(), newName);
        return folder;
    }

    public Folder moveFolder(Long id, Long targetParentId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder folder = folderRepository.findByIdAndUserAndDeletedAtIsNull(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        Folder targetParent = null;
        if (targetParentId != null) {
            if (id.equals(targetParentId)) {
                throw new IllegalArgumentException("Cannot move a folder into itself");
            }
            targetParent = folderRepository.findByIdAndUserAndDeletedAtIsNull(targetParentId, user)
                    .orElseThrow(() -> new IllegalArgumentException("Target folder not found"));

            // Check for cyclical reference (can't move folder into one of its descendants)
            Folder current = targetParent;
            while (current != null) {
                if (current.getId().equals(id)) {
                    throw new IllegalArgumentException("Cannot move a folder into its descendant");
                }
                current = current.getParent();
            }

            if (folderRepository.existsByUserAndParentIdAndNameAndDeletedAtIsNull(user, targetParentId, folder.getName())) {
                throw new IllegalArgumentException("A folder with this name already exists in the destination");
            }
        } else {
            if (folderRepository.existsByUserAndParentIsNullAndNameAndDeletedAtIsNull(user, folder.getName())) {
                throw new IllegalArgumentException("A folder with this name already exists in the root");
            }
        }

        folder.setParent(targetParent);
        folder.setUpdatedAt(LocalDateTime.now());
        folderRepository.save(folder);
        webSocketHandler.broadcastFileUpdate();
        log.info("Move folder userId={} folderId={} toParentId={}", user.getId(), folder.getId(), targetParentId);
        return folder;
    }

    public Folder copyFolder(Long id, Long targetParentId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder folder = folderRepository.findByIdAndUserAndDeletedAtIsNull(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        // Use createFolder to handle unique naming and parent validation
        String newName = folder.getName();
        int counter = 1;
        while (true) {
            try {
                Folder newFolder = createFolder(newName, targetParentId, username);
                
                // Copy all files in the current folder
                List<FileMetadata> files = fileService.getFileList(username, folder.getId());
                for (FileMetadata f : files) {
                    try {
                        fileService.copyFile(f.getStoredName(), newFolder.getId(), username);
                    } catch (Exception e) {
                        System.err.println("Failed to copy file " + f.getOriginalName() + ": " + e.getMessage());
                    }
                }

                // Recursively copy subfolders
                List<Folder> subfolders = folderRepository.findAllByUserAndParentIdAndDeletedAtIsNull(user, folder.getId());
                for (Folder sub : subfolders) {
                    copyFolder(sub.getId(), newFolder.getId(), username);
                }
                
                return newFolder;
            } catch (IllegalArgumentException e) {
                if (e.getMessage().contains("already exists")) {
                    newName = folder.getName() + " - Copy (" + counter + ")";
                    counter++;
                } else {
                    throw e; // Bubble up other exceptions (like cyclic references)
                }
            }
        }
    }

    public void deleteFolder(Long id, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder folder = folderRepository.findByIdAndUserAndDeletedAtIsNull(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        softDeleteRecursive(folder);
        webSocketHandler.broadcastFileUpdate();
        log.info("Delete folder userId={} folderId={}", user.getId(), folder.getId());
    }

    private void softDeleteRecursive(Folder folder) {
        LocalDateTime now = LocalDateTime.now();
        folder.setDeletedAt(now);
        folderRepository.save(folder);

        // Soft delete subfolders
        List<Folder> subfolders = folderRepository.findAllByParentIdAndDeletedAtIsNull(folder.getId());
        for (Folder sub : subfolders) {
            softDeleteRecursive(sub);
        }

        // Soft delete files in this folder
        List<FileMetadata> files = fileMetadataRepository.findAllByFolderIdAndDeletedAtIsNull(folder.getId());
        for (FileMetadata fm : files) {
            fm.setDeletedAt(now);
            fileMetadataRepository.save(fm);
        }
    }

    public void restoreFolder(Long id, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Use findByIdAndUser to get it even if it's deleted
        Folder folder = folderRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        // If restoring a folder whose parent is still deleted, restore to root
        if (folder.getParent() != null && folder.getParent().getDeletedAt() != null) {
            folder.setParent(null);
        }
        
        restoreRecursive(folder);
        webSocketHandler.broadcastFileUpdate();
        log.info("Restore folder userId={} folderId={}", user.getId(), folder.getId());
    }

    private void restoreRecursive(Folder folder) {
        folder.setDeletedAt(null);
        folderRepository.save(folder);
        
        // Restore subfolders
        List<Folder> subfolders = folderRepository.findAllByParentIdAndDeletedAtIsNotNull(folder.getId());
        for (Folder sub : subfolders) {
            restoreRecursive(sub);
        }

        // Restore files in this folder
        List<FileMetadata> files = fileMetadataRepository.findAllByFolderIdAndDeletedAtIsNotNull(folder.getId());
        for (FileMetadata fm : files) {
            fm.setDeletedAt(null);
            fileMetadataRepository.save(fm);
        }
    }

    public void permanentlyDeleteFolder(Long id, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder folder = folderRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        permanentlyDeleteFolderRecursive(folder);
        webSocketHandler.broadcastFileUpdate();
        log.info("Permanent delete folder userId={} folderId={}", user.getId(), folder.getId());
    }
    
    private void permanentlyDeleteFolderRecursive(Folder folder) {
        // Delete all files in this folder
        List<FileMetadata> files = fileMetadataRepository.findAllByFolderId(folder.getId());
        for (FileMetadata fm : files) {
            try {
                fileService.permanentlyDeleteFile(fm);
            } catch (java.io.IOException e) {
                System.err.println("Failed to delete file from disk during folder deletion: " + fm.getStoredName());
                e.printStackTrace();
            }
        }

        // Recursively delete subfolders
        List<Folder> subfolders = folderRepository.findAllByParentId(folder.getId());
        for (Folder sub : subfolders) {
            permanentlyDeleteFolderRecursive(sub);
        }

        // Finally delete the folder itself
        folderRepository.delete(folder);
    }

    public String buildFolderPath(Long folderId, String username) {
        if (folderId == null) return "Home";
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder current = folderRepository.findByIdAndUser(folderId, user).orElse(null);
        if (current == null) return "Unknown";

        List<String> path = new ArrayList<>();
        while (current != null) {
            path.add(current.getName());
            current = current.getParent();
        }
        path.add("Home");
        Collections.reverse(path);
        return String.join(" / ", path);
    }

    public List<java.util.Map<String, Object>> getFolderBreadcrumbs(Long folderId, String username) {
        List<java.util.Map<String, Object>> breadcrumbs = new ArrayList<>();
        java.util.Map<String, Object> homeNode = new java.util.HashMap<>();
        homeNode.put("id", null);
        homeNode.put("name", "Home");
        
        if (folderId == null) {
            breadcrumbs.add(homeNode);
            return breadcrumbs;
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Folder current = folderRepository.findByIdAndUser(folderId, user).orElse(null);
        if (current == null) {
            breadcrumbs.add(homeNode);
            return breadcrumbs;
        }

        while (current != null) {
            java.util.Map<String, Object> node = new java.util.HashMap<>();
            node.put("id", current.getId());
            node.put("name", current.getName());
            breadcrumbs.add(node);
            current = current.getParent();
        }
        breadcrumbs.add(homeNode);
        Collections.reverse(breadcrumbs);
        return breadcrumbs;
    }
}
