package com.psich.bot.services.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.psich.bot.utils.HttpClientFactory;
import okhttp3.*;
import java.io.IOException;
import java.util.List;

/**
 * Провайдер для любого API в формате OpenAI Chat Completions
 * (Groq, DeepSeek, OpenAI, OpenRouter, Mistral, Ollama, LM Studio и т.д.)
 */
public class OpenAICompatibleProvider extends BaseProvider {

    private final String apiUrl;
    private final String model;
    private final boolean supportsJsonMode;

    public OpenAICompatibleProvider(String name, String apiUrl, String model, List<String> keys,
            boolean supportsJsonMode, com.psich.bot.utils.ConfigManager config) {
        super(name, keys, config);
        this.apiUrl = apiUrl;
        this.model = model;
        this.supportsJsonMode = supportsJsonMode;
    }

    public String getModel() {
        return model;
    }

    @Override
    public boolean usesSystemMessage() {
        return true;
    }

    @Override
    public boolean supportsVision() {
        return false;
    }

    @Override
    public boolean supportsSearch() {
        return false;
    }

    @Override
    public String generate(String prompt, GenerateOptions options) throws Exception {
        return generate(prompt, options, 0);
    }

    private String generate(String prompt, GenerateOptions options, int retryCount) throws Exception {
        if (!isAvailable()) {
            throw new Exception(name + ": Провайдер недоступен (нет ключей)");
        }

        // Ограничиваем количество попыток смены ключа
        if (retryCount >= keys.size()) {
            throw new Exception(name + ": Все ключи исчерпали лимиты");
        }

        String apiKey = getCurrentKey();

        // Формируем запрос
        JsonObject request = new JsonObject();
        request.addProperty("model", model);

        JsonArray messages = new JsonArray();

        // Добавляем системный промпт если есть
        if (options.getSystemPrompt() != null && !options.getSystemPrompt().isEmpty()) {
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", options.getSystemPrompt());
            messages.add(systemMsg);
        }

        // Добавляем пользовательское сообщение
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        request.add("messages", messages);
        request.addProperty("max_tokens", options.getMaxTokens() != null ? options.getMaxTokens() : 2048);
        request.addProperty("temperature", options.getTemperature() != null ? options.getTemperature() : 0.9);
        request.addProperty("stream", false);

        if (supportsJsonMode && options.getExpectJson() != null && options.getExpectJson()) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            request.add("response_format", responseFormat);
        }

        // Отправляем запрос
        OkHttpClient client = HttpClientFactory.createClient(config);

        RequestBody body = RequestBody.create(
                request.toString(),
                MediaType.parse("application/json"));

        Request.Builder httpRequest = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json");
        // Для локальных моделей (Ollama, LM Studio) ключ может быть не нужен
        if (apiKey != null && !apiKey.isEmpty()) {
            httpRequest.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = client.newCall(httpRequest.build()).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    if (rotateKey() && retryCount < keys.size() - 1) {
                        return generate(prompt, options, retryCount + 1); // Повторяем с новым ключом
                    }
                    throw new Exception(name + ": Rate limit exceeded (429)");
                } else if (response.code() == 402) {
                    throw new Exception(name + ": Insufficient balance (402)");
                }
                throw new Exception(name + ": API error (" + response.code() + ", модель " + model + "): " + errorBody);
            }

            String responseBody = response.body().string();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);

            if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.getAsJsonObject("message").has("content")
                        && !choice.getAsJsonObject("message").get("content").isJsonNull()) {
                    return choice.getAsJsonObject("message").get("content").getAsString();
                }
            }

            throw new Exception(name + ": Пустой ответ от API");
        } catch (IOException e) {
            String errorMsg = e.getMessage();
            // Если ошибка прокси (403 CONNECT), не пытаемся менять ключ
            if (errorMsg != null && errorMsg.contains("403") && errorMsg.contains("CONNECT")) {
                throw new Exception(name + ": Proxy error (403) - " + errorMsg);
            }
            throw new Exception(name + ": Network error - " + errorMsg);
        }
    }
}
