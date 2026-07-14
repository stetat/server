import java.lang.reflect.Array;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

public class Mappings {
    private static Map<String, String> pathResponses = Map.ofEntries(
            entry("/", "Hello world!"),
            entry("/hello", "hey!"),
            entry("/slow", "this takes 10s!"),
            entry("/echo", "")
    );

    private static Map<String, String[]> allowedMethods = Map.ofEntries(
            entry("/", new String[]{"GET"}),
            entry("/hello", new String[]{"GET", "POST"}),
            entry("/slow", new String[]{"GET"}),
            entry("/echo", new String[]{"POST"})
    );

    private static Map<String, Integer> statusCodes = Map.ofEntries(
            entry("GET", 200),
            entry("POST", 201)
    );

    private static Map<Integer, String> statusTexts = Map.ofEntries(
            entry(200, "OK"),
            entry(201, "CREATED"),
            entry(405, "Method Not Allowed"),
            entry(404, "Not Found"),
            entry(403, "Forbidden")
    );

    private static Map<String, String> contentTypes = Map.ofEntries(
            entry("html", "text/html"),
            entry("css", "text/css"),
            entry("js", "application/javascript"),
            entry("png", "image/png"),
            entry("jpg", "image/jpeg"),
            entry("gif", "image/gif"),
            entry("txt", "text/plain"),
            entry("json", "application/json")
    );

    private static List<Integer> successCodes = Arrays.asList(200, 201);

    private static final Path WEB_ROOT = Paths.get("www").toAbsolutePath().normalize();

    public static boolean isValidPath(String path) {
        return pathResponses.containsKey(path);
    }
    public static String getPathResponse(String path) {
        return pathResponses.getOrDefault(path, "");
    }

    public static boolean isAllowedMethod(String path, String method) {
        return Arrays.asList(allowedMethods.get(path)).contains(method);
    }

    public static int getStatusCode(String method) {
        return statusCodes.get(method);
    }

    public static String getStatusText(int status) {
        return statusTexts.get(status);
    }

    public static String getContentType(String fileType) {
        return contentTypes.getOrDefault(fileType, "application/octet-stream");
    }

    public static boolean isSuccessCode(int code) {
        return successCodes.contains(code);
    }

    public static Path getWebRoot() {
        return WEB_ROOT;
    }
}
