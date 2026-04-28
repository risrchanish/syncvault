package com.syncvault.fileservice.config;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.config.LLMClientBuilder;
import com.syncvault.aisdk.config.Provider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public LLMClient llmClient(AiProperties props) {
        return LLMClientBuilder.create()
                .provider(Provider.valueOf(props.getProvider().toUpperCase()))
                .apiKey(props.getApiKey())
                .model(props.getModel())
                .tokenLimit(props.getTokenLimit())
                .resilient(props.isResilient())
                .build();
    }
}
