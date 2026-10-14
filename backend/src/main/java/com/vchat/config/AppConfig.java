package com.vchat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableConfigurationProperties(VchatProperties.class)
public class AppConfig implements WebMvcConfigurer {
    private final VchatProperties props;

    public AppConfig(VchatProperties props) { this.props = props; }

    /** Reads ANTHROPIC_API_KEY from the environment (Secrets Manager -> ECS env in AWS). */
    @Bean
    AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }

    @Bean
    S3Client s3Client() {
        return S3Client.builder().region(Region.of(props.storage().region())).build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (props.storage().mode() == VchatProperties.StorageMode.LOCAL) {
            registry.addResourceHandler("/files/**").addResourceLocations("file:uploads/");
        }
    }
}
