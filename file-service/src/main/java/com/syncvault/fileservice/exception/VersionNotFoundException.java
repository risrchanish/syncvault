package com.syncvault.fileservice.exception;

public class VersionNotFoundException extends RuntimeException {
    public VersionNotFoundException(String message) {
        super(message);
    }
}
