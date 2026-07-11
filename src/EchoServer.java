import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Map.entry;

public class EchoServer {
    static Map<String, String> pathResponses = Map.ofEntries(
            entry("/", "Hello world!"),
            entry("/hello", "hey!"),
            entry("/slow", "this takes 10s!")
    );

    static Map<String, String[]> allowedMethods = Map.ofEntries(
            entry("/", new String[]{"GET"}),
            entry("/hello", new String[]{"GET", "POST"}),
            entry("/slow", new String[]{"GET"})
    );

    static Map<String, Integer> statusCodes = Map.ofEntries(
            entry("GET", 200),
            entry("POST", 201)
    );

    static Map<Integer, String> statusTexts = Map.ofEntries(
            entry(200, "OK"),
            entry(201, "CREATED"),
            entry(405, "Method Not Allowed"),
            entry(404, "Not Found")
    );

    static List<Integer> successCodes = Arrays.asList(200, 201);

    public static void main(String[] args) throws IOException {

        try(ServerSocket server = new ServerSocket(8080)) {
            ExecutorService pool = Executors.newFixedThreadPool(200);

            while(true) {
                Socket socket = server.accept();
                pool.submit(() -> {
                    try(socket) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                        String line;
                        boolean close = false;

                        while(true) {
                            System.out.println("Reading request: ");
                            String requestLine = reader.readLine();
                            if(requestLine == null) {
                                break;
                            }
                            String[] request = requestLine.split(" ");
                            String method = request[0];
                            String path = request[1];
                            String version = request[2];
                            System.out.println("Method: " + method + "\n" + "Path: " + path + "\n" + "Version: " + version + "\n");

                            System.out.println("Reading headers: ");
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) {
                                    break;
                                }
                                if(line.equals("Connection: close")) {
                                    close = true;
                                }

                                System.out.println(line);
                            }

                            int status;
                            if (pathResponses.containsKey(path) && Arrays.asList(allowedMethods.get(path)).contains(method)) {
                                status = statusCodes.get(method);
                            } else if (pathResponses.containsKey(path)) {
                                status = 405;
                            } else {
                                status = 404;
                            }

                            String statusText = statusTexts.get(status);
                            String response = successCodes.contains(status) ? pathResponses.get(path) : "no content";

                            if (path.equals("/slow")) {
                                Thread.sleep(1000 * 2);
                            }
                            sendResponse(writer, status, statusText, response, close);
                            if(close) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("Request failed: " + e);
                    }
                });
            }
        }
    }

    public static int getContentLength(String body) {
        return body.getBytes(StandardCharsets.UTF_8).length;
    }

    public static void sendResponse(PrintWriter writer, int status, String statusText, String body, boolean close) {

        String responseBody = body + "\r\n";

        System.out.print("\nReturning response: ");
        writer.print("HTTP/1.1 " + status + " " + statusText + "\r\n");
        if(close) {
            writer.print("Connection: close\r\n");
        }
        writer.print("Content-Type: text/plain\r\n");
        writer.print("Content-Length: " + getContentLength(responseBody) + "\r\n");
        writer.print("\r\n");
        writer.print(responseBody);
        writer.flush();

    }
}
