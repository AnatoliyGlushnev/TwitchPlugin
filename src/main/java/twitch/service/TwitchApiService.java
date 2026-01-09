package twitch.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.net.ssl.SSLException;

/* Twitch API */
public class TwitchApiService {
    private final String clientId;
    private final String oauthToken;
    private final Logger logger;

    public TwitchApiService(String clientId, String oauthToken, Logger logger) {
        this.clientId = clientId;
        this.oauthToken = oauthToken;
        this.logger = logger;
    }

    private static boolean isApiTwitchTvHost(String host) {
        if (host == null) {
            return false;
        }
        return "api.twitch.tv".equalsIgnoreCase(host);
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String buildCauseChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            String msg = cur.getMessage();
            sb.append(cur.getClass().getName());
            if (msg != null && !msg.isEmpty()) {
                sb.append(": ").append(msg);
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static String classifyNetworkProblem(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = safeLower(cur.getMessage());

            if (cur instanceof UnknownHostException) {
                return "dns_unknown_host";
            }
            if (cur instanceof SocketTimeoutException || msg.contains("timed out")) {
                return "timeout";
            }
            if (cur instanceof ConnectException || msg.contains("connection refused")) {
                return "connect_refused";
            }
            if (cur instanceof SSLException) {
                return "ssl_error";
            }
            if (cur instanceof SocketException) {
                if (msg.contains("connection reset")) {
                    return "socket_connection_reset";
                }
                if (msg.contains("broken pipe")) {
                    return "socket_broken_pipe";
                }
                return "socket_error";
            }
            if (msg.contains("no route to host")) {
                return "no_route_to_host";
            }
            if (msg.contains("network is unreachable")) {
                return "network_unreachable";
            }

            cur = cur.getCause();
        }
        return "unknown";
    }

    private void logApiTwitchTvConnectionProblem(String endpoint, Throwable e) {
        String host = null;
        try {
            host = new URL(endpoint).getHost();
        } catch (Exception ignored) {
        }

        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String problem = classifyNetworkProblem(e);
        String chain = buildCauseChain(e);
        String rootMsg = root.getMessage() != null ? root.getMessage() : root.toString();

        String logLine = "[TWITCH API] Ошибка соединения" +
                " host=" + (host == null ? "?" : host) +
                " endpoint=" + endpoint +
                " type=" + problem +
                " root=\"" + rootMsg.replace("\"", "'") + "\"" +
                " causes=" + chain;

        Level level;
        if (!isApiTwitchTvHost(host)) {
            level = Level.WARNING;
        } else if ("dns_unknown_host".equals(problem) || "timeout".equals(problem) || "connect_refused".equals(problem)) {
            level = Level.INFO;
        } else {
            level = Level.WARNING;
        }

        logger.log(level, logLine, e);
    }

    public String sendGetRequest(String endpoint) throws Exception {
        try {
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Client-Id", clientId);
            conn.setRequestProperty("Authorization", "Bearer " + oauthToken);

            int responseCode = conn.getResponseCode();
            if (responseCode == 429) {
                logger.warning("[TWITCH API] Превышен лимит запросов к Twitch API (429 Too Many Requests). Попробуйте позже.");
                return "{\"error\": \"rate_limit\", \"message\": \"Превышен лимит запросов к Twitch API. Попробуйте позже.\"}";
            } else if (responseCode == 401 || responseCode == 403) {
                // Ошибка при авторизации API
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = err.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    logger.warning("[TWITCH API] Ошибка авторизации (" + responseCode + "): " + errorResponse);
                    return "{\"error\": \"auth_error\", \"code\": " + responseCode + ", \"message\": \"" + errorResponse + "\"}";
                }
            } else if (responseCode >= 400) {
                // Другие возможные ошибки API
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = err.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    logger.warning("[TWITCH API] Ошибка " + responseCode + ": " + errorResponse);
                    return "{\"error\": \"http_error\", \"code\": " + responseCode + ", \"message\": \"" + errorResponse + "\"}";
                }
            }
            // успешный поток
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                return response.toString();
            }
        } catch (Exception e) {
            logApiTwitchTvConnectionProblem(endpoint, e);

            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String msg = root.getMessage() != null ? root.getMessage() : root.toString();
            return "{\"error\": \"exception\", \"message\": \"" + msg.replace("\"", "'") + "\"}";
        }
    }

    public void validateConnection() {
        String endpoint = "https://api.twitch.tv/helix/users";
        try {
            String response = sendGetRequest(endpoint);
            logger.info("[TWITCH API][DEBUG] Ответ от Twitch: " + response);
            if (response.contains("id") && response.contains("login")) {
                logger.info("[TWITCH API] Подключение к Twitch API успешно!");
            } else {
                logger.warning("[TWITCH API] Не удалось получить валидный ответ от Twitch API. Проверьте токен и client_id.");
            }
        } catch (Exception e) {
            logApiTwitchTvConnectionProblem(endpoint, e);
        }
    }
}
