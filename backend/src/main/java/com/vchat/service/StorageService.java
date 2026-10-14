package com.vchat.service;

import com.vchat.config.VchatProperties;
import com.vchat.dto.Dtos.UploadResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static java.util.Map.entry;

/**
 * Stores stickers, attachments and avatar models. Locally files land in ./uploads; on AWS they go to S3 and are
 * served from a separate CloudFront domain, which keeps user-supplied SVG/HTML off the app origin.
 */
@Service
public class StorageService {
    public enum Kind { STICKER, ATTACHMENT, AVATAR, MODEL }

    private static final Set<String> STICKER_TYPES = Set.of("image/png", "image/svg+xml", "image/gif", "image/webp");

    /** Files allowed in 3D/Live2D/Unity model uploads, with the content type they are served as. */
    private static final Map<String, String> MODEL_EXT = Map.ofEntries(
            entry("glb", "model/gltf-binary"), entry("gltf", "model/gltf+json"), entry("vrm", "model/gltf-binary"),
            entry("bin", "application/octet-stream"), entry("json", "application/json"),
            entry("moc3", "application/octet-stream"), entry("png", "image/png"), entry("jpg", "image/jpeg"),
            entry("jpeg", "image/jpeg"), entry("webp", "image/webp"),
            // Unity WebGL builds (build with compression "Disabled"); rendered in a sandboxed iframe
            entry("html", "text/html"), entry("js", "text/javascript"), entry("wasm", "application/wasm"),
            entry("data", "application/octet-stream"));

    private final VchatProperties.Storage cfg;
    private final S3Client s3;

    public StorageService(VchatProperties props, S3Client s3) { this.cfg = props.storage(); this.s3 = s3; }

    public UploadResponse store(UUID owner, Kind kind, MultipartFile file) throws IOException {
        String ext = extension(file.getOriginalFilename());
        String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();

        if (kind == Kind.STICKER && !STICKER_TYPES.contains(type)) {
            throw new IllegalArgumentException("Stickers must be PNG, SVG, GIF or WEBP");
        }
        if (kind == Kind.MODEL) type = modelType(ext);
        String key = "%s/%s/%s%s".formatted(kind.name().toLowerCase(), owner, UUID.randomUUID(), ext.isEmpty() ? "" : "." + ext);
        put(key, type, file);
        return new UploadResponse(cfg.publicBaseUrl() + "/" + key, type, file.getSize());
    }

    /**
     * Multi-file model upload (Live2D model3.json + moc3 + textures, glTF + .bin, Unity WebGL build folder).
     * Relative paths are kept so the files can reference each other. Returns the folder's base URL.
     */
    public UploadResponse storeBundle(UUID owner, List<MultipartFile> files, List<String> paths) throws IOException {
        if (files.size() != paths.size() || files.isEmpty()) throw new IllegalArgumentException("files and paths must match");
        String base = "model/%s/%s".formatted(owner, UUID.randomUUID());
        long total = 0;
        for (int i = 0; i < files.size(); i++) {
            String rel = paths.get(i).replace('\\', '/');
            if (rel.startsWith("/") || rel.contains("..") || rel.isBlank()) throw new IllegalArgumentException("Bad path: " + rel);
            put(base + "/" + rel, modelType(extension(rel)), files.get(i));
            total += files.get(i).getSize();
        }
        return new UploadResponse(cfg.publicBaseUrl() + "/" + base, "inode/directory", total);
    }

    private void put(String key, String type, MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            if (cfg.mode() == VchatProperties.StorageMode.S3) {
                s3.putObject(PutObjectRequest.builder().bucket(cfg.bucket()).key(key).contentType(type)
                                .cacheControl("public, max-age=31536000, immutable").build(),
                        RequestBody.fromInputStream(in, file.getSize()));
            } else {
                Path root = Path.of("uploads").toAbsolutePath().normalize();
                Path target = root.resolve(key).normalize();
                if (!target.startsWith(root)) throw new IllegalArgumentException("Bad path");
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String modelType(String ext) {
        String t = MODEL_EXT.get(ext);
        if (t == null) throw new IllegalArgumentException("Unsupported model file: ." + ext);
        return t;
    }

    private static String extension(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }
}
