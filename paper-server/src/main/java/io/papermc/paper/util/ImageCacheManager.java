package io.papermc.paper.util;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 修复：使用 AtomicLong 替代 long，避免 lambda 修改局部变量
     */
    public static void enforceCacheLimit() {
        if (!ENABLE_CACHE) return;

        try {
            // 1. 计算总大小
            final AtomicLong totalSize = new AtomicLong(
                Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum()
            );

            if (totalSize.get() <= CACHE_MAX_BYTES) return;

            // 2. 按修改时间排序，删除最旧的文件
            Files.walk(CACHE_DIR)
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .forEach(p -> {
                    if (totalSize.get() > CACHE_MAX_BYTES) {
                        long size = p.toFile().length();
                        try {
                            Files.delete(p);
                            totalSize.addAndGet(-size);  // 安全修改
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
