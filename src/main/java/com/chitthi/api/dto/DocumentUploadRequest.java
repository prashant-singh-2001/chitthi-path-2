package com.chitthi.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record DocumentUploadRequest(
        @NotBlank(message = "Title must not be blank")
        String title,

        @NotBlank(message = "Language must not be blank")
        String language,

        String ownerId,

        Integer year,

        List<String> tags,

        Boolean preprocess
) {
    public DocumentUploadRequest(String title, String language, String ownerId, Integer year, List<String> tags) {
        this(title, language, ownerId, year, tags, true);
    }
}
