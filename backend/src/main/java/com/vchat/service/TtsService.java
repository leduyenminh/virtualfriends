package com.vchat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vchat.aop.RateLimited;
import com.vchat.config.CacheConfig;
import com.vchat.config.VchatProperties;
import com.vchat.domain.BotCharacter.TtsProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.Serializable;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Server-side TTS proxies so API keys never reach the browser.
 *  - ELEVENLABS: eleven_multilingual_v2 covers vi/en/zh/ja/ko/es/ru (free tier: ~10k chars/month).
 *  - GOOGLE:     Cloud Text-to-Speech (free tier covers standard voices for all 7 languages).
 *  - COEIROINK:  free local engine (Japanese voice bank).
 *  - BROWSER:    handled entirely client-side with the Web Speech API (free, no key).
 * Synthesized audio is cached in Redis for 7 days: replaying a line (🔊) or a common greeting costs nothing.
 */
@Service
public class TtsService {
    public record Audio(byte[] bytes, String contentType) implements Serializable {}

    private static final Map<String, String> GOOGLE_LOCALES = Map.of(
            "vi", "vi-VN", "en", "en-US", "zh", "cmn-CN", "ja", "ja-JP", "ko", "ko-KR", "es", "es-ES", "ru", "ru-RU");

    private final VchatProperties.Tts cfg;
    private final RestClient http = client(Duration.ofSeconds(3), Duration.ofSeconds(60));
    private final RestClient probe = client(Duration.ofMillis(500), Duration.ofSeconds(1));

    public TtsService(VchatProperties props) { this.cfg = props.tts(); }

    private static RestClient client(Duration connect, Duration read) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Which engines are usable right now. COEIROINK is probed because it is a desktop app that may not be running. */
    @Cacheable(CacheConfig.TTS_PROVIDERS)
    public Map<TtsProvider, Boolean> availability() {
        Map<TtsProvider, Boolean> m = new EnumMap<>(TtsProvider.class);
        m.put(TtsProvider.BROWSER, true);
        m.put(TtsProvider.ELEVENLABS, notBlank(cfg.elevenlabsApiKey()));
        m.put(TtsProvider.GOOGLE, notBlank(cfg.googleApiKey()));
        m.put(TtsProvider.COEIROINK, coeiroinkUp());
        return m;
    }

    @RateLimited(name = "tts", limit = 30, windowSeconds = 60, message = "Voice limit reached, try again in a minute")
    @Cacheable(cacheNames = CacheConfig.TTS, key = "#root.target.cacheKey(#text, #lang, #provider, #voiceId)")
    public Audio synthesize(String text, String lang, TtsProvider provider, String voiceId) {
        return switch (provider == null ? TtsProvider.BROWSER : provider) {
            case ELEVENLABS -> elevenLabs(text, voiceId);
            case GOOGLE -> google(text, lang, voiceId);
            case COEIROINK -> coeiroink(text, voiceId);
            case BROWSER -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BROWSER voices are synthesized client-side");
        };
    }

    public String cacheKey(String text, String lang, TtsProvider provider, String voiceId) {
        String raw = provider + "|" + voiceId + "|" + lang + "|" + text;
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Lists voices from providers that expose a catalogue. */
    @Cacheable(CacheConfig.TTS_VOICES)
    public List<Map<String, String>> voices(TtsProvider provider) {
        List<Map<String, String>> out = new ArrayList<>();
        if (provider == TtsProvider.ELEVENLABS && notBlank(cfg.elevenlabsApiKey())) {
            JsonNode res = http.get().uri("https://api.elevenlabs.io/v1/voices")
                    .header("xi-api-key", cfg.elevenlabsApiKey()).retrieve().body(JsonNode.class);
            if (res != null) res.path("voices").forEach(v -> out.add(Map.of("id", v.path("voice_id").asText(), "name", v.path("name").asText())));
        } else if (provider == TtsProvider.COEIROINK && coeiroinkUp()) {
            JsonNode res = http.get().uri(cfg.coeiroinkUrl() + "/v1/speakers").retrieve().body(JsonNode.class);
            if (res != null) res.forEach(sp -> sp.path("styles").forEach(st -> out.add(Map.of(
                    "id", sp.path("speakerUuid").asText() + ":" + st.path("styleId").asText(),
                    "name", sp.path("speakerName").asText() + " / " + st.path("styleName").asText()))));
        }
        return out;
    }

    private boolean coeiroinkUp() {
        if (!notBlank(cfg.coeiroinkUrl())) return false;
        try {
            probe.get().uri(cfg.coeiroinkUrl() + "/v1/speakers").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Audio elevenLabs(String text, String voiceId) {
        require(cfg.elevenlabsApiKey(), "ELEVENLABS_API_KEY");
        String voice = notBlank(voiceId) ? voiceId : "21m00Tcm4TlvDq8ikWAM"; // "Rachel", a default premade voice
        byte[] audio = http.post().uri("https://api.elevenlabs.io/v1/text-to-speech/{v}", voice)
                .header("xi-api-key", cfg.elevenlabsApiKey())
                .accept(MediaType.valueOf("audio/mpeg"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text, "model_id", cfg.elevenlabsModel()))
                .retrieve().body(byte[].class);
        return new Audio(audio, "audio/mpeg");
    }

    private Audio google(String text, String lang, String voiceName) {
        require(cfg.googleApiKey(), "GOOGLE_TTS_API_KEY");
        String locale = GOOGLE_LOCALES.getOrDefault(lang == null ? "en" : lang, "en-US");
        Map<String, Object> voice = new HashMap<>(Map.of("languageCode", locale));
        if (notBlank(voiceName)) voice.put("name", voiceName);
        JsonNode res = http.post().uri("https://texttospeech.googleapis.com/v1/text:synthesize?key={k}", cfg.googleApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("input", Map.of("text", text), "voice", voice, "audioConfig", Map.of("audioEncoding", "MP3")))
                .retrieve().body(JsonNode.class);
        return new Audio(Base64.getDecoder().decode(res.path("audioContent").asText()), "audio/mpeg");
    }

    /** voiceId format: "speakerUuid:styleId" as returned by {@link #voices}. COEIROINK v2 engine API. */
    private Audio coeiroink(String text, String voiceId) {
        if (!notBlank(voiceId) || !voiceId.contains(":")) throw new IllegalArgumentException("COEIROINK voiceId must be speakerUuid:styleId");
        String[] parts = voiceId.split(":", 2);
        byte[] wav = http.post().uri(cfg.coeiroinkUrl() + "/v1/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("speakerUuid", parts[0], "styleId", Integer.parseInt(parts[1]), "text", text, "speedScale", 1.0))
                .retrieve().body(byte[].class);
        return new Audio(wav, "audio/wav");
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static void require(String v, String name) {
        if (!notBlank(v)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, name + " is not configured");
    }
}
