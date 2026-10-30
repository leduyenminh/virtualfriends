package com.vchat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import java.net.URI;

@Configuration
@EnableConfigurationProperties(VchatProperties.class)
public class AppConfig implements WebMvcConfigurer {
    private final VchatProperties props;

    public AppConfig(VchatProperties props) { this.props = props; }

    /** Reads ANTHROPIC_API_KEY from the environment (a .env file, a Kubernetes Secret or the host's secret store). */
    @Bean
    AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }

    /** S3-protocol client for whichever object store STORAGE_ENDPOINT names; only created when STORAGE_MODE=S3. */
    @Bean
    @ConditionalOnProperty(prefix = "vchat.storage", name = "mode", havingValue = "S3")
    S3Client s3Client() {
        VchatProperties.Storage cfg = props.storage();
        if (!VchatProperties.notBlank(cfg.accessKey()) || !VchatProperties.notBlank(cfg.secretKey())) {
            throw new IllegalStateException("STORAGE_MODE=S3 needs STORAGE_ACCESS_KEY and STORAGE_SECRET_KEY");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(cfg.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())))
                .forcePathStyle(cfg.pathStyle());
        if (VchatProperties.notBlank(cfg.endpoint())) builder.endpointOverride(URI.create(cfg.endpoint()));
        return builder.build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (props.storage().mode() == VchatProperties.StorageMode.LOCAL) {
            registry.addResourceHandler("/files/**").addResourceLocations("file:uploads/");
        }
    }
}
