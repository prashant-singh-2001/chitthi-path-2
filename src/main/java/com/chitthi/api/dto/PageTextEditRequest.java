package com.chitthi.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PageTextEditRequest(
        @NotBlank(message = "Text cannot be blank")
        String text
) {}
