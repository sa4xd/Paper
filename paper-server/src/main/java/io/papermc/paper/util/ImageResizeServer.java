package io.papermc.paper.util;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;

public class ImageResizeServer {

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ResizeHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("✅ ImageResizeServer started on port " + port);
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
            int width = 100;
            int height = 100;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length != 2) continue;
                    switch (kv[0]) {
                        case "url": imageUrl = URLDecoder.decode(kv[1], "UTF-8"); break;
                        case "w": width = Integer.parseInt(kv[1]); break;
                        case "h": height = Integer.parseInt(kv[1]); break;
                    }
                }
            }

            if (imageUrl == null) {
                String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Image Resize Server</title></head>" +
                        "<body><h2>📷 图片缩放服务</h2>" +
                        "<p>使用格式：<code>?url=图片地址&w=宽度&h=高度</code></p>" +
                        "<p>示例：<a href='/?url=https://example.com/image.jpg&w=300&h=200'>点击查看缩放效果</a></p>" +
                        "</body></html>";
                byte[] response = html.getBytes("UTF-8");
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
                return;
            }

            BufferedImage original;
            try {
                original = ImageIO.read(new URL(imageUrl));
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, width, height, null);
            g.dispose();

            exchange.getResponseHeaders().add("Content-Type", "image/png");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", baos);
            byte[] bytes = baos.toByteArray();

            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }
}
