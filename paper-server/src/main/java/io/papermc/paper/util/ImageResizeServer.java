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
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final boolean ENABLE_CACHE = Boolean.parseBoolean(dotenv.get("ENABLE_CACHE", "false"));
    private static final Path CACHE_DIR = Paths.get(dotenv.get("CACHE_DIR", "./image_cache"));
    private static final long CACHE_MAX_BYTES = Long.parseLong(dotenv.get("CACHE_MAX_BYTES", "2147483648")); // 2GB

    public static void start(int port) throws IOException {
        if (ENABLE_CACHE && !Files.exists(CACHE_DIR)) Files.createDirectories(CACHE_DIR);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("✅ ImageResizeServer started on port " + port + (ENABLE_CACHE ? " with cache" : ""));
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
                    String[] kv = param.split("=");
                    if (kv.length != 2) continue;
                    switch (kv[0]) {
                        case "url": imageUrl = URLDecoder.decode(kv[1], "UTF-8"); break;
                        case "w": targetW = Integer.parseInt(kv[1]); break;
                        case "h": targetH = Integer.parseInt(kv[1]); break;
                    }
                }
            }

            if (imageUrl == null) {
                byte[] html = ("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>" +
                        "<body><h2>📷 图片缩放服务</h2><p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>" +
                        "<p>支持：只指定一边等比例缩放，指定两边裁剪，不放大，原图返回</p>" +
                        "<p>示例：<a href='/?url=https://example.com/image.jpg&w=300&h=200'>点击查看缩放效果</a></p>" +
                        "</body></html>").getBytes("UTF-8");
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, html.length);
                exchange.getResponseBody().write(html);
                exchange.getResponseBody().close();
                return;
            }

            // 原图直传逻辑（不处理）
            if (targetW == null && targetH == null) {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    byte[] raw = in.readAllBytes();
                    String contentType = Files.probeContentType(Paths.get(new URL(imageUrl).getPath()));
                    if (contentType == null) contentType = "application/octet-stream";
                    exchange.getResponseHeaders().add("Content-Type", contentType);
                    exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000");
                    exchange.sendResponseHeaders(200, raw.length);
                    exchange.getResponseBody().write(raw);
                    exchange.getResponseBody().close();
                    return;
                } catch (Exception e) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }

            BufferedImage original;
            try {
                original = loadImage(imageUrl);
                if (original == null) throw new IOException("Invalid image");
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            int ow = original.getWidth(), oh = original.getHeight();
            BufferedImage output = original;

            boolean shouldResize = false;
            if ((targetW != null && targetW < ow) || (targetH != null && targetH < oh)) {
                shouldResize = true;
            }

            if (shouldResize) {
                double scale;
                int rw, rh;

                if (targetW != null && targetH != null) {
                    double scaleW = (double) targetW / ow;
                    double scaleH = (double) targetH / oh;
                    scale = Math.max(scaleW, scaleH);
                    rw = (int) (ow * scale);
                    rh = (int) (oh * scale);
                    BufferedImage scaled = resize(original, rw, rh);
                    int x = Math.max(0, (rw - targetW) / 2);
                    int y = Math.max(0, (rh - targetH) / 2);
                    output = scaled.getSubimage(x, y, Math.min(targetW, rw), Math.min(targetH, rh));
                } else if (targetW != null) {
                    scale = (double) targetW / ow;
                    rw = targetW;
                    rh = (int) (oh * scale);
                    output = resize(original, rw, rh);
                } else {
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

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
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

        private BufferedImage resize(BufferedImage src, int w, int h) {
            BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return resized;
        }

        private BufferedImage loadImage(String url) throws Exception {
            if (!ENABLE_CACHE) return ImageIO.read(new URL(url));
            String hash = sha256(url);
            Path cachedFile = CACHE_DIR.resolve(hash + ".img");
            if (Files.exists(cachedFile)) return ImageIO.read(cachedFile.toFile());
            BufferedImage img = ImageIO.read(new URL(url));
            if (img != null) {
                ImageIO.write(img, "png", cachedFile.toFile());
                enforceCacheLimit();
            }
            return img;
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

        private void enforceCacheLimit() throws IOException {
            AtomicLong totalSize = new AtomicLong(
                Files.walk(CACHE_DIR)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum()
            );

            if (totalSize.get() <= CACHE_MAX_BYTES) return;

            Files.walk(CACHE_DIR)
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .forEach(p -> {
                    if (totalSize.get() > CACHE_MAX_BYTES) {
                        long size = p.toFile().length();
                        try {
                            Files.delete(p);
                            totalSize.addAndGet(-size);
                        } catch (IOException ignored) {}
                    }
                });
        }
    }
}
