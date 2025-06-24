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
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        return response.toString();
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
