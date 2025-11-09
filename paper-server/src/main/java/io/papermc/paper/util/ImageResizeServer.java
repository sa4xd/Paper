package io.papermc.paper.util;

import com.sun.net.httpserver.*;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648")); // 2GB

    public static void start(int port) throws IOException {
        if (ENABLE_CACHE && !Files.exists(CACHE_DIR)) {
            Files.createDirectories(CACHE_DIR);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null); // 使用默认线程池
        server.start();
        System.out.println("ImageResizeServer started on port " + port + (ENABLE_CACHE ? " with cache" : ""));
    }

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

            // ---------- 首页 ----------
            if (imageUrl == null) {
                sendHtmlHelp(exchange);
                return;
            }

            // ---------- 原图直传 ----------
            if (targetW == null && targetH == null) {
                proxyOriginalImage(exchange, imageUrl);
                return;
            }

            // ---------- 加载图片（带缓存） ----------
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

            // ---------- 写入 JPEG ----------
            sendJpegResponse(exchange, output, imageInfo);
        }

        // ------------------- 辅助方法 -------------------

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
            conn.setRequestMethod("GET");
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

                // ETag / Last-Modified
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
                return new ImageInfo(img, cachedFile, true);
            }

            BufferedImage img = readImageFromUrl(urlStr);
            if (img != null) {
                // 保存为原始格式（通过 ImageIO 自动检测）
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(cachedFile.toFile())) {
                    if (ImageIO.write(img, detectFormatName(urlStr), ios)) {
                        enforceCacheLimit();
                    }
                } catch (Exception e) {
                    // 降级为 PNG
                    ImageIO.write(img, "png", cachedFile.toFile());
                    enforceCacheLimit();
                }
            }
            return new ImageInfo(img, cachedFile, false);
        }

        private BufferedImage readImageFromUrl(String urlStr) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            try (InputStream in = conn.getInputStream()) {
                // 使用 ImageIO 增量读取，防止大图 OOM
                ImageInputStream iis = ImageIO.createImageInputStream(in);
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) return null;
                ImageReader reader = readers.next();
                reader.setInput(iis, true, true);
                try {
                    ImageReadParam param = reader.getDefaultReadParam();
                    // 读取最小尺寸（避免一次性加载超大图）
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width > 4096 || height > 4096) {
                        double scale = Math.min(4096.0 / width, 4096.0 / height);
                        param.setSourceSubsampling((int) scale, (int) scale, 0, 0);
                    }
                    return reader.read(0, param);
                } finally {
                    reader.dispose();
                }
            } finally {
                conn.disconnect();
            }
        }

        private String detectFormatName(String url) {
            String path = new URL(url).getPath().toLowerCase();
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "jpeg";
            if (path.endsWith(".png")) return "png";
            if (path.endsWith(".gif")) return "gif";
            if (path.endsWith(".bmp")) return "bmp";
            if (path.endsWith(".webp")) return "webp";
            return "png";
        }

        private BufferedImage performResize(BufferedImage src, Integer targetW, Integer targetH, int ow, int oh) {
            if (targetW != null && targetH != null) {
                // 按最大比例缩放 → 居中裁剪
                double scaleW = (double) targetW / ow;
                double scaleH = (double) targetH / oh;
                double scale = Math.max(scaleW, scaleH);
                int rw = (int) (ow * scale);
                int rh = (int) (oh * scale);
                BufferedImage scaled = resizeImage(src, rw, rh);
                int x = Math.max(0, (rw - targetW) / 2);
                int y = Math.max(0, (rh - targetH) / 2);
                return scaled.getSubimage(x, y, Math.min(targetW, rw), Math.min(targetH, rh));
            } else if (targetW != null) {
                return resizeImage(src, targetW, (int) (oh * (double) targetW / ow));
            } else {
                return resizeImage(src, (int) (ow * (double) targetH / oh), targetH);
            }
        }

        private BufferedImage resizeImage(BufferedImage src, int w, int h) {
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return dst;
        }

        private void sendJpegResponse(HttpExchange exchange, BufferedImage img, ImageInfo info) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            float quality = (img.getWidth() <= 1000 && img.getHeight() <= 1000) ? 1.0f : 0.96f;
            param.setCompressionQuality(quality);

            try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(mos);
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }

            byte[] jpeg = baos.toByteArray();

            // ---------- 响应头 ----------
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");

            // ETag (基于内容哈希)
            String etag = "\"" + Integer.toHexString(Arrays.hashCode(jpeg)) + "\"";
            exchange.getResponseHeaders().set("ETag", etag);

            // Last-Modified (缓存文件时间)
            if (info.cachedFile() != null && Files.exists(info.cachedFile())) {
                long lastModified = Files.getLastModifiedTime(info.cachedFile()).toMillis();
                exchange.getResponseHeaders().set("Last-Modified", formatHttpDate(lastModified));
            }

            // 缓存命中标记
            exchange.getResponseHeaders().set("X-Cache-Hit", info.cacheHit() ? "true" : "false");

            exchange.sendResponseHeaders(200, jpeg.length);
            exchange.getResponseBody().write(jpeg);
            exchange.close();
        }

        private String formatHttpDate(long millis) {
            return DateTimeFormatter.RFC_1123_DATE_TIME
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(millis));
        }

        // ------------------- 缓存管理 -------------------

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

        private void enforceCacheLimit() throws IOException {
            if (!ENABLE_CACHE) return;

            long total = Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum();

            if (total <= CACHE_MAX_BYTES) return;

            AtomicLong remaining = new AtomicLong(total);

            Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .takeWhile(p -> remaining.get() > CACHE_MAX_BYTES)
                    .forEach(p -> {
                        try {
                            long size = p.toFile().length();
                            Files.delete(p);
                            remaining.addAndGet(-size);
                        } catch (IOException ignored) {}
                    });
        }
    }
}
