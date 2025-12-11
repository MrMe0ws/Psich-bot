package com.psich.bot.services.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.psich.bot.utils.HttpClientFactory;
import okhttp3.*;
import java.io.IOException;
import java.util.List;

public class GeminiProvider extends BaseProvider {
    
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent";
    
    public GeminiProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        super("Gemini", keys, config);
    }
    
    @Override
    public boolean supportsVision() {
        return true;
    }
    
    @Override
    public boolean supportsSearch() {
        return true;
    }
    
    @Override
    public String generate(String prompt, GenerateOptions options) throws Exception {
        return generate(prompt, options, 0);
    }
    
    private String generate(String prompt, GenerateOptions options, int retryCount) throws Exception {
        if (!isAvailable()) {
            throw new Exception("Gemini: Провайдер недоступен (нет ключей)");
        }
        
        // Ограничиваем количество попыток смены ключа
        if (retryCount >= keys.size()) {
            throw new Exception("Gemini: Все ключи исчерпали лимиты");
        }
        
        String apiKey = getCurrentKey();
        String url = API_URL + "?key=" + apiKey;
        
        // Формируем запрос
        JsonObject request = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        
        // Добавляем текст
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);
        parts.add(textPart);
        
        content.add("parts", parts);
        contents.add(content);
        request.add("contents", contents);
        
        // Настройки генерации
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", options.getMaxTokens() != null ? options.getMaxTokens() : 2500);
        generationConfig.addProperty("temperature", options.getTemperature() != null ? options.getTemperature() : 0.9);
        request.add("generationConfig", generationConfig);
        
        // Добавляем Google Search tool, если нужен поиск
        if (options.getRequiresSearch() != null && options.getRequiresSearch()) {
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
                MediaType.parse("application/json")
        );
        
        Request httpRequest = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        
        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    if (rotateKey() && retryCount < keys.size() - 1) {
                        return generate(prompt, options, retryCount + 1); // Повторяем с новым ключом
                    }
                    throw new Exception("Gemini: Rate limit exceeded (429)");
                }
                throw new Exception("Gemini: API error (" + response.code() + "): " + errorBody);
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
                        throw new Exception("Gemini: Content blocked by safety policy");
                    }
                }
                
                if (candidate.has("content") && candidate.getAsJsonObject("content").has("parts")) {
                    JsonArray responseParts = candidate.getAsJsonObject("content").getAsJsonArray("parts");
                    if (responseParts.size() > 0 && responseParts.get(0).getAsJsonObject().has("text")) {
                        String text = responseParts.get(0).getAsJsonObject().get("text").getAsString();
                        
                        // Убираем источники из ответа (не добавляем ссылки в конец)
                        // В Minecraft версии не нужны источники, чтобы не тратить место в лимите 510 символов
                        // Источники обычно добавляются через groundingMetadata, но мы их игнорируем
                        
                        return text;
                    }
                }
            }
            
            throw new Exception("Gemini: Пустой ответ от API");
        } catch (IOException e) {
            String errorMsg = e.getMessage();
            // Если ошибка прокси (403 CONNECT), не пытаемся менять ключ
            if (errorMsg != null && errorMsg.contains("403") && errorMsg.contains("CONNECT")) {
                throw new Exception("Gemini: Proxy error (403) - " + errorMsg);
            }
            throw new Exception("Gemini: Network error - " + errorMsg);
        }
    }
}

