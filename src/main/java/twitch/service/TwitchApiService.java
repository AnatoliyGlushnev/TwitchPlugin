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

    public TwitchApiService(String clientId, String oauthToken, Logger logger) {
        this.clientId = clientId;
        this.oauthToken = oauthToken;
        this.logger = logger;
    }

    public String sendGetRequest(String endpoint) throws Exception {
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
