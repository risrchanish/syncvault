package com.syncvault.fileservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private String  provider    = "openai";
    private String  apiKey      = "not-configured";
    private String  model       = "gpt-4o-mini";
    private int     tokenLimit  = 100_000;
    private boolean resilient   = true;
}
