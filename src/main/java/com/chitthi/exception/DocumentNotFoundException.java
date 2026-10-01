package com.chitthi.exception;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    private final UUID documentId;

    public DocumentNotFoundException(UUID documentId) {
        super("Document with id '" + documentId + "' was not found.");
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
