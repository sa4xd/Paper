package io.papermc.paper.util;

import com.sun.net.httpserver.*;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final boolean ENABLE_CACHE = ImageCacheManager.isEnabled();
    private static final Path CACHE_DIR = ImageCacheManager.getCacheDir();

    public static void start(int port) throws IOException {
        if (ENABLE_CACHE && !Files.exists(CACHE_DIR)) {
            Files.createDirectories(CACHE_DIR);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("ImageResizeServer started on port " + port + (ENABLE_CACHE ? " with cache" : ""));
    }

    static class ResizeHandler implements HttpHandler {
        private static final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        static {
            HTTP_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String imageUrl = null;
            int targetW = -1;   // ← 改为 int，默认 -1 表示未指定
            int targetH = -1;   // ← 改为 int，默认 -1 表示未指定

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length != 2) continue;
                    switch (kv[0]) {
                        case "url":
                            imageUrl = URLDecoder.decode(kv[1], "UTF-8");
                            break;
                        case "w":
                            try {
                                targetW = Integer.parseInt(kv[1]);
                            } catch (NumberFormatException ignored) {}
                            break;
                        case "h":
                            try {
                                targetH = Integer.parseInt(kv[1]);
                            } catch (NumberFormatException ignored) {}
                            break;
                    }
                }
            }

            if (imageUrl == null) {
                sendHtmlHelp(exchange);
                return;
            }

            // 原图直传（不缩放）
            if (targetW == -1 && targetH == -1) {
                handleOriginalImage(exchange, imageUrl);
                return;
            }

            // 缩放处理 + 304 支持
            handleResizeImage(exchange, imageUrl, targetW, targetH);
        }

        private void sendHtmlHelp(HttpExchange exchange) throws IOException {
            byte[] html = ("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>" +
                    "<body><h2>图片缩放服务</h2><p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>" +
                    "<p>支持：只指定一边等比例缩放，指定两边裁剪，不放大，原图返回</p>" +
                    "<p>示例：<a href='/?url=https://example.com/image.jpg&w=300&h=200'>点击查看缩放效果</a></p>" +
                    "</body></html>").getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.getResponseBody().close();
        }

        private void handleOriginalImage(HttpExchange exchange, String imageUrl) throws IOException {
            String etag = "\"" + ImageCacheManager.hashUrl(imageUrl) + "\"";
            exchange.getResponseHeaders().add("ETag", etag);
            exchange.getResponseHeaders().add("Last-Modified", formatHttpDate(System.currentTimeMillis()));
            exchange.getResponseHeaders().add("X-Cache-Hit", "MISS");
            ImageCacheManager.incrementCacheMiss();

            try (InputStream in = new URL(imageUrl).openStream()) {
                byte[] raw = in.readAllBytes();
                String contentType = probeContentType(imageUrl);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000");

                exchange.sendResponseHeaders(200, raw.length);
                exchange.getResponseBody().write(raw);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
            }
        }

        private void handleResizeImage(HttpExchange exchange, String imageUrl, int targetW, int targetH) throws IOException {
            String urlHash = ImageCacheManager.hashUrl(imageUrl);
            String etag = "\"" + urlHash + "\"";
            Path cachedFile = ImageCacheManager.getCachedImagePath(imageUrl);

            // === 1. 304 Not Modified 检查 ===
            if (checkNotModified(exchange, etag, cachedFile)) {
                exchange.getResponseHeaders().add("ETag", etag);
                exchange.getResponseHeaders().add("Last-Modified", formatHttpDate(getFileTime(cachedFile)));
                exchange.getResponseHeaders().add("X-Cache-Hit", "HIT");
                exchange.sendResponseHeaders(304, -1);
                ImageCacheManager.increment304();
                ImageCacheManager.incrementCacheHit();
                return;
            }

            // === 2. 加载原图（缓存或网络）===
            BufferedImage original;
            boolean cacheHit = false;

            try {
                if (cachedFile != null && Files.exists(cachedFile)) {
                    original = ImageIO.read(cachedFile.toFile());
                    cacheHit = true;
                    ImageCacheManager.touchFile(cachedFile);
                } else {
                    original = ImageIO.read(new URL(imageUrl));
                    if (original != null && cachedFile != null) {
                        ImageIO.write(original, "png", cachedFile.toFile());
                        ImageCacheManager.enforceCacheLimit();
                    }
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            if (original == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            if (cacheHit) {
                ImageCacheManager.incrementCacheHit();
            } else {
                ImageCacheManager.incrementCacheMiss();
            }

            int ow = original.getWidth(), oh = original.getHeight();
            BufferedImage output = original;

            // ← 修复点：targetW / targetH 现在是 int，-1 表示未指定
            boolean shouldResize = (targetW > 0 && targetW < ow) || (targetH > 0 && targetH < oh);

            if (shouldResize) {
                double scale;
                int rw, rh;

                if (targetW > 0 && targetH > 0) {
                    double scaleW = (double) targetW / ow;
                    double scaleH = (double) targetH / oh;
                    scale = Math.max(scaleW, scaleH);
                    rw = (int) (ow * scale);
                    rh = (int) (oh * scale);
                    BufferedImage scaled = resize(original, rw, rh);
                    int x = Math.max(0, (rw - targetW) / 2);
                    int y = Math.max(0, (rh - targetH) / 2);
                    output = scaled.getSubimage(x, y, Math.min(targetW, rw), Math.min(targetH, rh));
                } else if (targetW > 0) {
                    scale = (double) targetW / ow;
                    rw = targetW;
                    rh = (int) (oh * scale);
                    output = resize(original, rw, rh);
                } else if (targetH > 0) {
                    scale = (double) targetH / oh;
                    rh = targetH;
                    rw = (int) (ow * scale);
                    output = resize(original, rw, rh);
                }
            }

            int outW = output.getWidth(), outH = output.getHeight();
            float quality = (outW <= 1000 && outH <= 1000) ? 1.0f : 0.96f;

            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000");
            exchange.getResponseHeaders().add("ETag", etag);
            exchange.getResponseHeaders().add("Last-Modified", formatHttpDate(getFileTime(cachedFile)));
            exchange.getResponseHeaders().add("X-Cache-Hit", cacheHit ? "HIT" : "MISS");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new javax.imageio.IIOImage(output, null, null), param);
            writer.dispose();

            byte[] bytes = baos.toByteArray();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }

        private boolean checkNotModified(HttpExchange exchange, String etag, Path cachedFile) {
            if (!ENABLE_CACHE) return false;

            List<String> ifNoneMatch = exchange.getRequestHeaders().get("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.stream().anyMatch(h -> h.equals(etag) || h.equals("*"))) {
                return true;
            }

            List<String> ifModifiedSince = exchange.getRequestHeaders().get("If-Modified-Since");
            if (ifModifiedSince != null && !ifModifiedSince.isEmpty() && cachedFile != null && Files.exists(cachedFile)) {
                try {
                    long fileTime = Files.getLastModifiedTime(cachedFile).toMillis();
                    long sinceTime = HTTP_DATE_FORMAT.parse(ifModifiedSince.get(0)).getTime();
                    return fileTime <= sinceTime;
                } catch (ParseException | IOException ignored) {}
            }
            return false;
        }

        private long getFileTime(Path file) {
            if (file != null && Files.exists(file)) {
                try {
                    return Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    return System.currentTimeMillis();
                }
            }
            return System.currentTimeMillis();
        }

        private String formatHttpDate(long millis) {
            return HTTP_DATE_FORMAT.format(new Date(millis));
        }

        private String probeContentType(String url) {
            try {
                String path = new URL(url).getPath();
                String type = Files.probeContentType(Paths.get(path));
                return type != null ? type : "application/octet-stream";
            } catch (Exception e) {
                return "application/octet-stream";
            }
        }

        private BufferedImage resize(BufferedImage src, int w, int h) {
            BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return resized;
        }
    }
}
