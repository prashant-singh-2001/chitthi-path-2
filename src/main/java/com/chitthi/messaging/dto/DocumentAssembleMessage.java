package com.chitthi.messaging.dto;

import java.util.UUID;

public record DocumentAssembleMessage(
        UUID documentId
) {}
