package twitch.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.net.ssl.SSLException;

/* Twitch API */
public class TwitchApiService {
    private final String clientId;
    private final String oauthToken;
    private final Logger logger;

    private static final long RATE_LIMIT_LOG_THROTTLE_MS = 60_000L;
    private static volatile long lastRateLimitLogTimeMs = 0L;

    private static final long REQUEST_LOG_THROTTLE_MS = 60_000L;
    private static final Map<String, Long> lastRequestLogTimeMs = new ConcurrentHashMap<>();

    private static final long RATE_LIMIT_SAFETY_BUFFER_MS = 2_000L;
    private static volatile long rateLimitRetryAfterMs = 0L;

    private static volatile String lastRateLimitLimit = "";
    private static volatile String lastRateLimitRemaining = "";
    private static volatile String lastRateLimitReset = "";

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

    private static long parseResetEpochSecondsToMs(String resetHeader) {
        if (resetHeader == null || resetHeader.isEmpty()) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(resetHeader.trim());
            if (seconds <= 0L) {
                return 0L;
            }
            return seconds * 1000L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String buildRateLimitJson(String limit, String remaining, String reset) {
        return "{\"error\": \"rate_limit\", \"code\": 429," +
                " \"ratelimit_limit\": \"" + (limit == null ? "" : limit) + "\"," +
                " \"ratelimit_remaining\": \"" + (remaining == null ? "" : remaining) + "\"," +
                " \"ratelimit_reset\": \"" + (reset == null ? "" : reset) + "\"," +
                " \"message\": \"Превышен лимит запросов к Twitch API. Попробуйте позже.\"}";
    }

    private static void rememberRateLimitHeaders(String limit, String remaining, String reset) {
        if (limit != null) {
            lastRateLimitLimit = limit;
        }
        if (remaining != null) {
            lastRateLimitRemaining = remaining;
        }
        if (reset != null) {
            lastRateLimitReset = reset;
        }
    }

    public String getLastRateLimitLimit() {
        return lastRateLimitLimit;
    }

    public String getLastRateLimitRemaining() {
        return lastRateLimitRemaining;
    }

    public String getLastRateLimitReset() {
        return lastRateLimitReset;
    }

    public String sendGetRequest(String endpoint) throws Exception {
        long nowBeforeRequest = System.currentTimeMillis();
        long retryAfter = rateLimitRetryAfterMs;
        if (retryAfter > 0L && nowBeforeRequest < retryAfter) {
            long remainingMs = retryAfter - nowBeforeRequest;
            if (remainingMs < 0L) {
                remainingMs = 0L;
            }

            Long last = lastRequestLogTimeMs.get(endpoint);
            if (last == null || nowBeforeRequest - last > REQUEST_LOG_THROTTLE_MS) {
                lastRequestLogTimeMs.put(endpoint, nowBeforeRequest);
                logger.info("[TWITCH API] Запрос пропущен из-за rate_limit backoff: endpoint=" + endpoint +
                        " retry_in_ms=" + remainingMs +
                        " last_remaining=" + (lastRateLimitRemaining == null ? "" : lastRateLimitRemaining) +
                        " last_limit=" + (lastRateLimitLimit == null ? "" : lastRateLimitLimit) +
                        (lastRateLimitReset != null && !lastRateLimitReset.isEmpty() ? (" last_reset=" + lastRateLimitReset) : ""));
            }

            return buildRateLimitJson("", "0", String.valueOf(retryAfter / 1000L));
        }

        try {
            URL url = new URL(endpoint);

            Long last = lastRequestLogTimeMs.get(endpoint);
            if (last == null || nowBeforeRequest - last > REQUEST_LOG_THROTTLE_MS) {
                lastRequestLogTimeMs.put(endpoint, nowBeforeRequest);
                logger.info("[TWITCH API] Реальный HTTP запрос: endpoint=" + endpoint);
            }

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Client-Id", clientId);
            conn.setRequestProperty("Authorization", "Bearer " + oauthToken);

            int responseCode = conn.getResponseCode();

            String limitHdr = conn.getHeaderField("Ratelimit-Limit");
            String remainingHdr = conn.getHeaderField("Ratelimit-Remaining");
            String resetHdr = conn.getHeaderField("Ratelimit-Reset");
            rememberRateLimitHeaders(limitHdr, remainingHdr, resetHdr);

            if (responseCode == 429) {
                long resetAtMs = parseResetEpochSecondsToMs(resetHdr);
                if (resetAtMs > 0L) {
                    rateLimitRetryAfterMs = resetAtMs + RATE_LIMIT_SAFETY_BUFFER_MS;
                } else {
                    rateLimitRetryAfterMs = System.currentTimeMillis() + 30_000L;
                }

                long now = System.currentTimeMillis();
                if (now - lastRateLimitLogTimeMs > RATE_LIMIT_LOG_THROTTLE_MS) {
                    lastRateLimitLogTimeMs = now;
                    String details = "limit=" + (limitHdr == null ? "?" : limitHdr) +
                            " remaining=" + (remainingHdr == null ? "?" : remainingHdr) +
                            " reset=" + (resetHdr == null ? "?" : resetHdr);
                    logger.warning("[TWITCH API] Превышен лимит запросов к Twitch API (429 Too Many Requests). " + details);
                }
                return buildRateLimitJson(limitHdr, remainingHdr, resetHdr);
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
