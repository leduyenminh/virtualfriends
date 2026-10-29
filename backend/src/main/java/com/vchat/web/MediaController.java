package com.vchat.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.vchat.domain.BotCharacter.TtsProvider;
import com.vchat.dto.Dtos.*;
import com.vchat.service.Current;
import com.vchat.service.StorageService;
import com.vchat.service.TtsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Tag(name = "Media")
@RestController
@RequestMapping("/api")
public class MediaController {
    private final StorageService storage;
    private final TtsService tts;

    public MediaController(StorageService storage, TtsService tts) { this.storage = storage; this.tts = tts; }

    /** Upload from local disk, or a blob the browser fetched from Google Drive / OneDrive pickers. */
    @Operation(summary = "Upload one file (sticker, attachment, avatar image or model)")
    @PostMapping(value = "/uploads", consumes = "multipart/form-data")
    public UploadResponse upload(@RequestParam StorageService.Kind kind, @RequestParam MultipartFile file) throws IOException {
        return storage.store(Current.userId(), kind, file);
    }

    @Operation(summary = "Upload a folder (Live2D model, glTF with textures, Unity WebGL build)")
    @PostMapping(value = "/uploads/bundle", consumes = "multipart/form-data")
    public UploadResponse uploadBundle(@RequestParam List<MultipartFile> files, @RequestParam List<String> paths) throws IOException {
        return storage.storeBundle(Current.userId(), files, paths);
    }

    @Operation(summary = "Which voice engines are usable right now")
    @GetMapping("/tts/providers")
    public Map<TtsProvider, Boolean> providers() { return tts.availability(); }

    @Operation(summary = "Voices of a server-side TTS provider")
    @GetMapping("/tts/voices")
    public List<Map<String, String>> voices(@RequestParam TtsProvider provider) { return tts.voices(provider); }

    @Operation(summary = "Synthesize speech (audio bytes, cached 7 days)")
    @PostMapping("/tts")
    public ResponseEntity<byte[]> speak(@Valid @RequestBody TtsRequest r) {
        TtsService.Audio a = tts.synthesize(r.text(), r.language(), r.provider(), r.voiceId());
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(a.contentType()))
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                .body(a.bytes());
    }
}
