package twitch.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

/* Twitch API */
public class TwitchApiService {
    private final String clientId;
    private final String oauthToken;
    private final Logger logger;

    private static final long RATE_LIMIT_LOG_THROTTLE_MS = 60_000L;
    private static volatile long lastRateLimitLogTimeMs = 0L;

    public TwitchApiService(String clientId, String oauthToken, Logger logger) {
        this.clientId = clientId;
        this.oauthToken = oauthToken;
        this.logger = logger;
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
                String limit = conn.getHeaderField("Ratelimit-Limit");
                String remaining = conn.getHeaderField("Ratelimit-Remaining");
                String reset = conn.getHeaderField("Ratelimit-Reset");

                long now = System.currentTimeMillis();
                if (now - lastRateLimitLogTimeMs > RATE_LIMIT_LOG_THROTTLE_MS) {
                    lastRateLimitLogTimeMs = now;
                    String details = "limit=" + (limit == null ? "?" : limit) +
                            " remaining=" + (remaining == null ? "?" : remaining) +
                            " reset=" + (reset == null ? "?" : reset);
                    logger.warning("[TWITCH API] Превышен лимит запросов к Twitch API (429 Too Many Requests). " + details);
                }

                return "{\"error\": \"rate_limit\", \"code\": 429," +
                        " \"ratelimit_limit\": \"" + (limit == null ? "" : limit) + "\"," +
                        " \"ratelimit_remaining\": \"" + (remaining == null ? "" : remaining) + "\"," +
                        " \"ratelimit_reset\": \"" + (reset == null ? "" : reset) + "\"," +
                        " \"message\": \"Превышен лимит запросов к Twitch API. Попробуйте позже.\"}";
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
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        if (cause instanceof java.net.ConnectException ||
            cause instanceof java.net.UnknownHostException ||
            cause instanceof java.net.SocketTimeoutException ||
            cause instanceof javax.net.ssl.SSLException ||
            msg.toLowerCase().contains("timed out") ||
            msg.toLowerCase().contains("connection refused")) {
            logger.info("[TWITCH API] Временная ошибка подключения: " + msg);
        } else {
            logger.warning("[TWITCH API] Ошибка подключения к Twitch API: " + msg);
        }
        return "{\"error\": \"exception\", \"message\": \"" + msg.replace("\"", "'") + "\"}";
    }
}
    public void validateConnection() {
        try {
            String response = sendGetRequest("https://api.twitch.tv/helix/users");
            logger.info("[TWITCH API][DEBUG] Ответ от Twitch: " + response);
            if (response.contains("id") && response.contains("login")) {
                logger.info("[TWITCH API] Подключение к Twitch API успешно!");
            } else {
                logger.warning("[TWITCH API] Не удалось получить валидный ответ от Twitch API. Проверьте токен и client_id.");
            }
        } catch (Exception e) {
            logger.warning("[TWITCH API][DEBUG] Ошибка: " + e);
            logger.warning("[TWITCH API] Ошибка подключения к Twitch API: " + e.getMessage());
        }
    }
}
