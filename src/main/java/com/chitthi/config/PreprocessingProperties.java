package com.chitthi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "chitthi.preprocessing")
public record PreprocessingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean autoContrast,
        @DefaultValue("true") boolean deskew,
        @DefaultValue("true") boolean sharpen,
        @DefaultValue("15.0") double maxSkewAngle
) {
    public PreprocessingProperties() {
        this(true, true, true, true, 15.0);
    }
}
