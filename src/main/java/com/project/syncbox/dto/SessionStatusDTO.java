package com.project.syncbox.dto;

import com.project.syncbox.model.UploadSession;
import lombok.Data;

import java.util.List;

@Data
public class SessionStatusDTO {
    private UploadSession session;
    private List<Integer> uploadedChunks;

    public SessionStatusDTO(UploadSession session, List<Integer> uploadedChunks) {
        this.session = session;
        this.uploadedChunks = uploadedChunks;
    }
}
