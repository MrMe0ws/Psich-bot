package com.psich.bot.services.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.psich.bot.utils.HttpClientFactory;
import okhttp3.*;
import java.io.IOException;
import java.util.List;

public class GroqProvider extends BaseProvider {
    
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.3-70b-versatile";
    private static final String SIMPLE_MODEL = "llama-3.1-8b-instant"; // Простая модель для fallback
    
    private final boolean useSimpleModel;
    
    public GroqProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        this(keys, false, config);
    }
    
    public GroqProvider(List<String> keys, boolean useSimpleModel, com.psich.bot.utils.ConfigManager config) {
        super("Groq" + (useSimpleModel ? "-Simple" : ""), keys, config);
        this.useSimpleModel = useSimpleModel;
    }
    
    private String getModel() {
        return useSimpleModel ? SIMPLE_MODEL : MODEL;
    }
    
    @Override
    public boolean supportsVision() {
        return true;
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
            throw new Exception("Groq: Провайдер недоступен (нет ключей)");
        }
        
        // Ограничиваем количество попыток смены ключа
        if (retryCount >= keys.size()) {
            throw new Exception("Groq: Все ключи исчерпали лимиты");
        }
        
        String apiKey = getCurrentKey();
        
        // Формируем запрос
        JsonObject request = new JsonObject();
        request.addProperty("model", getModel());
        
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
        
        if (options.getExpectJson() != null && options.getExpectJson()) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            request.add("response_format", responseFormat);
        }
        
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
                    throw new Exception("Groq: Rate limit exceeded (429)");
                }
                throw new Exception("Groq: API error (" + response.code() + "): " + errorBody);
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
            
            throw new Exception("Groq: Пустой ответ от API");
        } catch (IOException e) {
            String errorMsg = e.getMessage();
            // Если ошибка прокси (403 CONNECT), не пытаемся менять ключ
            if (errorMsg != null && errorMsg.contains("403") && errorMsg.contains("CONNECT")) {
                throw new Exception("Groq: Proxy error (403) - " + errorMsg);
            }
            throw new Exception("Groq: Network error - " + errorMsg);
        }
    }
}

