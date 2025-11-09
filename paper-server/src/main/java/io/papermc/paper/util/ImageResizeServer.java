package io.papermc.paper.util;

import com.sun.net.httpserver.*;
import com.google.gson.*;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648")); // 2GB
    private static final Path INDEX_FILE = CACHE_DIR.resolve("cache_index.json");

    // 全局缓存大小（内存索引）
    private static final AtomicLong currentCacheSize = new AtomicLong(0);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, CacheEntry> fileIndex = new ConcurrentHashMap<>();

    static {
        if (ENABLE_CACHE) {
            initCacheIndex();
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
        System.out.println("ImageResizeServer started on port " + port + (ENABLE_CACHE ? " with indexed cache" : ""));
    }

    // ================== 索引管理 ==================
    private static void initCacheIndex() {
        if (!Files.exists(INDEX_FILE)) {
            // 首次启动：扫描磁盘构建索引
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
                long size = obj.get("size").getAsLong();
                long lastModified = obj.get("lastModified").getAsLong();
                fileIndex.put(hash, new CacheEntry(size, lastModified));
            }
            System.out.println("Cache index loaded: " + fileIndex.size() + " files, " + total + " bytes");
        } catch (Exception e) {
            System.err.println("Failed to load cache index, rebuilding...");
            rebuildIndexFromDisk();
        }
    }

    private static void rebuildIndexFromDisk() {
        fileIndex.clear();
        currentCacheSize.set(0);
        if (!Files.exists(CACHE_DIR)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CACHE_DIR, "*.img")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    String name = p.getFileName().toString();
                    String hash = name.substring(0, name.length() - 4); // remove .img
                    long size = Files.size(p);
                    long lastModified = Files.getLastModifiedTime(p).toMillis();
                    fileIndex.put(hash, new CacheEntry(size, lastModified));
                    currentCacheSize.addAndGet(size);
                }
            }
            saveIndex();
            System.out.println("Cache index rebuilt: " + fileIndex.size() + " files, " + currentCacheSize.get() + " bytes");
        } catch (Exception e) {
            System.err.println("Failed to rebuild cache index: " + e.getMessage());
        }
    }

    private static void saveIndex() {
        if (!ENABLE_CACHE) return;
        try {
            JsonObject json = new JsonObject();
            json.addProperty("totalSize", currentCacheSize.get());

            JsonObject files = new JsonObject();
            fileIndex.forEach((hash, entry) -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("size", entry.size);
                obj.addProperty("lastModified", entry.lastModified);
                files.add(hash, obj);
            });
            json.add("files", files);

            Files.createDirectories(CACHE_DIR);
            try (Writer writer = Files.newBufferedWriter(INDEX_FILE)) {
                GSON.toJson(json, writer);
            }
        } catch (Exception e) {
            System.err.println("Failed to save cache index: " + e.getMessage());
        }
    }

    private static void updateIndex(String hash, long size, long lastModified) {
        fileIndex.put(hash, new CacheEntry(size, lastModified));
        currentCacheSize.addAndGet(size);
        saveIndexAsync();
    }

    private static void removeFromIndex(String hash) {
        CacheEntry entry = fileIndex.remove(hash);
        if (entry != null) {
            currentCacheSize.addAndGet(-entry.size);
            saveIndexAsync();
        }
    }

    private static void saveIndexAsync() {
        // 异步保存，防阻塞
        Thread.startVirtualThread(() -> {
            try { saveIndex(); } catch (Exception ignored) {}
        });
    }

    record CacheEntry(long size, long lastModified) {}

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
                proxyOriginalImage(exchange, imageUrl);
                return;
            }

            ImageInfo imageInfo;
            try {
                imageInfo = loadImageWithCache(imageUrl);
                if (imageInfo == null || imageInfo.image() == null) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            BufferedImage original = imageInfo.image();
            int ow = original.getWidth(), oh = original.getHeight();
            BufferedImage output = original;

            boolean shouldResize = (targetW != null && targetW < ow) || (targetH != null && targetH < oh);
            if (shouldResize) {
                output = performResize(original, targetW, targetH, ow, oh);
            }

            sendJpegResponse(exchange, output, imageInfo);
        }

        // ================== 辅助方法 ==================
        private void sendHtmlHelp(HttpExchange exchange) throws IOException {
            String html = """
                    <!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>
                    <body><h2>图片缩放服务</h2>
                    <p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>
                    <p>支持：只指定一边等比例缩放，指定两边裁剪，不放大，原图返回</p>
                    <p>示例：<a href='/?url=https://example.com/image.jpg&w=300&h=200'>点击查看缩放效果</a></p>
                    </body></html>""";
            byte[] data = html.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        }

        private void proxyOriginalImage(HttpExchange exchange, String imageUrl) throws IOException {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            try (InputStream in = conn.getInputStream()) {
                byte[] data = in.readAllBytes();
                String contentType = conn.getContentType();
                if (contentType == null || contentType.isEmpty()) {
                    contentType = URLConnection.guessContentTypeFromName(url.getPath());
                }
                if (contentType == null) contentType = "application/octet-stream";

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");

                String etag = "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
                exchange.getResponseHeaders().set("ETag", etag);
                long lastModified = conn.getLastModified();
                if (lastModified > 0) {
                    exchange.getResponseHeaders().set("Last-Modified", formatHttpDate(lastModified));
                }

                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
                exchange.close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        record ImageInfo(BufferedImage image, Path cachedFile, boolean cacheHit) {}

        private ImageInfo loadImageWithCache(String urlStr) throws Exception {
            if (!ENABLE_CACHE) {
                BufferedImage img = readImageFromUrl(urlStr);
                return new ImageInfo(img, null, false);
            }

            String hash = sha256(urlStr);
            Path cachedFile = CACHE_DIR.resolve(hash + ".img");
            boolean cacheHit = Files.exists(cachedFile);

            if (cacheHit) {
                BufferedImage img = ImageIO.read(cachedFile.toFile());
                // 更新访问时间（模拟 LRU）
                long now = System.currentTimeMillis();
                Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(now));
                CacheEntry entry = fileIndex.get(hash);
                if (entry != null) {
                    fileIndex.put(hash, new CacheEntry(entry.size, now));
                    saveIndexAsync();
                }
                return new ImageInfo(img, cachedFile, true);
            }

            BufferedImage img = readImageFromUrl(urlStr);
            if (img != null) {
                long fileSize = 0;
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(cachedFile.toFile())) {
                    if (ImageIO.write(img, detectFormatName(urlStr), ios)) {
                        fileSize = Files.size(cachedFile);
                    }
                } catch (Exception e) {
                    fileSize = writeAsPngAndGetSize(img, cachedFile);
                }

                if (fileSize > 0) {
                    long now = System.currentTimeMillis();
                    Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(now));
                    updateIndex(hash, fileSize, now);
                    if (currentCacheSize.get() > CACHE_MAX_BYTES) {
                        enforceCacheLimit();
                    }
                }
            }
            return new ImageInfo(img, cachedFile, false);
        }

        private long writeAsPngAndGetSize(BufferedImage img, Path path) {
            try {
                ImageIO.write(img, "png",
