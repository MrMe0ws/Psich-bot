package com.psich.bot.integrations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.psich.bot.PsichBot;
import com.psich.bot.utils.HttpClientFactory;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * Интеграция с Discord через Webhook
 */
public class DiscordWebhookIntegration {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static OkHttpClient httpClient;

    /**
     * Инициализирует HTTP клиент для отправки запросов
     */
    public static void initialize(PsichBot plugin) {
        httpClient = HttpClientFactory.createClient(plugin.getConfigManager());
        plugin.getLogger().info("Discord Webhook интеграция инициализирована!");
    }

    /**
     * Отправляет сообщение в Discord через Webhook
     * 
     * @param webhookUrl URL вебхука Discord
     * @param message    Сообщение для отправки (уже без цветовых кодов Minecraft)
     * @param username   Имя отправителя (опционально, null для использования имени
     *                   вебхука)
     * @param avatarUrl  URL аватарки (опционально, null для использования аватарки
     *                   вебхука)
     * @return true если сообщение отправлено успешно
     */
    public static boolean sendMessage(String webhookUrl, String message, String username, String avatarUrl) {
        if (httpClient == null) {
            PsichBot.getInstance().getLogger().warning("Discord Webhook не инициализирован");
            return false;
        }

        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return false;
        }

        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        try {
            // Создаем JSON payload
            JsonObject json = new JsonObject();
            json.addProperty("content", message);
            if (username != null && !username.trim().isEmpty()) {
                json.addProperty("username", username);
            }
            if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
                json.addProperty("avatar_url", avatarUrl);
            }

            // Создаем HTTP запрос
            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();

            // Отправляем запрос с обработкой rate limiting
            int maxRetries = 3;
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        if (PsichBot.getInstance().getConfigManager().isDebug()) {
                            PsichBot.getInstance().getLogger()
                                    .info("[DEBUG] Сообщение отправлено в Discord: " + message);
                        }
                        return true;
                    } else if (response.code() == 429) {
                        // Rate limit - парсим retry_after
                        String responseBody = "{}";
                        if (response.body() != null) {
                            try {
                                responseBody = response.body().string();
                            } catch (IOException e) {
                                // Игнорируем ошибку чтения
                            }
                        }

                        try {
                            JsonObject errorJson = JsonParser.parseString(responseBody).getAsJsonObject();
                            double retryAfter = errorJson.has("retry_after")
                                    ? errorJson.get("retry_after").getAsDouble()
                                    : 1.0; // Дефолт 1 секунда

                            if (attempt < maxRetries - 1) {
                                long waitMs = (long) (retryAfter * 1000) + 100; // +100мс запас
                                if (PsichBot.getInstance().getConfigManager().isDebug()) {
                                    PsichBot.getInstance().getLogger()
                                            .info("[DEBUG] Discord rate limit, ждем " + waitMs + "мс");
                                }
                                Thread.sleep(waitMs);
                                continue; // Повторяем попытку
                            } else {
                                PsichBot.getInstance().getLogger()
                                        .warning("Discord rate limit после " + maxRetries + " попыток");
                                return false;
                            }
                        } catch (Exception e) {
                            // Если не удалось распарсить, ждем 1 секунду
                            if (attempt < maxRetries - 1) {
                                try {
                                    Thread.sleep(1000);
                                    continue;
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return false;
                                }
                            }
                        }
                    } else {
                        PsichBot.getInstance().getLogger()
                                .warning("Ошибка отправки сообщения в Discord: HTTP " + response.code());
                        if (PsichBot.getInstance().getConfigManager().isDebug()) {
                            String responseBody = "нет тела ответа";
                            if (response.body() != null) {
                                try {
                                    responseBody = response.body().string();
                                } catch (IOException e) {
                                    // Игнорируем ошибку чтения
                                }
                            }
                            PsichBot.getInstance().getLogger()
                                    .warning("[DEBUG] Ответ Discord: " + responseBody);
                        }
                        return false;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            PsichBot.getInstance().getLogger()
                    .warning("Ошибка отправки сообщения в Discord: " + e.getMessage());
            if (PsichBot.getInstance().getConfigManager().isDebug()) {
                e.printStackTrace();
            }
            return false;
        }
    }
}
