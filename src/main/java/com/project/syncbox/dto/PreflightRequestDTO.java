package com.project.syncbox.dto;

import lombok.Data;

@Data
public class PreflightRequestDTO {
    private String clientId;
    private String fileName;
    private Long size;
    private String contentType;
    private String checksum;
    private Long folderId;
}
