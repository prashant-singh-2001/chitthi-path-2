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

        List<String> tags
) {}
