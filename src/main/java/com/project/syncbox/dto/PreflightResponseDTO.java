package com.project.syncbox.dto;

import lombok.Data;

@Data
public class PreflightResponseDTO {
    private String clientId;
    private String fileName;
    private String checksum;
    private String status; // "NEW", "SERVER_CONFLICT"
}
