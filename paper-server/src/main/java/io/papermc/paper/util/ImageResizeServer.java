package io.papermc.paper.util;

import com.sun.net.httpserver.*;
import com.google.gson.*;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648"));
    private static final int MAX_AGE_DAYS = Integer.parseInt(dotenv.get("CACHE_MAX_AGE_DAYS", "10"));
    private static final Path INDEX_FILE = CACHE_DIR.resolve("cache_index.json");
    private static final ConcurrentHashMap<String, CacheEntry> fileIndex = new ConcurrentHashMap<>();
    private static final AtomicLong currentCacheSize = new AtomicLong(0);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    static {
        if (ENABLE_CACHE) {
            initCacheIndex();
            startCleanupTask();
        }
    }

    public static void start(int port) throws IOException {
        if (ENABLE_CACHE && !Files.exists(CACHE_DIR)) {
            Files.createDirectories(CACHE_DIR);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("ImageResizeServer started on port " + port + " [INDEXED CACHE]");
    }

    // ================== 缓存索引管理（保留）==================
    private static void initCacheIndex() {
        if (!Files.exists(INDEX_FILE)) {
            rebuildIndexFromDisk();
            return;
        }
        try (Reader reader = Files.newBufferedReader(INDEX_FILE)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            long total = json.get("totalSize").getAsLong();
            currentCacheSize.set(total);
            JsonObject files = json.getAsJsonObject("files");
            for (Map.Entry<String, JsonElement> e : files.entrySet()) {
                String hash = e.getKey();
                JsonObject obj = e.getValue().getAsJsonObject();
                fileIndex.put(hash, new CacheEntry(obj.get("size").getAsLong(), obj.get("lastModified").getAsLong()));
            }
        } catch (Exception e) {
            System.err.println("[Cache] Load index failed, rebuilding...");
            rebuildIndexFromDisk();
        }
    }

    private static void rebuildIndexFromDisk() {
        fileIndex.clear();
        currentCacheSize.set(0);
        if (!Files.exists(CACHE_DIR)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CACHE_DIR)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".jpg") && !name.endsWith(".bin")) continue;
                String hash = name.substring(0, name.lastIndexOf('.'));
                long size = Files.size(p);
                long lastModified = Files.getLastModifiedTime(p).toMillis();
                fileIndex.put(hash, new CacheEntry(size, lastModified));
                currentCacheSize.addAndGet(size);
            }
            saveIndex();
        } catch (Exception e) {
            System.err.println("[Cache] Rebuild failed: " + e.getMessage());
        }
    }

    private static void addToIndex(String hash, long size, long lastModified) {
        fileIndex.put(hash, new CacheEntry(size, lastModified));
        currentCacheSize.addAndGet(size);
        saveIndexAsync();
    }

    private static void removeFromIndex(String hash) {
        CacheEntry e = fileIndex.remove(hash);
        if (e != null) {
            currentCacheSize.addAndGet(-e.size);
            saveIndexAsync();
        }
    }

    private static void touchIndex(String hash) {
        CacheEntry e = fileIndex.get(hash);
        if (e != null) {
            fileIndex.put(hash, new CacheEntry(e.size, System.currentTimeMillis()));
            saveIndexAsync();
        }
    }

    private static void saveIndexAsync() {
        Thread.startVirtualThread(() -> {
            try { saveIndex(); } catch (Exception ignored) {}
        });
    }

    private static void saveIndex() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("totalSize", currentCacheSize.get());
            JsonObject files = new JsonObject();
            fileIndex.forEach((h, e) -> {
                JsonObject o = new JsonObject();
                o.addProperty("size", e.size);
                o.addProperty("lastModified", e.lastModified);
                files.add(h, o);
            });
            json.add("files", files);
            Files.createDirectories(CACHE_DIR);
            try (Writer w = Files.newBufferedWriter(INDEX_FILE)) {
                GSON.toJson(json, w);
            }
        } catch (Exception ignored) {}
    }

    record CacheEntry(long size, long lastModified) {}

    private static void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> cleanupTask(), 1, 1, TimeUnit.HOURS);
    }

    private static void cleanupTask() {
        long now = System.currentTimeMillis();
        long expire = now - (MAX_AGE_DAYS * 24L * 3600 * 1000);

        fileIndex.entrySet().removeIf(e -> {
            if (e.getValue().lastModified < expire) {
                Path f = CACHE_DIR.resolve(e.getKey() + (e.getKey().endsWith(".bin") ? ".bin" : ".jpg"));
                try {
                    Files.deleteIfExists(f);
                    currentCacheSize.addAndGet(-e.getValue().size);
                    return true;
                } catch (IOException ex) {
                    return false;
                }
            }
            return false;
        });

        while (currentCacheSize.get() > CACHE_MAX_BYTES && !fileIndex.isEmpty()) {
            Optional<Map.Entry<String, CacheEntry>> oldest = fileIndex.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().lastModified));
            if (oldest.isEmpty()) break;
            String h = oldest.get().getKey();
            Path f = CACHE_DIR.resolve(h + (h.endsWith(".bin") ? ".bin" : ".jpg"));
            try {
                Files.deleteIfExists(f);
                removeFromIndex(h);
            } catch (IOException ex) {
                removeFromIndex(h);
            }
        }
        saveIndexAsync();
    }

    // ================== HTTP 处理 ==================
    static class ResizeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String imageUrl = null;
            Integer targetW = null, targetH = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length != 2) continue;
                    switch (kv[0]) {
                        case "url" -> imageUrl = URLDecoder.decode(kv[1], "UTF-8");
                        case "w" -> targetW = Integer.parseInt(kv[1]);
                        case "h" -> targetH = Integer.parseInt(kv[1]);
                    }
                }
            }

            if (imageUrl == null) {
                sendHtmlHelp(exchange);
                return;
            }

            if (targetW == null && targetH == null) {
                handleOriginalImage(exchange, imageUrl);
            } else {
                handleResizedImage(exchange, imageUrl, targetW, targetH);
            }
        }

        // ================== 原图直传（已修复）==================
        private void handleOriginalImage(HttpExchange exchange, String imageUrl) throws IOException {
            String hash = sha256(imageUrl);
            Path cachedFile = CACHE_DIR.resolve(hash + ".bin");

            if (ENABLE_CACHE && Files.exists(cachedFile)) {
                byte[] data = Files.readAllBytes(cachedFile);
                Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(System.currentTimeMillis()));
                touchIndex(hash);
                String contentType = guessContentTypeFromUrl(imageUrl);
                sendResponse(exchange, data, contentType, true);
                return;
            }

            HttpURLConnection conn = null;
            try {
                URL url = new URL(imageUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "ImageResizeServer/1.0");

                try (InputStream in = conn.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    String contentType = conn.getContentType();
                    if (contentType == null || contentType.contains("text") || contentType.contains("html")) {
                        contentType = guessContentTypeFromUrl(imageUrl);
                    }
                    if (contentType == null) contentType = "application/octet-stream";

                    sendResponse(exchange, data, contentType, false);

                    if (ENABLE_CACHE && isImageContentType(contentType)) {
                        Files.write(cachedFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        long now = System.currentTimeMillis();
                        Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(now));
                        addToIndex(hash, data.length, now);
                        if (currentCacheSize.get() > CACHE_MAX_BYTES) cleanupTask();
                    }
                }
            } catch (Exception e) {
                System.err.println("[ImageResize] 原图获取失败: " + imageUrl + " | " + e.getMessage());
                exchange.sendResponseHeaders(502, -1);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        // ================== 缩放处理（100% 回归原版逻辑）==================
        private void handleResizedImage(HttpExchange exchange, String imageUrl, int w, int h) throws IOException {
            String key = imageUrl + "|" + w + "|" + h;
            String hash = sha256(key);
            Path cachedFile = CACHE_DIR.resolve(hash + ".jpg");

            // 缓存命中
            if (ENABLE_CACHE && Files.exists(cachedFile)) {
                byte[] jpeg = Files.readAllBytes(cachedFile);
                Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(System.currentTimeMillis()));
                touchIndex(hash);
                sendResponse(exchange, jpeg, "image/jpeg", true);
                return;
            }

            // 加载原图（原版 loadImage 逻辑）
            BufferedImage original;
            try {
                original = loadImage(imageUrl);
                if (original == null) throw new IOException("Invalid image");
            } catch (Exception e) {
                System.err.println("[ImageResize] 加载图片失败: " + imageUrl + " | " + e.getMessage());
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            int ow = original.getWidth(), oh = original.getHeight();
            BufferedImage output = original;

            boolean shouldResize = false;
            if ((w < ow) || (h < oh)) {
                shouldResize = true;
            }

            if (shouldResize) {
                double scale;
                int rw, rh;

                if (w > 0 && h > 0) {
                    double scaleW = (double) w / ow;
                    double scaleH = (double) h / oh;
                    scale = Math.max(scaleW, scaleH);
                    rw = (int) (ow * scale);
                    rh = (int) (oh * scale);
                    BufferedImage scaled = resize(original, rw, rh);
                    int x = Math.max(0, (rw - w) / 2);
                    int y = Math.max(0, (rh - h) / 2);
                    output = scaled.getSubimage(x, y, Math.min(w, rw), Math.min(h, rh));
                } else if (w > 0) {
                    scale = (double) w / ow;
                    rw = w;
                    rh = (int) (oh * scale);
                    output = resize(original, rw, rh);
                } else {
                    scale = (double) h / oh;
                    rh = h;
                    rw = (int) (ow * scale);
                    output = resize(original, rw, rh);
                }
            }

            // JPEG 编码（原版逻辑）
            float quality = (output.getWidth() <= 1000 && output.getHeight() <= 1000) ? 1.0f : 0.96f;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
                if (!writers.hasNext()) throw new IOException("No JPEG writer");
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.setOutput(new MemoryCacheImageOutputStream(baos));
                writer.write(null, new IIOImage(output, null, null), param);
                writer.dispose();
            } catch (Exception e) {
                System.err.println("[ImageResize] JPEG 编码失败: " + e.getMessage());
                exchange.sendResponseHeaders(500, -1);
                return;
            }

            byte[] jpeg = baos.toByteArray();
            sendResponse(exchange, jpeg, "image/jpeg", false);

            // 写入缓存
            if (ENABLE_CACHE) {
                try {
                    Files.write(cachedFile, jpeg, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    long now = System.currentTimeMillis();
                    Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(now));
                    addToIndex(hash, jpeg.length, now);
                    if (currentCacheSize.get() > CACHE_MAX_BYTES) cleanupTask();
                } catch (Exception e) {
                    System.err.println("[Cache] 写入缓存失败: " + e.getMessage());
                }
            }
        }

        // ================== 原版 loadImage + resize（关键！）==================
        private BufferedImage loadImage(String url) throws Exception {
            if (!ENABLE_CACHE) return ImageIO.read(new URL(url));
            String hash = sha256(url);
            Path cachedFile = CACHE_DIR.resolve(hash + ".img");
            if (Files.exists(cachedFile)) {
                touchFile(cachedFile);
                return ImageIO.read(cachedFile.toFile());
            }
            BufferedImage img = ImageIO.read(new URL(url));
            if (img != null && ENABLE_CACHE) {
                ImageIO.write(img, "png", cachedFile.toFile());
                touchFile(cachedFile);
                enforceCacheLimit();
            }
            return img;
        }

        private BufferedImage resize(BufferedImage src, int w, int h) {
            BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return resized;
        }

        private void enforceCacheLimit() throws IOException {
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
        }

        // ================== 工具方法 ==================
        private void sendResponse(HttpExchange ex, byte[] data, String contentType, boolean hit) throws IOException {
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            ex.getResponseHeaders().set("ETag", "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"");
            ex.getResponseHeaders().set("X-Cache-Hit", String.valueOf(hit));
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        }

        private void sendHtmlHelp(HttpExchange ex) throws IOException {
            String html = """
                <!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>
                <body><h2>图片缩放服务</h2>
                <p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>
                <p>支持：只指定一边等比例缩放，指定两边裁剪，不放大，原图返回</p>
                <p>示例：<a href='/?url=https://example.com/image.jpg&w=300&h=200'>点击查看缩放效果</a></p>
                </body></html>""";
            byte[] data = html.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        }

        private String guessContentTypeFromUrl(String url) {
            try {
                String path = new URL(url).getPath().toLowerCase();
                if (path.endsWith(".png")) return "image/png";
                if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
                if (path.endsWith(".gif")) return "image/gif";
                if (path.endsWith(".webp")) return "image/webp";
                if (path.endsWith(".svg")) return "image/svg+xml";
            } catch (Exception ignored) {}
            return null;
        }

        private boolean isImageContentType(String ct) {
            return ct != null && ct.startsWith("image/");
        }

        private void touchFile(Path file) {
            try {
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (Exception ignored) {}
        }

        private String sha256(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(input.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return UUID.randomUUID().toString().replace("-", "");
            }
        }
    }
}
