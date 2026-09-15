package com.project.syncbox.controller;

import com.project.syncbox.model.Folder;
import com.project.syncbox.service.FolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    public ResponseEntity<Folder> createFolder(
            @RequestParam("name") String name,
            @RequestParam(value = "parentId", required = false) Long parentId,
            Principal principal) {
        try {
            Folder folder = folderService.createFolder(name, parentId, principal.getName());
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<Folder>> listFolders(
            @RequestParam(value = "parentId", required = false) Long parentId,
            Principal principal) {
        try {
            return ResponseEntity.ok(folderService.getSubfolders(parentId, principal.getName()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/all")
    public ResponseEntity<List<Folder>> listAllFolders(Principal principal) {
        return ResponseEntity.ok(folderService.getAllActiveFolders(principal.getName()));
    }

    @GetMapping("/trash")
    public ResponseEntity<List<Folder>> listTrashedFolders(Principal principal) {
        return ResponseEntity.ok(folderService.getTrashedFolders(principal.getName()));
    }

    @PutMapping("/{id}/rename")
    public ResponseEntity<Folder> renameFolder(
            @PathVariable Long id,
            @RequestParam("name") String name,
            Principal principal) {
        try {
            Folder folder = folderService.renameFolder(id, name, principal.getName());
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}/move")
    public ResponseEntity<Folder> moveFolder(
            @PathVariable Long id,
            @RequestParam(value = "parentId", required = false) Long parentId,
            Principal principal) {
        try {
            Folder folder = folderService.moveFolder(id, parentId, principal.getName());
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<Folder> copyFolder(
            @PathVariable Long id,
            @RequestParam(value = "parentId", required = false) Long parentId,
            Principal principal) {
        try {
            Folder folder = folderService.copyFolder(id, parentId, principal.getName());
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long id, Principal principal) {
        try {
            folderService.deleteFolder(id, principal.getName());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restoreFolder(@PathVariable Long id, Principal principal) {
        try {
            folderService.restoreFolder(id, principal.getName());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<Void> permanentlyDeleteFolder(@PathVariable Long id, Principal principal) {
        try {
            folderService.permanentlyDeleteFolder(id, principal.getName());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    @GetMapping("/path")
    public ResponseEntity<Map<String, String>> getFolderPath(@RequestParam(value = "folderId", required = false) Long folderId, Principal principal) {
        try {
            String path = folderService.buildFolderPath(folderId, principal.getName());
            return ResponseEntity.ok(Map.of("path", path));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/breadcrumbs")
    public ResponseEntity<List<Map<String, Object>>> getFolderBreadcrumbs(@RequestParam(value = "folderId", required = false) Long folderId, Principal principal) {
        try {
            List<Map<String, Object>> breadcrumbs = folderService.getFolderBreadcrumbs(folderId, principal.getName());
            return ResponseEntity.ok(breadcrumbs);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
