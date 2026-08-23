package com.campusgrid.master;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.InetSocketAddress;

/**
 * Lightweight, native Java HTTP Server to host the CampusGrid UI.
 */
public class DashboardServer {

    public static void main(String[] args) {
        try {
            int port = 8080;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Serve the main UI dashboard
            server.createContext("/", new UIHandler());

            server.setExecutor(null); // Creates a default executor
            server.start();
            System.out.println("--- CampusGrid Master Node ---");
            System.out.println("Dashboard is live! Open your browser to: http://localhost:" + port);
            
        } catch (IOException e) {
            System.err.println("Failed to start Dashboard Server: " + e.getMessage());
        }
    }

    static class UIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response;
            int statusCode = 200;
            
            try {
                // Ensure this path points to your new web directory
                response = Files.readAllBytes(Paths.get("web/index.html"));
                exchange.getResponseHeaders().set("Content-Type", "text/html");
            } catch (IOException e) {
                response = "404 Error: Could not find web/index.html".getBytes();
                statusCode = 404;
            }
            
            exchange.sendResponseHeaders(statusCode, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
}