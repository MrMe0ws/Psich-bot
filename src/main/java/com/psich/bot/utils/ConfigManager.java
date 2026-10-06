package com.psich.bot.utils;

import com.psich.bot.PsichBot;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

public class ConfigManager {

    private final PsichBot plugin;
    private FileConfiguration config;

    private List<String> geminiKeys;
    private List<String> groqKeys;
    private List<String> deepseekKeys;
    private String geminiModel;
    private String gemmaModel;
    private String groqModel;
    private String groqSimpleModel;
    private String deepseekModel;
    private String googleThinkingLevel;
    private boolean customEnabled;
    private String customApiUrl;
    private String customModel;
    private List<String> customKeys;
    private boolean customJsonMode;
    private List<String> providerPriority;
    private List<String> simpleProviderPriority;
    private String trigger;
    private double spontaneousChanceMessage;
    private double spontaneousChanceJoinQuit;
    private double spontaneousChanceAdvancement;
    private double spontaneousChanceDeath;
    private int contextSize;
    private int minMessageLength;
    private String botName;
    private String systemPrompt;
    private String nameColor;
    private boolean sendAsPlayer;
    private double responseDelay;
    private boolean discordEnabled;
    private String discordWebhookUrl;
    private String discordUsername;
    private String discordAvatarUrl;
    private boolean debug;
    private boolean proxyEnabled;
    private String proxyHost;
    private int proxyPort;
    private String proxyUser;
    private String proxyPassword;

    public ConfigManager(PsichBot plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        loadConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        loadConfig();
    }

    private void loadConfig() {
        // Загружаем AI ключи
        geminiKeys = config.getStringList("ai.gemini-keys");
        groqKeys = config.getStringList("ai.groq-keys");
        deepseekKeys = config.getStringList("ai.deepseek-keys");

        // Загружаем модели (если в старом конфиге секции нет - используются значения по умолчанию)
        geminiModel = getNonEmptyString("ai.models.gemini", "gemini-flash-latest");
        gemmaModel = getNonEmptyString("ai.models.gemma", "gemma-4-26b-a4b-it");
        groqModel = getNonEmptyString("ai.models.groq", "llama-3.3-70b-versatile");
        groqSimpleModel = getNonEmptyString("ai.models.groq-simple", "llama-3.1-8b-instant");
        deepseekModel = getNonEmptyString("ai.models.deepseek", "deepseek-flash");
        googleThinkingLevel = config.getString("ai.models.google-thinking-level", "minimal").trim();

        // Загружаем свой OpenAI-совместимый провайдер
        customEnabled = config.getBoolean("ai.custom.enabled", false);
        customApiUrl = config.getString("ai.custom.api-url", "").trim();
        customModel = config.getString("ai.custom.model", "").trim();
        customKeys = new ArrayList<>(config.getStringList("ai.custom.keys"));
        customKeys.removeIf(ConfigManager::isPlaceholderKey);
        // Для локальных моделей (Ollama, LM Studio) ключ не нужен - работаем без него
        if (customKeys.isEmpty()) {
            customKeys.add("");
        }
        customJsonMode = config.getBoolean("ai.custom.json-mode", false);
        if (customEnabled && (customApiUrl.isEmpty() || customModel.isEmpty())) {
            plugin.getLogger().warning("ai.custom включен, но не указан api-url или model - провайдер отключен");
            customEnabled = false;
        }

        // Порядок использования провайдеров
        providerPriority = config.getStringList("ai.priority");
        if (providerPriority.isEmpty()) {
            providerPriority = List.of("Custom", "Groq", "Gemini", "Gemma", "Groq-Simple", "DeepSeek");
        }
        simpleProviderPriority = config.getStringList("ai.simple-priority");
        if (simpleProviderPriority.isEmpty()) {
            simpleProviderPriority = List.of("Gemma", "Groq-Simple", "Custom", "Groq", "Gemini", "DeepSeek");
        }

        // Загружаем настройки чата
        trigger = config.getString("chat.trigger", "псич");
        // Загружаем отдельные шансы для разных типов событий
        spontaneousChanceMessage = config.getDouble("chat.spontaneous-chance-message", 0.05);
        spontaneousChanceJoinQuit = config.getDouble("chat.spontaneous-chance-join-quit", 0.1);
        spontaneousChanceAdvancement = config.getDouble("chat.spontaneous-chance-advancement", 0.1);
        spontaneousChanceDeath = config.getDouble("chat.spontaneous-chance-death", 0.1);
        // Обратная совместимость: если указан старый параметр, используем его для всех
        if (config.contains("chat.spontaneous-chance") && !config.contains("chat.spontaneous-chance-message")) {
            double oldChance = config.getDouble("chat.spontaneous-chance", 0.05);
            spontaneousChanceMessage = oldChance;
            spontaneousChanceJoinQuit = oldChance;
            spontaneousChanceAdvancement = oldChance;
            spontaneousChanceDeath = oldChance;
        }
        contextSize = config.getInt("chat.context-size", 20);
        minMessageLength = config.getInt("chat.min-message-length", 10);
        botName = config.getString("chat.bot-name", "Псич");
        nameColor = config.getString("chat.name-color", "yellow");
        sendAsPlayer = config.getBoolean("chat.send-as-player", false);
        responseDelay = config.getDouble("chat.response-delay", 1.5);
        discordEnabled = config.getBoolean("discord.enabled", true);
        discordWebhookUrl = config.getString("discord.webhook-url", "");
        discordUsername = config.getString("discord.username", "Псич");
        discordAvatarUrl = config.getString("discord.avatar-url", "");

        // Загружаем режим отладки
        debug = config.getBoolean("debug", false);

        // Загружаем настройки прокси
        proxyEnabled = config.getBoolean("proxy.enabled", false);
        proxyHost = config.getString("proxy.host", "");
        proxyPort = config.getInt("proxy.port", 3128);
        proxyUser = config.getString("proxy.user", "");
        proxyPassword = config.getString("proxy.password", "");

        // Загружаем системный промпт
        systemPrompt = config.getString("prompt.system", "");
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            // Дефолтный промпт если не указан в конфиге
            systemPrompt = "Ты — Псич. говорящий кот. Ты циничный, умный, но свой в доску.";
        }

        // Фильтруем пустые ключи
        geminiKeys = new ArrayList<>(geminiKeys);
        geminiKeys.removeIf(ConfigManager::isPlaceholderKey);
        groqKeys = new ArrayList<>(groqKeys);
        groqKeys.removeIf(ConfigManager::isPlaceholderKey);
        deepseekKeys = new ArrayList<>(deepseekKeys);
        deepseekKeys.removeIf(ConfigManager::isPlaceholderKey);

        plugin.getLogger().info("Загружено ключей Gemini: " + geminiKeys.size());
        plugin.getLogger().info("Загружено ключей Groq: " + groqKeys.size());
        plugin.getLogger().info("Загружено ключей DeepSeek: " + deepseekKeys.size());
    }

    private String getNonEmptyString(String path, String def) {
        String value = config.getString(path, def);
        return value == null || value.trim().isEmpty() ? def : value.trim();
    }

    // Пустые ключи и заглушки из конфига по умолчанию ("YOUR_..._KEY_HERE") не считаются ключами
    private static boolean isPlaceholderKey(String key) {
        return key == null || key.trim().isEmpty() || key.trim().startsWith("YOUR_");
    }

    public List<String> getGeminiKeys() {
        return geminiKeys;
    }

    public List<String> getGroqKeys() {
        return groqKeys;
    }

    public List<String> getDeepseekKeys() {
        return deepseekKeys;
    }

    public String getGeminiModel() {
        return geminiModel;
    }

    public String getGemmaModel() {
        return gemmaModel;
    }

    public String getGroqModel() {
        return groqModel;
    }

    public String getGroqSimpleModel() {
        return groqSimpleModel;
    }

    public String getDeepseekModel() {
        return deepseekModel;
    }

    /**
     * Уровень размышлений для моделей Google (minimal, low, medium, high).
     * Пустая строка - параметр не отправляется
     */
    public String getGoogleThinkingLevel() {
        return googleThinkingLevel;
    }

    public boolean isCustomEnabled() {
        return customEnabled;
    }

    public String getCustomApiUrl() {
        return customApiUrl;
    }

    public String getCustomModel() {
        return customModel;
    }

    public List<String> getCustomKeys() {
        return customKeys;
    }

    public boolean isCustomJsonMode() {
        return customJsonMode;
    }

    public List<String> getProviderPriority() {
        return providerPriority;
    }

    public List<String> getSimpleProviderPriority() {
        return simpleProviderPriority;
    }

    public String getTrigger() {
        return trigger;
    }

    /**
     * @deprecated Используйте getSpontaneousChanceMessage() вместо этого метода
     */
    @Deprecated
    public double getSpontaneousChance() {
        return spontaneousChanceMessage;
    }

    /**
     * Возвращает шанс спонтанного ответа на обычные сообщения в чате
     */
    public double getSpontaneousChanceMessage() {
        return spontaneousChanceMessage;
    }

    /**
     * Возвращает шанс спонтанного ответа на вход/выход/первый заход игроков
     */
    public double getSpontaneousChanceJoinQuit() {
        return spontaneousChanceJoinQuit;
    }

    /**
     * Возвращает шанс спонтанного ответа на получение ачивок игроками
     */
    public double getSpontaneousChanceAdvancement() {
        return spontaneousChanceAdvancement;
    }

    /**
     * Возвращает шанс спонтанного ответа на смерти игроков
     */
    public double getSpontaneousChanceDeath() {
        return spontaneousChanceDeath;
    }

    public int getContextSize() {
        return contextSize;
    }

    public int getMinMessageLength() {
        return minMessageLength;
    }

    public String getBotName() {
        return botName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getNameColor() {
        return nameColor;
    }

    /**
     * Преобразует название цвета или код цвета в код Minecraft
     * Поддерживает названия цветов и прямые коды (§e, §c и т.д.)
     */
    public String getNameColorCode() {
        if (nameColor == null || nameColor.trim().isEmpty()) {
            return "§e"; // Дефолт - желтый
        }

        String color = nameColor.trim().toLowerCase();

        // Если уже код цвета (начинается с §)
        if (color.startsWith("§")) {
            return nameColor.substring(0, 2); // Возвращаем первые 2 символа (§ + код)
        }

        // Маппинг названий цветов на коды
        switch (color) {
            case "black":
                return "§0";
            case "dark_blue":
                return "§1";
            case "dark_green":
                return "§2";
            case "dark_aqua":
            case "dark_cyan":
                return "§3";
            case "dark_red":
                return "§4";
            case "dark_purple":
            case "dark_magenta":
                return "§5";
            case "gold":
            case "orange":
                return "§6";
            case "gray":
            case "grey":
                return "§7";
            case "dark_gray":
            case "dark_grey":
                return "§8";
            case "blue":
                return "§9";
            case "green":
                return "§a";
            case "aqua":
            case "cyan":
                return "§b";
            case "red":
                return "§c";
            case "light_purple":
            case "magenta":
            case "pink":
                return "§d";
            case "yellow":
                return "§e";
            case "white":
                return "§f";
            default:
                return "§e"; // Дефолт - желтый
        }
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public String getProxyUser() {
        return proxyUser;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public boolean isSendAsPlayer() {
        return sendAsPlayer;
    }

    public double getResponseDelay() {
        return responseDelay;
    }

    public boolean isDiscordEnabled() {
        return discordEnabled;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public String getDiscordUsername() {
        return discordUsername;
    }

    public String getDiscordAvatarUrl() {
        return discordAvatarUrl;
    }
}
