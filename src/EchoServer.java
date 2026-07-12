import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Map.entry;

public class EchoServer {
    static Map<String, String> pathResponses = Map.ofEntries(
            entry("/", "Hello world!"),
            entry("/hello", "hey!"),
            entry("/slow", "this takes 10s!"),
            entry("/echo", "")
    );

    static Map<String, String[]> allowedMethods = Map.ofEntries(
            entry("/", new String[]{"GET"}),
            entry("/hello", new String[]{"GET", "POST"}),
            entry("/slow", new String[]{"GET"}),
            entry("/echo", new String[]{"POST"})
    );

    static Map<String, Integer> statusCodes = Map.ofEntries(
            entry("GET", 200),
            entry("POST", 201)
    );

    static Map<Integer, String> statusTexts = Map.ofEntries(
            entry(200, "OK"),
            entry(201, "CREATED"),
            entry(405, "Method Not Allowed"),
            entry(404, "Not Found"),
            entry(403, "Forbidden")
    );

    static Map<String, String> contentTypes = Map.ofEntries(
            entry("html", "text/html"),
            entry("css", "text/css"),
            entry("js", "application/javascript"),
            entry("png", "image/png"),
            entry("jpg", "image/jpeg"),
            entry("gif", "image/gif"),
            entry("txt", "text/plain"),
            entry("json", "application/json")
    );

    static List<Integer> successCodes = Arrays.asList(200, 201);

    static final Path WEB_ROOT = Paths.get("www").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {

        try(ServerSocket server = new ServerSocket(8080)) {
            ExecutorService pool = Executors.newFixedThreadPool(200);

            while(true) {
                Socket socket = server.accept();
                pool.submit(() -> {
                    try(socket) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        OutputStream out = socket.getOutputStream();

                        String line;
                        boolean close = false;

                        while(true) {
                            socket.setSoTimeout(1000 * 5);
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

                            int contentLength = 0;
                            System.out.println("Reading headers: ");
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) {
                                    break;
                                }
                                if(line.equals("Connection: close")) {
                                    close = true;
                                } else if(line.startsWith("Content-Length")) {
                                    String[] contentLengthLine= line.split(" ");
                                    contentLength = Integer.parseInt(contentLengthLine[1]);
                                }

                                System.out.println(line);
                            }

                            char[] charBody = new char[contentLength];
                            int count = 0;
                            System.out.println("Reading body");
                            while(count < contentLength) {
                                int n = reader.read(charBody, count, contentLength - count);
                                if(n == -1) break;
                                count += n;
                            }
                            String body = new String(charBody);
                            System.out.println(body);

                            int status;
                            if (pathResponses.containsKey(path) && Arrays.asList(allowedMethods.get(path)).contains(method)) {
                                status = statusCodes.get(method);
                            } else if (pathResponses.containsKey(path)) {
                                status = 405;
                            } else {
                                status = 404;
                            }


                            Path resourcePath = null;
                            String contentType = "";
                            byte[] responseBytes = null;
                            if(path.contains(".")) {
                                status = 200;
                                contentType = contentTypes.getOrDefault(path.substring(path.lastIndexOf(".") + 1), "application/octet-stream");
                                resourcePath = WEB_ROOT.resolve(path.substring(1)).normalize();

                                if(!resourcePath.startsWith(WEB_ROOT)) {
                                    status = 403;
                                    responseBytes = "Forbidden".getBytes(StandardCharsets.UTF_8);
                                } else if(!(Files.exists(resourcePath) && Files.isRegularFile(resourcePath))) {
                                    status = 404;
                                    responseBytes = "Not Found".getBytes(StandardCharsets.UTF_8);
                                } else {
                                    responseBytes = Files.readAllBytes(resourcePath);
                                }
                            }

                            if(responseBytes == null) {
                                String response = successCodes.contains(status) ? pathResponses.get(path) : "no content";
                                responseBytes = response.getBytes(StandardCharsets.UTF_8);

                                if (path.equals("/slow")) {
                                    Thread.sleep(1000 * 2);
                                } else if (path.equals("/echo")) {
                                    responseBytes = body.getBytes(StandardCharsets.UTF_8);
                                }
                            }
                            String statusText = statusTexts.get(status);

                            sendResponse(out, contentType, status, statusText, close, responseBytes);
                            if(close) {
                                break;
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("Idle timeout - closing connection");
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

    public static void sendResponse(OutputStream out, String contentType, int status, String statusText, boolean close, byte[] bodyBytes) throws IOException {

        System.out.print("\nReturning response: ");

        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n";
        if(close) headers += "Connection: close\r\n";
        headers += "Content-Type: " + contentType + "\r\n" +
                   "Content-Length: " + bodyBytes.length + "\r\n" + "\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();

    }
}
