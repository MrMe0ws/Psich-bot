package com.psich.bot.services.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.psich.bot.utils.HttpClientFactory;
import okhttp3.*;
import java.io.IOException;
import java.util.List;

public class DeepSeekProvider extends BaseProvider {
    
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";
    
    public DeepSeekProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        super("DeepSeek", keys, config);
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
            throw new Exception("DeepSeek: Провайдер недоступен (нет ключей)");
        }
        
        // Ограничиваем количество попыток смены ключа
        if (retryCount >= keys.size()) {
            throw new Exception("DeepSeek: Все ключи исчерпали лимиты");
        }
        
        String apiKey = getCurrentKey();
        
        // Формируем запрос
        JsonObject request = new JsonObject();
        request.addProperty("model", MODEL);
        
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
        request.addProperty("max_tokens", options.getMaxTokens() != null ? options.getMaxTokens() : 2500);
        request.addProperty("temperature", options.getTemperature() != null ? options.getTemperature() : 0.9);
        request.addProperty("stream", false);
        
        // Отправляем запрос
        OkHttpClient client = HttpClientFactory.createClient(config);
        
        RequestBody body = RequestBody.create(
                request.toString(),
                MediaType.parse("application/json")
        );
        
        Request httpRequest = new Request.Builder()
                .url(API_URL)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
        
        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    if (rotateKey() && retryCount < keys.size() - 1) {
                        return generate(prompt, options, retryCount + 1); // Повторяем с новым ключом
                    }
                    throw new Exception("DeepSeek: Rate limit exceeded (429)");
                } else if (response.code() == 402) {
                    throw new Exception("DeepSeek: Insufficient balance (402)");
                }
                throw new Exception("DeepSeek: API error (" + response.code() + "): " + errorBody);
            }
            
            String responseBody = response.body().string();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            
            if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                    return choice.getAsJsonObject("message").get("content").getAsString();
                }
            }
            
            throw new Exception("DeepSeek: Пустой ответ от API");
        } catch (IOException e) {
            String errorMsg = e.getMessage();
            // Если ошибка прокси (403 CONNECT), не пытаемся менять ключ
            if (errorMsg != null && errorMsg.contains("403") && errorMsg.contains("CONNECT")) {
                throw new Exception("DeepSeek: Proxy error (403) - " + errorMsg);
            }
            throw new Exception("DeepSeek: Network error - " + errorMsg);
        }
    }
}

