package com.vchat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import java.time.Instant;

@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "bot_character")
public class BotCharacter extends BaseEntity {
    public enum TtsProvider { BROWSER, ELEVENLABS, COEIROINK, GOOGLE }

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_id") private AppUser owner;
    @Column(nullable = false) private String name;
    @Column(nullable = false, columnDefinition = "text") private String persona;
    @Column(nullable = false) private String language = "en";
    @Enumerated(EnumType.STRING) @Column(name = "tts_provider", nullable = false) private TtsProvider ttsProvider = TtsProvider.BROWSER;
    @Column(name = "voice_id") private String voiceId;
    /** Claude model id, or null for the server default (see VchatProperties.Ai). */
    @Column(name = "model") private String model;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "avatar_model_id") private AvatarModel avatarModel;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected BotCharacter() {}
    public BotCharacter(AppUser owner, String name, String persona) {
        this.owner = owner; this.name = name; this.persona = persona;
    }

    public AppUser getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getPersona() { return persona; }
    public void setPersona(String v) { persona = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v; }
    public TtsProvider getTtsProvider() { return ttsProvider; }
    public void setTtsProvider(TtsProvider v) { ttsProvider = v; }
    public String getVoiceId() { return voiceId; }
    public void setVoiceId(String v) { voiceId = v; }
    public String getModel() { return model; }
    public void setModel(String v) { model = v; }
    public AvatarModel getAvatarModel() { return avatarModel; }
    public void setAvatarModel(AvatarModel v) { avatarModel = v; }
}
