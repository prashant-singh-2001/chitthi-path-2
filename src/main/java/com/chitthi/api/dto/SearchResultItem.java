package com.chitthi.api.dto;

import java.util.UUID;

public record SearchResultItem(
        UUID documentId,
        String documentTitle,
        int pageNo,
        String snippet,
        String matchType
) {}
