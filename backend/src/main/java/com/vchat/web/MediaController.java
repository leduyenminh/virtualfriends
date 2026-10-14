package com.vchat.web;

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

@RestController
@RequestMapping("/api")
public class MediaController {
    private final StorageService storage;
    private final TtsService tts;

    public MediaController(StorageService storage, TtsService tts) { this.storage = storage; this.tts = tts; }

    /** Upload from local disk, or a blob the browser fetched from Google Drive / OneDrive pickers. */
    @PostMapping(value = "/uploads", consumes = "multipart/form-data")
    public UploadResponse upload(@RequestParam StorageService.Kind kind, @RequestParam MultipartFile file) throws IOException {
        return storage.store(Current.userId(), kind, file);
    }

    @PostMapping(value = "/uploads/bundle", consumes = "multipart/form-data")
    public UploadResponse uploadBundle(@RequestParam List<MultipartFile> files, @RequestParam List<String> paths) throws IOException {
        return storage.storeBundle(Current.userId(), files, paths);
    }

    @GetMapping("/tts/providers")
    public Map<TtsProvider, Boolean> providers() { return tts.availability(); }

    @GetMapping("/tts/voices")
    public List<Map<String, String>> voices(@RequestParam TtsProvider provider) { return tts.voices(provider); }

    @PostMapping("/tts")
    public ResponseEntity<byte[]> speak(@Valid @RequestBody TtsRequest r) {
        TtsService.Audio a = tts.synthesize(r.text(), r.language(), r.provider(), r.voiceId());
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(a.contentType()))
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                .body(a.bytes());
    }
}
