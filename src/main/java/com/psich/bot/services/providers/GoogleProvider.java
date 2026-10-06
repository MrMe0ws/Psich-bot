package com.psich.bot.services.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.psich.bot.utils.HttpClientFactory;
import okhttp3.*;
import java.io.IOException;
import java.util.List;

/**
 * Провайдер для моделей Google (Gemini и Gemma) через Gemini API
 */
public class GoogleProvider extends BaseProvider {

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    // Модели с "размышлениями" тратят часть maxOutputTokens на мысли, поэтому
    // слишком маленький лимит (например 10 токенов для YES/NO) дает пустой ответ
    private static final int MIN_OUTPUT_TOKENS = 256;

    private final String model;
    private final boolean searchAndVision;
    // Сбрасывается в false, если модель не поддерживает thinkingConfig
    private volatile boolean sendThinkingConfig = true;

    public GoogleProvider(String name, String model, List<String> keys, boolean searchAndVision,
            com.psich.bot.utils.ConfigManager config) {
        super(name, keys, config);
        this.model = model;
        this.searchAndVision = searchAndVision;
    }

    public String getModel() {
        return model;
    }

    @Override
    public boolean supportsVision() {
        return searchAndVision;
    }

    @Override
    public boolean supportsSearch() {
        return searchAndVision;
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
        String url = API_BASE + model + ":generateContent";

        // Формируем запрос
        JsonObject request = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();

        // Добавляем текст
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);
        parts.add(textPart);

        content.addProperty("role", "user");
        content.add("parts", parts);
        contents.add(content);
        request.add("contents", contents);

        // Настройки генерации
        int maxTokens = options.getMaxTokens() != null ? options.getMaxTokens() : 2500;
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", Math.max(maxTokens, MIN_OUTPUT_TOKENS));
        generationConfig.addProperty("temperature", options.getTemperature() != null ? options.getTemperature() : 0.9);

        String thinkingLevel = config.getGoogleThinkingLevel();
        boolean withThinking = sendThinkingConfig && thinkingLevel != null && !thinkingLevel.isEmpty();
        if (withThinking) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("thinkingLevel", thinkingLevel);
            generationConfig.add("thinkingConfig", thinkingConfig);
        }
        request.add("generationConfig", generationConfig);

        // Добавляем Google Search tool, если нужен поиск
        if (searchAndVision && options.getRequiresSearch() != null && options.getRequiresSearch()) {
            JsonArray tools = new JsonArray();
            JsonObject googleSearchTool = new JsonObject();
            JsonObject googleSearch = new JsonObject();
            googleSearchTool.add("googleSearch", googleSearch);
            tools.add(googleSearchTool);
            request.add("tools", tools);
        }

        // Отправляем запрос
        OkHttpClient client = HttpClientFactory.createClient(config);

        RequestBody body = RequestBody.create(
                request.toString(),
                MediaType.parse("application/json"));

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("x-goog-api-key", apiKey)
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    if (rotateKey() && retryCount < keys.size() - 1) {
                        return generate(prompt, options, retryCount + 1); // Повторяем с новым ключом
                    }
                    throw new Exception(name + ": Rate limit exceeded (429)");
                }
                // Модель не поддерживает выбранный уровень размышлений - повторяем без него
                if (response.code() == 400 && withThinking && errorBody.toLowerCase().contains("thinking")) {
                    sendThinkingConfig = false;
                    if (config.isDebug()) {
                        com.psich.bot.PsichBot.getInstance().getLogger().warning("[DEBUG] " + name + " (" + model
                                + ") не поддерживает thinkingLevel=" + thinkingLevel + ", отправляю без него");
                    }
                    return generate(prompt, options, retryCount);
                }
                if (response.code() == 404) {
                    throw new Exception(name + ": Модель \"" + model
                            + "\" не найдена (404). Укажите актуальную модель в config.yml (ai.models)");
                }
                throw new Exception(name + ": API error (" + response.code() + ", модель " + model + "): " + errorBody);
            }

            String responseBody = response.body().string();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);

            if (json.has("candidates") && json.getAsJsonArray("candidates").size() > 0) {
                JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();

                // Проверка на блокировку безопасности
                if (candidate.has("finishReason")) {
                    String finishReason = candidate.get("finishReason").getAsString();
                    if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason)) {
                        throw new Exception(name + ": Content blocked by safety policy");
                    }
                }

                if (candidate.has("content") && candidate.getAsJsonObject("content").has("parts")) {
                    // Собираем текст из всех частей, пропуская "мысли" модели.
                    // Источники (groundingMetadata) игнорируем, чтобы не тратить лимит 510 символов
                    StringBuilder text = new StringBuilder();
                    for (JsonElement partElement : candidate.getAsJsonObject("content").getAsJsonArray("parts")) {
                        JsonObject part = partElement.getAsJsonObject();
                        if (part.has("thought") && part.get("thought").getAsBoolean()) {
                            continue;
                        }
                        if (part.has("text")) {
                            text.append(part.get("text").getAsString());
                        }
                    }
                    if (text.length() > 0) {
                        return text.toString();
                    }
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
