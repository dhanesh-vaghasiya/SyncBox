package com.project.syncbox.exception;

public class DuplicateFileException extends RuntimeException {
    public DuplicateFileException(String message) {
        super(message);
    }
}