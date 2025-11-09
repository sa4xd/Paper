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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648")); // 2GB
    private static final int MAX_AGE_DAYS = Integer.parseInt(dotenv.get("CACHE_MAX_AGE_DAYS", "10")); // 10 天
    private static final Path INDEX_FILE = CACHE_DIR.resolve("cache_index.json");

    // 全局索引：hash -> {size, lastModified}
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
        System.out.println("ImageResizeServer started on port " + port + " [OUTPUT-ONLY CACHE]");
    }

    // ================== 索引管理 ==================
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
            System.err.println("Load index failed, rebuilding...");
            rebuildIndexFromDisk();
        }
    }

    private static void rebuildIndexFromDisk() {
        fileIndex.clear();
        currentCacheSize.set(0);
        if (!Files.exists(CACHE_DIR)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CACHE_DIR, "*.jpg")) {
            for (Path p : stream) {
                String hash = p.getFileName().toString().replace(".jpg", "");
                long size = Files.size(p);
                long lastModified = Files.getLastModifiedTime(p).toMillis();
                fileIndex.put(hash, new CacheEntry(size, lastModified));
                currentCacheSize.addAndGet(size);
            }
            saveIndex();
        } catch (Exception e) {
            System.err.println("Rebuild index failed: " + e.getMessage());
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

    // ================== 后台清理 ==================
    private static void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long expire = now - (MAX_AGE_DAYS * 24L * 3600 * 1000);
            fileIndex.entrySet().removeIf(e -> {
                if (e.getValue().lastModified < expire) {
                    Path f = CACHE_DIR.resolve(e.getKey() + ".jpg");
                    try { Files.deleteIfExists(f); currentCacheSize.addAndGet(-e.getValue().size); return true; }
                    catch (IOException ex) { return false; }
                }
                return false;
            });
            while (currentCacheSize.get() > CACHE_MAX_BYTES && !fileIndex.isEmpty()) {
                Optional<Map.Entry<String, CacheEntry>> oldest = fileIndex.entrySet().stream()
                        .min(Comparator.comparingLong(e -> e.getValue().lastModified));
                if (oldest.isEmpty()) break;
                String h = oldest.get().getKey();
                Path f = CACHE_DIR.resolve(h + ".jpg");
                try { Files.deleteIfExists(f); removeFromIndex(h); }
                catch (IOException ex) { removeFromIndex(h); }
            }
            saveIndexAsync();
        }, 1, 1, TimeUnit.HOURS);
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

            // 原图直传（无 w/h）
            if (targetW == null && targetH == null) {
                handleOriginalImage(exchange, imageUrl);
                return;
            }

            // 缩放处理
            handleResizedImage(exchange, imageUrl, targetW, targetH);
        }

        // ------------------- 原图直传（缓存原始响应） -------------------
        private void handleOriginalImage(HttpExchange exchange, String imageUrl) throws IOException {
            String hash = sha256(imageUrl);
            Path cachedFile = CACHE_DIR.resolve(hash + ".bin");  // 原始字节

            if (ENABLE_CACHE && Files.exists(cachedFile)) {
                byte[] data = Files.readAllBytes(cachedFile);
                Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(System.currentTimeMillis()));
                touchIndex(hash);
                sendCachedResponse(exchange, data, true);
                return;
            }

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
                long lm = conn.getLastModified();
                if (lm > 0) exchange.getResponseHeaders().set("Last-Modified", formatHttpDate(lm));
                exchange.getResponseHeaders().set("X-Cache-Hit", "false");

                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
                exchange.close();

                // 缓存原始字节
                if (ENABLE_CACHE) {
                    Files.write(cachedFile, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    long now = System.currentTimeMillis();
                    Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(now));
                    addToIndex(hash, data.length, now);
                    if (currentCacheSize.get() > CACHE_MAX_BYTES) cleanupNow();
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            } finally {
                conn.disconnect();
            }
        }

        // ------------------- 缩放处理（缓存最终 JPEG） -------------------
        private void handleResizedImage(HttpExchange exchange, String imageUrl, int w, int h) throws IOException {
            String key = imageUrl + "|" + w + "|" + h;
            String hash = sha256(key);
            Path cachedFile = CACHE_DIR.resolve(hash + ".jpg");

            // 缓存命中 → 直接返回
            if (ENABLE_CACHE && Files.exists(cachedFile)) {
                byte[] jpeg = Files.readAllBytes(cachedFile);
                Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(System.currentTimeMillis()));
                touchIndex(hash);
                sendCachedResponse(exchange, jpeg, true);
                return;
            }

            // 下载原图
            BufferedImage original = downloadImage(imageUrl);
            if (original == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            // 缩放
            BufferedImage resized = resizeImage(original, w, h, original.getWidth(), original.getHeight());

            // 编码为 JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.96f);
            try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(mos);
                writer.write(null, new IIOImage(resized, null, null), param);
            } finally {
                writer.dispose();
            }
            byte[] jpeg = baos.toByteArray();

            // 返回
            sendCachedResponse(exchange, jpeg, false);

            // 缓存最终输出
            if (ENABLE_CACHE) {
                Files.write(cachedFile, jpeg, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                long now = System.currentTimeMillis();
                Files.setLastModifiedTime(cachedFile, FileTime.fromMillis(now));
                addToIndex(hash, jpeg.length, now);
                if (currentCacheSize.get() > CACHE_MAX_BYTES) cleanupNow();
            }
        }

        private void sendCachedResponse(HttpExchange exchange, byte[] data, boolean hit) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", hit && data.length > 0 && data[0] == (byte)0xFF && data[1] == (byte)0xD8 ? "image/jpeg" : "application/octet-stream");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            exchange.getResponseHeaders().set("ETag", "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"");
            exchange.getResponseHeaders().set("X-Cache-Hit", String.valueOf(hit));
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        }

        private BufferedImage downloadImage(String urlStr) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            try (InputStream in = conn.getInputStream()) {
                ImageInputStream iis = ImageIO.createImageInputStream(in);
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) return null;
                ImageReader reader = readers.next();
                reader.setInput(iis, true, true);
                try {
                    ImageReadParam param = reader.getDefaultReadParam();
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width > 4096 || height > 4096) {
                        double s = Math.min(4096.0 / width, 4096.0 / height);
                        param.setSourceSubsampling((int)s, (int)s, 0, 0);
                    }
                    return reader.read(0, param);
                } finally {
                    reader.dispose();
                }
            } finally {
                conn.disconnect();
            }
        }

        private BufferedImage resizeImage(BufferedImage src, int tw, int th, int ow, int oh) {
            if (tw > 0 && th > 0) {
                double sw = (double)tw/ow, sh = (double)th/oh;
                double scale = Math.max(sw, sh);
                int rw = (int)(ow*scale), rh = (int)(oh*scale);
                BufferedImage temp = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = temp.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, rw, rh, null);
                g.dispose();
                int x = Math.max(0, (rw-tw)/2), y = Math.max(0, (rh-th)/2);
                return temp.getSubimage(x, y, Math.min(tw, rw-x), Math.min(th, rh-y));
            } else if (tw > 0) {
                int nh = (int)(oh * (double)tw / ow);
                return scaleImage(src, tw, nh);
            } else {
                int nw = (int)(ow * (double)th / oh);
                return scaleImage(src, nw, th);
            }
        }

        private BufferedImage scaleImage(BufferedImage src, int w, int h) {
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return dst;
        }

        private void cleanupNow() {
            // 立即触发一次清理（超限时）
            startCleanupTask().run();
        }

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

        private String formatHttpDate(long millis) {
            return DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis));
        }

        private String sha256(String input) throws IOException {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(input.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new IOException("Hash error", e);
            }
        }
    }
}
