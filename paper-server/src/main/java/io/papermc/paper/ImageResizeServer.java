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
        server.createContext("/", new ResizeHandler()); // 👈 根路径监听
        server.setExecutor(null);
        server.start();
        System.out.println("ImageResizeServer started on port " + port);
    }

    static class ResizeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
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

            BufferedImage original;
            if (imageUrl != null) {
                original = ImageIO.read(new URL(imageUrl));
            } else {
                File fallback = new File("z.png"); // 👈 默认图片路径
                if (!fallback.exists()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                original = ImageIO.read(fallback);
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
