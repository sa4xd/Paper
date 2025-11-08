package io.papermc.paper.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648")); // 2GB
    private static final int MAX_EDGE = 1500; // 最大边限制
    private static final float JPEG_QUALITY = 1.0f; // 100% 质量

    public static void start(int port) throws IOException {
        if (ENABLE_CACHE && !Files.exists(CACHE_DIR)) {
            Files.createDirectories(CACHE_DIR);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("ImageResizeServer started on port " + port
                + (ENABLE_CACHE ? " with cache" : ""));
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
            Integer targetW = null;
            Integer targetH = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length != 2) continue;
                    try {
                        switch (kv[0]) {
                            case "url":
                                imageUrl = URLDecoder.decode(kv[1], "UTF-8");
                                break;
                            case "w":
                                targetW = Integer.parseInt(kv[1]);
                                break;
                            case "h":
                                targetH = Integer.parseInt(kv[1]);
                                break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // ---------- 首页 ----------
            if (imageUrl == null) {
                String html = """
                        <!DOCTYPE html>
                        <html><head><meta charset='UTF-8'><title>Image Resize Server</title>
                        <style>body{font-family:Arial;margin:40px;}code{background:#f4f4f4;padding:2px 5px;}</style>
                        </head><body>
                        <h2>图片缩放服务 (JPG 输出)</h2>
                        <p>格式：<code>?url=图片地址&amp;w=宽度&amp;h=高度</code></p>
                        <ul>
                            <li>输出统一 <strong>JPEG 100% 质量</strong></li>
                            <li>不放大、最大边 ≤1500px</li>
                            <li>原图直接返回远程数据（不处理）</li>
                            <li>指定两边 → 缩放后居中裁剪</li>
                        </ul>
                        <p>示例：<a href='/?url=https://httpbin.org/image/jpeg&w=300&h=200'>查看</a></p>
                        </body></html>
                        """;
                byte[] resp = html.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
                exchange.getResponseBody().close();
                return;
            }

            // ---------- 参数校验：最大边限制 ----------
            if (targetW != null && targetW > MAX_EDGE) targetW = null;
            if (targetH != null && targetH > MAX_EDGE) targetH = null;

            // ---------- 缓存 Key ----------
            String hash = sha256(imageUrl + "|" + targetW + "|" + targetH);
            Path cachedFile = CACHE_DIR.resolve(hash + ".jpg");

            // ---------- 客户端缓存 (ETag) ----------
            String etag = "\"" + hash + "\"";
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ENABLE_CACHE && Files.exists(cachedFile) && etag.equals(ifNoneMatch)) {
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
                exchange.sendResponseHeaders(304, -1);
                return;
            }

            // ---------- 直接返回原图（不处理） ----------
            if (targetW == null && targetH == null) {
                proxyOriginalImage(exchange, imageUrl, etag, cachedFile);
                return;
            }

            // ---------- 加载 + 缩放 ----------
            BufferedImage original;
            try {
                original = loadImage(imageUrl);
                if (original == null) throw new IOException("Invalid image");
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            int ow = original.getWidth();
            int oh = original.getHeight();
            BufferedImage output = original;

            if (targetW != null && targetH != null) {
                // 两边指定 → 缩放后居中裁剪
                double scaleW = (double) targetW / ow;
                double scaleH = (double) targetH / oh;
                double scale = Math.max(scaleW, scaleH);

                if (scale < 1.0) {
                    int rw = (int) (ow * scale);
                    int rh = (int) (oh * scale);
                    BufferedImage scaled = resize(original, rw, rh);
                    int x = Math.max(0, (rw - targetW) / 2);
                    int y = Math.max(0, (rh - targetH) / 2);
                    output = scaled.getSubimage(x, y,
                            Math.min(targetW, rw), Math.min(targetH, rh));
                }
                // else: 不放大
            } else if (targetW != null && ow > targetW) {
                double scale = (double) targetW / ow;
                int rw = targetW;
                int rh = (int) (oh * scale);
                output = resize(original, rw, rh);
            } else if (targetH != null && oh > targetH) {
                double scale = (double) targetH / oh;
                int rh = targetH;
                int rw = (int) (ow * scale);
                output = resize(original, rw, rh);
            }
            // else: 原图返回

            // ---------- 输出 JPEG 100% 质量 ----------
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            exchange.getResponseHeaders().set("X-Cache-Hit", ENABLE_CACHE && Files.exists(cachedFile) ? "true" : "false");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeJpeg(output, baos, JPEG_QUALITY);
            byte[] bytes = baos.toByteArray();

            // 缓存处理
            if (ENABLE_CACHE && !Files.exists(cachedFile)) {
                try {
                    Files.write(cachedFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    enforceCacheLimit();
                } catch (Exception ignored) {}
            }

            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        // ------------------- 原图直通代理 -------------------
        private void proxyOriginalImage(HttpExchange exchange, String urlStr, String etag, Path cachedFile) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "ImageResizeServer/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            String contentType = conn.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            exchange.getResponseHeaders().set("X-Cache-Hit", "false");

            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                byte[] data = baos.toByteArray();

                // 缓存原图（仅缓存字节流）
                if (ENABLE_CACHE && !Files.exists(cachedFile)) {
                    Files.write(cachedFile, data, StandardOpenOption.CREATE);
                    enforceCacheLimit();
                }

                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            }
        }

        // ------------------- JPEG 100% 写入 -------------------
        private void writeJpeg(BufferedImage img, OutputStream out, float quality) throws IOException {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
        }

        // ------------------- 工具方法 -------------------
        private BufferedImage resize(BufferedImage src, int w, int h) {
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return dst;
        }

        private BufferedImage loadImage(String urlStr) throws Exception {
            if (!ENABLE_CACHE) {
                return ImageIO.read(new URL(urlStr));
            }

            String hash = sha256(urlStr);
            Path cachedFile = CACHE_DIR.resolve(hash + ".raw");

            if (Files.exists(cachedFile)) {
                try (InputStream in = Files.newInputStream(cachedFile)) {
                    return ImageIO.read(in);
                }
            }

            BufferedImage img = ImageIO.read(new URL(urlStr));
            if (img != null && ENABLE_CACHE) {
                try (OutputStream out = Files.newOutputStream(cachedFile)) {
                    ImageIO.write(img, "png", out); // 缓存原始 PNG 避免 JPEG 失真
                }
                enforceCacheLimit();
            }
            return img;
        }

        private String sha256(String input) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        }

        private void enforceCacheLimit() throws IOException {
            AtomicLong total = new AtomicLong(
                    Files.walk(CACHE_DIR)
                            .filter(Files::isRegularFile)
                            .mapToLong(p -> p.toFile().length())
                            .sum()
            );

            if (total.get() <= CACHE_MAX_BYTES) return;

            Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .forEach(p -> {
                        if (total.get() > CACHE_MAX_BYTES) {
                            long size = p.toFile().length();
                            try {
                                Files.delete(p);
                                total.addAndGet(-size);
                            } catch (IOException ignored) {}
                        }
                    });
        }
    }
}
