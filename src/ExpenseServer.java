
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ExpenseServer {
    private static final int PORT = 8080;
    private static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath();
    private static final Path DATA_DIR = Paths.get("data");
    private static final Path DATA_FILE = DATA_DIR.resolve("transactions.txt");
    private static final List<Transaction> transactions = new ArrayList<>();

    static class Transaction {
        String id, type, category, date, note;
        double amount;

        Transaction(String id, String type, double amount,
                    String category, String date, String note) {
            this.id = id;
            this.type = type;
            this.amount = amount;
            this.category = category;
            this.date = date;
            this.note = note;
        }

        String toJson() {
            return "{\"id\":\"" + esc(id) + "\","
                + "\"type\":\"" + esc(type) + "\","
                + "\"amount\":" + amount + ","
                + "\"category\":\"" + esc(category) + "\","
                + "\"date\":\"" + esc(date) + "\","
                + "\"note\":\"" + esc(note) + "\"}";
        }
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        loadTransactions();

       int port = Integer.parseInt(
    System.getenv().getOrDefault("PORT", "8080")
);

HttpServer server = HttpServer.create(
    new InetSocketAddress("0.0.0.0", port), 0
);

        server.createContext("/api/transactions",
            ExpenseServer::handleTransactions);
        server.createContext("/", ExpenseServer::handleFiles);

        server.start();
        System.out.println("Expense Tracker running on port " + PORT);
        System.out.println("Open the forwarded port 8080 in Codespaces.");
    }

    private static synchronized void handleTransactions(
            HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (path.equals("/api/transactions") && method.equals("GET")) {
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < transactions.size(); i++) {
                    if (i > 0) json.append(",");
                    json.append(transactions.get(i).toJson());
                }
                json.append("]");
                send(ex, 200, json.toString(), "application/json");
                return;
            }

            if (path.equals("/api/transactions") && method.equals("POST")) {
                String body = new String(
                    ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

                String type = field(body, "type");
                String category = field(body, "category");
                String date = field(body, "date");
                String note = field(body, "note");
                double amount = Double.parseDouble(field(body, "amount"));

                if (!type.equals("income") && !type.equals("expense")) {
                    send(ex, 400, "{\"error\":\"Invalid type\"}", "application/json");
                    return;
                }
                if (!Double.isFinite(amount) || amount <= 0
                        || category.isBlank() || date.isBlank()) {
                    send(ex, 400, "{\"error\":\"Check your input\"}", "application/json");
                    return;
                }

                Transaction t = new Transaction(
                    UUID.randomUUID().toString(), type, amount,
                    category, date, note);
                transactions.add(t);
                saveTransactions();
                send(ex, 201, t.toJson(), "application/json");
                return;
            }

            if (path.equals("/api/transactions") && method.equals("DELETE")) {
                transactions.clear();
                saveTransactions();
                send(ex, 200, "{\"message\":\"All transactions cleared\"}",
                    "application/json");
                return;
            }

            if (path.startsWith("/api/transactions/")
                    && method.equals("DELETE")) {
                String id = path.substring("/api/transactions/".length());
                boolean removed = transactions.removeIf(t -> t.id.equals(id));
                if (!removed) {
                    send(ex, 404, "{\"error\":\"Transaction not found\"}",
                        "application/json");
                    return;
                }
                saveTransactions();
                send(ex, 200, "{\"message\":\"Transaction deleted\"}",
                    "application/json");
                return;
            }

            send(ex, 405, "{\"error\":\"Method not allowed\"}",
                "application/json");
        } catch (Exception e) {
            send(ex, 400, "{\"error\":\"Invalid request\"}", "application/json");
        } finally {
            ex.close();
        }
    }

    private static void handleFiles(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "Method not allowed", "text/plain");
                return;
            }

            String urlPath = ex.getRequestURI().getPath();
            if (urlPath.equals("/")) urlPath = "/index.html";

            Path file = PUBLIC_DIR.resolve(urlPath.substring(1)).normalize();
            if (!file.startsWith(PUBLIC_DIR) || !Files.isRegularFile(file)) {
                send(ex, 404, "File not found", "text/plain");
                return;
            }

            String name = file.getFileName().toString();
            String contentType = name.endsWith(".html") ? "text/html; charset=UTF-8"
                : name.endsWith(".css") ? "text/css; charset=UTF-8"
                : name.endsWith(".js") ? "application/javascript; charset=UTF-8"
                : "application/octet-stream";

            ex.getResponseHeaders().set("Content-Type", contentType);
            byte[] bytes = Files.readAllBytes(file);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        } finally {
            ex.close();
        }
    }

    private static String field(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key)
            + "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing " + key);
        String value = m.group(1);
        if (value.startsWith("\"")) {
            return unescape(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(n);
                }
            } else out.append(c);
        }
        return out.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static void send(HttpExchange ex, int status, String body,
                             String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void loadTransactions() throws IOException {
        if (!Files.exists(DATA_FILE)) return;
        for (String line : Files.readAllLines(DATA_FILE)) {
            try {
                String[] p = line.split("\\|", -1);
                if (p.length != 6) continue;
                transactions.add(new Transaction(
                    p[0], p[1], Double.parseDouble(p[2]),
                    decode(p[3]), decode(p[4]), decode(p[5])));
            } catch (Exception ignored) {
                // Skip malformed records.
            }
        }
    }

    private static void saveTransactions() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Transaction t : transactions) {
            lines.add(t.id + "|" + t.type + "|" + t.amount + "|"
                + encode(t.category) + "|" + encode(t.date) + "|"
                + encode(t.note));
        }
        Path temp = DATA_DIR.resolve("transactions.tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String encode(String s) {
        return Base64.getUrlEncoder().encodeToString(
            s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String s) {
        return new String(Base64.getUrlDecoder().decode(s),
            StandardCharsets.UTF_8);
    }
}
