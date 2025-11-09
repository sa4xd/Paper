package io.papermc.paper.util;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicLong TOTAL_REQUESTS = new AtomicLong(0);

    static { HTTP_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT")); }

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.createContext("/stats", new StatsHandler()); // 新增
        server.setExecutor(null);
        server.start();
        System.out.println("ImageResizeServer started on port " + port + " [cache + ETag + 304 + /stats]");
    }

    // ================== /stats 接口 ==================
    static class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            JsonObject stats = new JsonObject();
            long total = ImageCacheManager.getTotalRequests();
            long hits = ImageCacheManager.getCacheHits();
            long misses = ImageCacheManager.getCacheMisses();
            long notModified = ImageCacheManager.get304Count();

            stats.addProperty("total_requests", TOTAL_REQUESTS.get());
            stats.addProperty("cache_hits", hits);
            stats.addProperty("cache_misses", misses);
            stats.addProperty("cache_hit_rate", String.format("%.2f%%", ImageCacheManager.getHitRate()));
            stats.addProperty("304_not_modified", notModified);
            stats.addProperty("cache_enabled", ImageCacheManager.isEnabled());

            if (ImageCacheManager.isEnabled()) {
                long fileCount = 0;
                long sizeBytes = 0;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(ImageCacheManager.getCacheDir())) {
                    for (Path p : stream) {
                        if (Files.isRegularFile(p)) {
                            fileCount++;
                            sizeBytes += Files.size(p);
                        }
                    }
                } catch (Exception e) {
                    stats.addProperty("cache_error", e.getMessage());
                }
                stats.addProperty("cache_files", fileCount);
                stats.addProperty("cache_size_bytes", sizeBytes);
                stats.addProperty("cache_size_mb", String.format("%.2f", sizeBytes / 1024.0 / 1024.0));
                stats.addProperty("cache_max_mb", ImageCacheManager.getCacheDir().toFile().getFreeSpace() / 1024 / 1024);
            }

            byte[] json = GSON.toJson(stats).getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, json.length);
            ex.getResponseBody().write(json);
            ex.close();
        }
    }

    // ================== 主处理 ==================
    static class ResizeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            TOTAL_REQUESTS.incrementAndGet();
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

        private void handleOriginalImage(HttpExchange exchange, String imageUrl) throws IOException {
            byte[] data;
            String contentType = guessContentType(imageUrl);
            if (contentType == null) contentType = "application/octet-stream";

            Path cachedFile = ImageCacheManager.isEnabled() ? ImageCacheManager.getCachedImagePath(imageUrl) : null;
            boolean cacheHit = false;

            if (cachedFile != null && Files.exists(cachedFile)) {
                data = Files.readAllBytes(cachedFile);
                ImageCacheManager.touchFile(cachedFile);
                ImageCacheManager.incrementCacheHit();
                cacheHit = true;
            } else {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    data = in.readAllBytes();
                } catch (Exception e) {
                    System.err.println("[ImageResize] 原图获取失败: " + imageUrl);
                    exchange.sendResponseHeaders(502, -1);
                    return;
                }
                ImageCacheManager.incrementCacheMiss();

                if (cachedFile != null) {
                    Files.write(cachedFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ImageCacheManager.touchFile(cachedFile);
                    ImageCacheManager.enforceCacheLimit();
                }
            }

            sendImageResponse(exchange, data, contentType, cacheHit, cachedFile);
        }

        private void handleResizedImage(HttpExchange exchange, String imageUrl, int w, int h) throws IOException {
            String key = imageUrl + "|" + w + "|" + h;
            String hash = ImageCacheManager.hashUrl(key);
            Path cachedFile = ImageCacheManager.isEnabled() ? ImageCacheManager.getCacheDir().resolve(hash + ".jpg") : null;

            byte[] jpeg;
            boolean cacheHit = false;

            if (cachedFile != null && Files.exists(cachedFile)) {
                jpeg = Files.readAllBytes(cachedFile);
                ImageCacheManager.touchFile(cachedFile);
                ImageCacheManager.incrementCacheHit();
                cacheHit = true;
            } else {
                BufferedImage original;
                try {
                    original = loadImage(imageUrl);
                    if (original == null) throw new IOException("Invalid image");
                } catch (Exception e) {
                    System.err.println("[ImageResize] 加载失败: " + imageUrl);
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }

                BufferedImage output = resizeImage(original,
                    w > 0 ? w : null,
                    h > 0 ? h : null,
                    original.getWidth(),
                    original.getHeight()
                );

                jpeg = encodeJpeg(output);
                ImageCacheManager.incrementCacheMiss();

                if (cachedFile != null) {
                    Files.write(cachedFile, jpeg, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ImageCacheManager.touchFile(cachedFile);
                    ImageCacheManager.enforceCacheLimit();
                }
            }

            sendImageResponse(exchange, jpeg, "image/jpeg", cacheHit, cachedFile);
        }

        // ================== 安全缩放（修复只指定 w/h）==================
private BufferedImage resizeImage(BufferedImage src, Integer tw, Integer th, int ow, int oh) {
    // 不缩放：目标尺寸都大于原图
    if ((tw != null && tw >= ow) && (th != null && th >= oh)) return src;

    // 同时指定 w 和 h：先放大再居中裁剪
    if (tw != null && th != null) {
        double scale = Math.max((double) tw / ow, (double) th / oh);
        int rw = Math.max(1, (int) (ow * scale));
        int rh = Math.max(1, (int) (oh * scale));
        BufferedImage scaled = resize(src, rw, rh);
        int x = Math.max(0, (rw - tw) / 2);
        int y = Math.max(0, (rh - th) / 2);
        int cw = Math.min(tw, rw - x);
        int ch = Math.min(th, rh - y);
        if (cw <= 0 || ch <= 0) return src;
        return scaled.getSubimage(x, y, cw, ch);
    }

    // 只指定 w：按宽度等比例缩放
    if (tw != null) {
        if (tw >= ow) return src;
        double scale = (double) tw / ow;
        int nh = Math.max(1, (int) (oh * scale));
        return resize(src, tw, nh);
    }

    // 只指定 h：按高度等比例缩放
    if (th != null) {
        if (th >= oh) return src;
        double scale = (double) th / oh;
        int nw = Math.max(1, (int) (ow * scale));
        return resize(src, nw, th);
    }

    // 都没指定，返回原图
    return src;
}


        private BufferedImage resize(BufferedImage src, int w, int h) {
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return dst;
        }

        private byte[] encodeJpeg(BufferedImage img) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.96f);
                try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(baos)) {
                    writer.setOutput(mos);
                    writer.write(null, new IIOImage(img, null, null), param);
                }
                writer.dispose();
            } catch (Exception e) {
                try { ImageIO.write(img, "jpeg", baos); } catch (IOException ignored) {}
            }
            return baos.toByteArray();
        }

        // ================== 响应 + 304 ==================
        private void sendImageResponse(HttpExchange ex, byte[] data, String contentType, boolean cacheHit, Path file) throws IOException {
            String etag = "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
            String lastModified = file != null ? formatHttpDate(Files.getLastModifiedTime(file).toMillis()) : formatHttpDate(System.currentTimeMillis());

            String ifNoneMatch = ex.getRequestHeaders().getFirst("If-None-Match");
            String ifModifiedSince = ex.getRequestHeaders().getFirst("If-Modified-Since");

            boolean notModified = (ifNoneMatch != null && ifNoneMatch.equals(etag)) ||
                                  (ifModifiedSince != null && !isModifiedSince(lastModified, ifModifiedSince));

            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            ex.getResponseHeaders().set("ETag", etag);
            ex.getResponseHeaders().set("Last-Modified", lastModified);
            ex.getResponseHeaders().set("X-Cache-Hit", String.valueOf(cacheHit));

            if (notModified) {
                ImageCacheManager.increment304();
                ex.sendResponseHeaders(304, -1);
                return;
            }

            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        }

        private BufferedImage loadImage(String url) throws Exception {
            if (!ImageCacheManager.isEnabled()) return ImageIO.read(new URL(url));
            Path cached = ImageCacheManager.getCachedImagePath(url);
            if (cached != null && Files.exists(cached)) return ImageIO.read(cached.toFile());
            BufferedImage img = ImageIO.read(new URL(url));
            if (img != null && cached != null) {
                ImageIO.write(img, "png", cached.toFile());
            }
            return img;
        }

        private void sendHtmlHelp(HttpExchange ex) throws IOException {
            String html = """
                <!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>
                <body><h2>图片缩放服务</h2>
                <p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>
                <p>支持：只指定一边等比例缩放，指定两边裁剪，不放大，原图返回</p>
                <p>示例：<a href='/?url=https://example.com/image.jpg&w=300&h=200'>点击查看缩放效果</a></p>
                <hr><p><a href='/stats'>查看统计 /stats</a></p>
                </body></html>""";
            byte[] data = html.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        }

        private String guessContentType(String url) {
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

        private String formatHttpDate(long millis) {
            return HTTP_DATE_FORMAT.format(new Date(millis));
        }

        private boolean isModifiedSince(String lastModified, String ifModifiedSince) {
            try {
                Date lm = HTTP_DATE_FORMAT.parse(lastModified);
                Date ims = HTTP_DATE_FORMAT.parse(ifModifiedSince);
                return lm.after(ims);
            } catch (Exception e) {
                return true;
            }
        }
    }
}
