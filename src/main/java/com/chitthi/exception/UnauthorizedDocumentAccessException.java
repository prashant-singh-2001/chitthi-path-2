package com.chitthi.exception;

import java.util.UUID;

public class UnauthorizedDocumentAccessException extends RuntimeException {

    private final UUID documentId;
    private final String requestingOwnerId;

    public UnauthorizedDocumentAccessException(UUID documentId, String requestingOwnerId) {
        super("User '" + requestingOwnerId + "' is not authorized to access or modify document '" + documentId + "'.");
        this.documentId = documentId;
        this.requestingOwnerId = requestingOwnerId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getRequestingOwnerId() {
        return requestingOwnerId;
    }
}
