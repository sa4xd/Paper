package io.papermc.paper.util;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;  // 必须添加这行！
import java.security.MessageDigest;
import java.util.Comparator;

public class ImageCacheManager {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648")); // 2GB

    static {
        if (ENABLE_CACHE && !Files.exists(CACHE_DIR)) {
            try {
                Files.createDirectories(CACHE_DIR);
            } catch (IOException e) {
                System.err.println("[Cache] Failed to create cache dir: " + e.getMessage());
            }
        }
    }

    public static boolean isEnabled() {
        return ENABLE_CACHE;
    }

    public static Path getCacheDir() {
        return CACHE_DIR;
    }

    public static String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(Double.doubleToLongBits(Math.random()));
        }
    }

    public static Path getCachedImagePath(String url) {
        if (!ENABLE_CACHE) return null;
        return CACHE_DIR.resolve(hashUrl(url) + ".img");
    }

    public static void enforceCacheLimit() {
        if (!ENABLE_CACHE) return;

        try {
            long total = Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum();

            if (total <= CACHE_MAX_BYTES) return;

            Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .forEach(p -> {
                        if (total > CACHE_MAX_BYTES) {
                            long size = p.toFile().length();
                            try {
                                Files.delete(p);
                                total -= size;
                            } catch (IOException ignored) {}
                        }
                    });
        } catch (IOException e) {
            System.err.println("[Cache] Failed to enforce limit: " + e.getMessage());
        }
    }

    public static void touchFile(Path file) {
        if (!ENABLE_CACHE || file == null) return;
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {}
    }
}
