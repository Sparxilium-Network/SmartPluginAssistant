package com.sparxilium.smartpluginassistant.service;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ImageCacheService {
    private static final Logger logger = LoggerFactory.getLogger(ImageCacheService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 SmartPluginAssistant/1.0 (contact@sparxilium.com)";
    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".smartpluginassistant", "cache", "images");
    private static final Map<String, Image> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static {
        try {
            if (!Files.exists(CACHE_DIR)) {
                Files.createDirectories(CACHE_DIR);
            }
        } catch (Exception e) {
            logger.error("Failed to create image cache directory", e);
        }
    }

    public static void loadImageAsync(String url, double width, double height, Consumer<Image> callback) {
        if (url == null || url.isBlank()) {
            return;
        }

        String memKey = url + "@" + width + "x" + height;
        Image memCached = MEMORY_CACHE.get(memKey);
        if (memCached != null) {
            Platform.runLater(() -> callback.accept(memCached));
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                byte[] imageBytes = null;
                boolean isRemoteHttp = url.startsWith("http://") || url.startsWith("https://");
                if (!isRemoteHttp) {
                    // Local file handling
                    try {
                        Path localPath;
                        if (url.startsWith("file:")) {
                            localPath = Paths.get(URI.create(url));
                        } else {
                            localPath = Paths.get(url);
                        }
                        if (Files.exists(localPath)) {
                            imageBytes = Files.readAllBytes(localPath);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to read local image: {}", url, e);
                    }
                } else {
                    String hash = hashUrl(url);
                    String ext = getExtension(url);
                    Path cachedFile = CACHE_DIR.resolve(hash + ext);

                    if (Files.exists(cachedFile) && Files.size(cachedFile) > 0) {
                        try {
                            imageBytes = Files.readAllBytes(cachedFile);
                        } catch (Exception e) {
                            logger.warn("Failed to read cached image file: {}", cachedFile, e);
                        }
                    }

                    if (imageBytes == null || imageBytes.length == 0) {
                        logger.debug("Downloading image from URL: {}", url);
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                                .GET()
                                .build();

                        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                            imageBytes = response.body();
                            try {
                                Files.write(cachedFile, imageBytes);
                            } catch (Exception e) {
                                logger.warn("Failed to write image to disk cache: {}", cachedFile, e);
                            }
                        } else {
                            logger.warn("Failed to download image from {}. HTTP Status: {}", url, response.statusCode());
                        }
                    }
                }

                if (imageBytes != null && imageBytes.length > 0) {
                    Image loadedImg = null;
                    // First try standard JavaFX Image decoder
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                        Image img = new Image(bais, width, height, true, true);
                        if (!img.isError()) {
                            loadedImg = img;
                        }
                    } catch (Exception ignored) {}

                    // Fallback to ImageIO (supports WebP via TwelveMonkeys ImageIO)
                    if (loadedImg == null) {
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                            java.awt.image.BufferedImage bImg = javax.imageio.ImageIO.read(bais);
                            if (bImg != null) {
                                loadedImg = javafx.embed.swing.SwingFXUtils.toFXImage(bImg, null);
                            }
                        } catch (Exception ex) {
                            logger.warn("ImageIO failed to decode image for URL: {}", url, ex);
                        }
                    }

                    if (loadedImg != null) {
                        final Image finalImg = loadedImg;
                        MEMORY_CACHE.put(memKey, finalImg);
                        Platform.runLater(() -> callback.accept(finalImg));
                    } else {
                        logger.warn("All decoders failed for image URL: {}", url);
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing image async for URL: " + url, e);
            }
        });
    }

    public static long getCacheSizeBytes() {
        if (!Files.exists(CACHE_DIR)) return 0;
        try (var stream = Files.walk(CACHE_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (Exception e) {
                            return 0;
                        }
                    }).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    public static void evict(String url) {
        if (url == null) return;
        MEMORY_CACHE.keySet().removeIf(k -> k.startsWith(url));
    }

    public static void clearCache() {
        MEMORY_CACHE.clear();
        if (Files.exists(CACHE_DIR)) {
            try (var stream = Files.walk(CACHE_DIR)) {
                stream.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception e) {
                logger.error("Failed to clear image cache directory", e);
            }
        }
    }

    private static String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    private static String getExtension(String url) {
        String cleanUrl = url.split("\\?")[0];
        int dot = cleanUrl.lastIndexOf('.');
        if (dot > 0 && dot > cleanUrl.lastIndexOf('/')) {
            String ext = cleanUrl.substring(dot).toLowerCase();
            if (ext.length() <= 5) return ext;
        }
        return ".png";
    }
}
