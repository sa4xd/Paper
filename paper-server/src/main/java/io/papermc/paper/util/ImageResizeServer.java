package io.papermc.paper.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ImageResizeServer {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    // 新增：限制最大宽高（防止恶意请求导致OOM）
    private static final int MAX_DIMENSION = 4096;
    static {
        HTTP_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("ImageResizeServer started on port " + port +
                (ImageCacheManager.isEnabled() ? " with cache + ETag + 304" : ""));
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
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    try {
                        switch (key) {
                            case "url" -> imageUrl = URLDecoder.decode(value, "UTF-8");
                            case "w" -> {
                                int w = Integer.parseInt(value);
                                // 修复：过滤无效宽度（负数/0/超出最大限制）
                                if (w > 0 && w <= MAX_DIMENSION) {
                                    targetW = w;
                                }
                            }
                            case "h" -> {
                                int h = Integer.parseInt(value);
                                // 修复：过滤无效高度（负数/0/超出最大限制）
                                if (h > 0 && h <= MAX_DIMENSION) {
                                    targetH = h;
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        // 修复：参数格式错误时忽略，不影响整体处理
                        System.err.println("[ImageResize] 无效参数格式: " + key + "=" + value);
                    }
                }
            }

            if (imageUrl == null) {
                sendHtmlHelp(exchange);
                return;
            }

            // 修复：处理 "只指定w" 或 "只指定h" 的场景（原逻辑正确，但需确保参数有效）
            if (targetW == null && targetH == null) {
                handleOriginalImage(exchange, imageUrl);
            } else {
                // 修复：防止null传递给handleResizedImage，用0占位（后续逻辑会处理）
                int w = targetW != null ? targetW : 0;
                int h = targetH != null ? targetH : 0;
                handleResizedImage(exchange, imageUrl, w, h);
            }
        }

        // ================== 原图直传（支持 ETag + Last-Modified + 304）==================
        private void handleOriginalImage(HttpExchange exchange, String imageUrl) throws IOException {
            byte[] data;
            String contentType = guessContentType(imageUrl);
            if (contentType == null) contentType = "application/octet-stream";

            Path cachedFile = ImageCacheManager.isEnabled() ? ImageCacheManager.getCachedImagePath(imageUrl) : null;
            boolean cacheHit = false;

            if (cachedFile != null && Files.exists(cachedFile)) {
                data = Files.readAllBytes(cachedFile);
                ImageCacheManager.touchFile(cachedFile);
                cacheHit = true;
            } else {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    data = in.readAllBytes();
                } catch (Exception e) {
                    System.err.println("[ImageResize] 原图获取失败: " + imageUrl + " - " + e.getMessage());
                    exchange.sendResponseHeaders(502, -1);
                    return;
                }

                if (cachedFile != null) {
                    // 修复：确保父目录存在
                    Files.createDirectories(cachedFile.getParent());
                    Files.write(cachedFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ImageCacheManager.touchFile(cachedFile);
                    ImageCacheManager.enforceCacheLimit();
                }
            }

            sendImageResponse(exchange, data, contentType, cacheHit, cachedFile);
        }

        // ================== 缩放处理（支持 ETag + Last-Modified + 304）==================
        private void handleResizedImage(HttpExchange exchange, String imageUrl, int w, int h) throws IOException {
            // 修复：再次校验宽高（防止恶意参数）
            if ((w <= 0 && h <= 0) || (w > MAX_DIMENSION || h > MAX_DIMENSION)) {
                System.err.println("[ImageResize] 无效缩放尺寸: w=" + w + ", h=" + h);
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            String key = imageUrl + "|" + w + "|" + h;
            String hash = ImageCacheManager.hashUrl(key);
            Path cachedFile = ImageCacheManager.isEnabled() ? ImageCacheManager.getCacheDir().resolve(hash + ".jpg") : null;

            byte[] jpeg;
            boolean cacheHit = false;

            if (cachedFile != null && Files.exists(cachedFile)) {
                jpeg = Files.readAllBytes(cachedFile);
                ImageCacheManager.touchFile(cachedFile);
                cacheHit = true;
            } else {
                BufferedImage original;
                try {
                    original = loadImage(imageUrl);
                    if (original == null) throw new IOException("无效图片（无法解析）");
                    // 修复：原图尺寸为0的异常处理
                    if (original.getWidth() <= 0 || original.getHeight() <= 0) {
                        throw new IOException("图片尺寸无效");
                    }
                } catch (Exception e) {
                    System.err.println("[ImageResize] 加载失败: " + imageUrl + " - " + e.getMessage());
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }

                // 修复：传递正确的原图宽高
                BufferedImage output = resizeImage(original, w, h, original.getWidth(), original.getHeight());
                jpeg = encodeJpeg(output);

                if (cachedFile != null) {
                    // 修复：确保缓存目录存在
                    Files.createDirectories(cachedFile.getParent());
                    Files.write(cachedFile, jpeg, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ImageCacheManager.touchFile(cachedFile);
                    ImageCacheManager.enforceCacheLimit();
                }
            }

            sendImageResponse(exchange, jpeg, "image/jpeg", cacheHit, cachedFile);
        }

        // ================== 统一响应（ETag + 304 + X-Cache-Hit）==================
        private void sendImageResponse(HttpExchange ex, byte[] data, String contentType, boolean cacheHit, Path file) throws IOException {
            // 修复：数据为空时返回404
            if (data == null || data.length == 0) {
                ex.sendResponseHeaders(404, -1);
                return;
            }

            String etag = "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
            String lastModified = file != null ? formatHttpDate(Files.getLastModifiedTime(file).toMillis()) : formatHttpDate(System.currentTimeMillis());

            // 检查客户端缓存
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
                ex.sendResponseHeaders(304, -1);
                return;
            }

            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            } finally {
                ex.close();
            }
        }

        // ================== 工具方法 ==================
        private BufferedImage loadImage(String url) throws Exception {
            if (!ImageCacheManager.isEnabled()) {
                return ImageIO.read(new URL(url));
            }
            Path cached = ImageCacheManager.getCachedImagePath(url);
            if (cached != null && Files.exists(cached)) {
                return ImageIO.read(cached.toFile());
            }
            // 修复：URL连接超时设置（防止挂起）
            URL imageUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
            conn.setConnectTimeout(5000); // 5秒连接超时
            conn.setReadTimeout(10000);   // 10秒读取超时
            try (InputStream in = conn.getInputStream()) {
                BufferedImage img = ImageIO.read(in);
                if (img != null && cached != null) {
                    Files.createDirectories(cached.getParent());
                    ImageIO.write(img, "png", cached.toFile());
                }
                return img;
            } finally {
                conn.disconnect();
            }
        }

        private BufferedImage resizeImage(BufferedImage src, int tw, int th, int ow, int oh) {
            // 修复：只指定w时的等比例计算（原逻辑正确，补充注释）
            if (tw > 0 && th <= 0) {
                // 只指定宽度，高度按比例缩放
                double scale = (double) tw / ow;
                int nh = (int) Math.round(oh * scale);
                return resize(src, tw, nh);
            }
            // 修复：只指定h时的等比例计算（原逻辑正确，补充注释）
            else if (th > 0 && tw <= 0) {
                // 只指定高度，宽度按比例缩放
                double scale = (double) th / oh;
                int nw = (int) Math.round(ow * scale);
                return resize(src, nw, th);
            }
            // 同时指定w和h：按比例缩放后裁剪居中部分
            else {
                double scale = Math.max((double) tw / ow, (double) th / oh);
                int rw = (int) Math.round(ow * scale);
                int rh = (int) Math.round(oh * scale);
                BufferedImage temp = resize(src, rw, rh);
                // 计算裁剪坐标（确保不越界）
                int x = Math.max(0, (rw - tw) / 2);
                int y = Math.max(0, (rh - th) / 2);
                // 修复：裁剪尺寸防止超出临时图片边界
                int cropW = Math.min(tw, rw - x);
                int cropH = Math.min(th, rh - y);
                return temp.getSubimage(x, y, cropW, cropH);
            }
        }

        private BufferedImage resize(BufferedImage src, int w, int h) {
            // 修复：目标尺寸为0的异常处理
            if (w <= 0 || h <= 0) {
                return src;
            }
            // 修复：缩放算法优化（使用双线性插值+抗锯齿）
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 修复：透明图片处理（填充白色背景）
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
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
                System.err.println("[ImageResize] JPEG编码失败: " + e.getMessage());
                // 降级方案：使用默认编码
                try {
                    ImageIO.write(img, "jpeg", baos);
                } catch (IOException ignored) {
                    // 编码彻底失败时返回空数组（后续响应会处理）
                    return new byte[0];
                }
            }
            return baos.toByteArray();
        }

        private void sendHtmlHelp(HttpExchange ex) throws IOException {
            String html = """
                <!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>
                <body><h2>图片缩放服务</h2>
                <p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>
                <p>支持功能：</p>
                <ul>
                    <li>只指定w：按宽度等比例缩放</li>
                    <li>只指定h：按高度等比例缩放</li>
                    <li>同时指定w和h：按比例缩放后居中裁剪</li>
                    <li>不指定w/h：返回原图</li>
                </ul>
                <p>限制：宽高最大不超过%d像素</p>
                <p>示例：</p>
                <ul>
                    <li><a href='/?url=https://example.com/image.jpg&w=300'>只指定宽度（300px）</a></li>
                    <li><a href='/?url=https://example.com/image.jpg&h=200'>只指定高度（200px）</a></li>
                    <li><a href='/?url=https://example.com/image.jpg&w=300&h=200'>指定宽高（裁剪）</a></li>
                </ul>
                </body></html>""".formatted(MAX_DIMENSION);
            byte[] data = html.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            } finally {
                ex.close();
            }
        }

        private String guessContentType(String url) {
            try {
                String path = new URL(url).getPath().toLowerCase();
                if (path.endsWith(".png")) return "image/png";
                if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
                if (path.endsWith(".gif")) return "image/gif";
                if (path.endsWith(".webp")) return "image/webp";
                if (path.endsWith(".svg")) return "image/svg+xml";
                if (path.endsWith(".bmp")) return "image/bmp";
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
                // 修复：考虑时间戳精度问题（允许1秒误差）
                return lm.getTime() - ims.getTime() > 1000;
            } catch (Exception e) {
                return true; // 解析失败，保守返回需要更新
            }
        }
    }
