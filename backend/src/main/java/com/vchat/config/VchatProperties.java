package com.vchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "vchat")
public record VchatProperties(
        String jwtSecret,
        Auth auth,
        List<String> corsOrigins,
        String frontendUrl,
        Storage storage,
        Ai ai,
        Tts tts,
        OAuth oauth,
        Events events,
        Monitoring monitoring,
        RateLimit rateLimit) {

    public enum StorageMode { LOCAL, S3 }

    /** Session settings: short-lived access JWTs + rotating refresh tokens in an httpOnly cookie. */
    public record Auth(int accessTtlMinutes, int refreshTtlDays, String cookieSecure, int reuseGraceSeconds,
                       List<String> adminUsernames) {
        public boolean isAdminUsername(String username) {
            return adminUsernames != null && username != null
                    && adminUsernames.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).anyMatch(username::equals);
        }
    }

    public record Storage(StorageMode mode, String bucket, String region, String publicBaseUrl) {}

    public record Ai(String model) {}

    public record Tts(String elevenlabsApiKey, String elevenlabsModel, String coeiroinkUrl, String googleApiKey) {}

    public record OAuth(String publicBaseUrl, String googleClientId, String googleClientSecret,
                        String facebookClientId, String facebookClientSecret) {
        public boolean google() { return notBlank(googleClientId) && notBlank(googleClientSecret); }
        public boolean facebook() { return notBlank(facebookClientId) && notBlank(facebookClientSecret); }

        public List<String> enabledProviders() {
            List<String> out = new ArrayList<>();
            if (google()) out.add("google");
            if (facebook()) out.add("facebook");
            return out;
        }
    }

    public record Events(String mode, int replicas) {}

    public record Monitoring(long slowCallMs) {}

    public record RateLimit(boolean enabled) {}

    static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
