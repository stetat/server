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

public class HttpServer {
    static final Path WEB_ROOT = Mappings.getWebRoot();

    public static void main(String[] args) throws IOException {

        // initiating server to listen on port 8080
        try(ServerSocket server = new ServerSocket(8080)) {
            // creating executor that spawns a new virtual thread for every task
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

            // a loop so server won't stop after 1 closed connection
            while(true) {
                // wait for a new connection
                Socket socket = server.accept();

                // submit a runnable to the thread pool
                pool.submit(() -> {
                    // try/catch for socket so server won't break after 1 failed request
                    try(socket) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        OutputStream out = socket.getOutputStream();

                        String line;
                        boolean close = false;

                        // a while loop so connection won't close after 1 request
                        // read entire request
                        while(true) {
                            socket.setSoTimeout(1000 * 5);
                            System.out.println("Reading request: ");
                            String requestLine = reader.readLine();
                            if(requestLine == null) {
                                break;
                            }

                            // parsing request data
                            String[] request = requestLine.split(" ");
                            String method = request[0];
                            String path = request[1];
                            String version = request[2];
                            System.out.println("Method: " + method + "\n" + "Path: " + path + "\n" + "Version: " + version + "\n");

                            // reading headers
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

                            // reading body
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

                            // checking if it's a valid path
                            int status;
                            if (Mappings.isValidPath(path) && Mappings.isAllowedMethod(path, method)) {
                                status = Mappings.getStatusCode(method);
                            } else if (Mappings.isValidPath(path)) {
                                status = 405;
                            } else {
                                status = 404;
                            }


                            Path resourcePath = null;
                            String contentType = "";
                            byte[] responseBytes = null;

                            // checking if request wants file
                            if(path.contains(".")) {
                                status = 200;
                                contentType = Mappings.getContentType(path.substring(path.lastIndexOf(".") + 1));

                                // protection from path traversal
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

                            // if not a file, preparing default response
                            if(responseBytes == null) {
                                String response = Mappings.isSuccessCode(status) ? Mappings.getPathResponse(path) : "no content";
                                responseBytes = response.getBytes(StandardCharsets.UTF_8);

                                if (path.equals("/slow")) {
                                    Thread.sleep(1000 * 3);
                                } else if (path.equals("/echo")) {
                                    responseBytes = body.getBytes(StandardCharsets.UTF_8);
                                }
                            }
                            String statusText = Mappings.getStatusText(status);

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

    // writing response back to output stream
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
