package com.psich.bot.services;

import com.psich.bot.services.providers.*;
import com.psich.bot.utils.ConfigManager;
import com.psich.bot.utils.Prompts;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AIManager {

    private final ConfigManager config;
    private final List<BaseProvider> providers;
    private final Random random = new Random();

    public AIManager(ConfigManager config) {
        this.config = config;
        this.providers = new ArrayList<>();

        // Устанавливаем промпт из конфига
        Prompts.setSystemPrompt(config.getSystemPrompt());

        // Инициализируем провайдеры (модели берутся из ai.models в конфиге)
        java.util.logging.Logger logger = JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger();

        if (config.isCustomEnabled()) {
            providers.add(new OpenAICompatibleProvider("Custom", config.getCustomApiUrl(), config.getCustomModel(),
                    config.getCustomKeys(), config.isCustomJsonMode(), config));
            logger.info("Custom провайдер инициализирован: " + config.getCustomModel() + " ("
                    + config.getCustomApiUrl() + ")");
        }

        if (!config.getGeminiKeys().isEmpty()) {
            providers.add(new GeminiProvider(config.getGeminiKeys(), config));
            // Добавляем Gemma (использует те же ключи что и Gemini)
            providers.add(new GemmaProvider(config.getGeminiKeys(), config));
            logger.info("Gemini провайдер инициализирован с " + config.getGeminiKeys().size() + " ключами (модель "
                    + config.getGeminiModel() + ")");
            logger.info("Gemma провайдер инициализирован с " + config.getGeminiKeys().size() + " ключами (модель "
                    + config.getGemmaModel() + ")");
        }

        if (!config.getGroqKeys().isEmpty()) {
            providers.add(new GroqProvider(config.getGroqKeys(), config));
            // Добавляем простую модель Groq для fallback
            providers.add(new GroqProvider(config.getGroqKeys(), true, config));
            logger.info("Groq провайдер инициализирован с " + config.getGroqKeys().size() + " ключами (модель "
                    + config.getGroqModel() + ")");
            logger.info("Groq-Simple провайдер инициализирован с " + config.getGroqKeys().size()
                    + " ключами (модель " + config.getGroqSimpleModel() + ")");
        }

        if (!config.getDeepseekKeys().isEmpty()) {
            providers.add(new DeepSeekProvider(config.getDeepseekKeys(), config));
            logger.info("DeepSeek провайдер инициализирован с " + config.getDeepseekKeys().size()
                    + " ключами (модель " + config.getDeepseekModel() + ")");
        }

        // Логируем статус прокси
        if (config.isProxyEnabled()) {
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("Прокси включен: " + config.getProxyHost() + ":" + config.getProxyPort());
        }

        if (providers.isEmpty()) {
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .warning("КРИТИЧЕСКАЯ ОШИБКА: Нет доступных AI провайдеров!");
        } else {
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("AI Manager готов. Провайдеров: " + providers.size());
        }
    }

    private BaseProvider selectProvider(boolean requiresVision, boolean requiresSearch) {
        // Если нужен vision или search - используем только Gemini
        if (requiresVision || requiresSearch) {
            for (BaseProvider provider : providers) {
                if (!provider.isAvailable())
                    continue;
                if (provider.getName().equals("Gemini")) {
                    if (requiresVision && !provider.supportsVision())
                        continue;
                    if (requiresSearch && !provider.supportsSearch())
                        continue;
                    return provider;
                }
            }
        }

        // Для обычного общения - порядок из ai.priority (по умолчанию:
        // Custom > Groq > Gemini > Gemma > Groq-Simple > DeepSeek)
        return orderedProviders(config.getProviderPriority()).stream()
                .filter(BaseProvider::isAvailable)
                .findFirst()
                .orElse(null);
    }

    /**
     * Возвращает провайдеры в указанном порядке. Провайдеры, которых нет в
     * списке, идут в конце
     */
    private List<BaseProvider> orderedProviders(List<String> priority) {
        List<BaseProvider> ordered = new ArrayList<>();
        for (String priorityName : priority) {
            for (BaseProvider provider : providers) {
                if (provider.getName().equalsIgnoreCase(priorityName.trim()) && !ordered.contains(provider)) {
                    ordered.add(provider);
                }
            }
        }
        for (BaseProvider provider : providers) {
            if (!ordered.contains(provider)) {
                ordered.add(provider);
            }
        }
        return ordered;
    }

    private String executeWithFallback(ProviderTask task, boolean requiresVision, boolean requiresSearch)
            throws Exception {
        BaseProvider preferredProvider = selectProvider(requiresVision, requiresSearch);

        if (preferredProvider == null) {
            throw new Exception("Нет доступных AI провайдеров");
        }

        if (config.isDebug()) {
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("[DEBUG] Выбран провайдер: " + preferredProvider.getName() + " (vision=" + requiresVision
                            + ", search=" + requiresSearch + ")");
        }

        // Пробуем предпочтительный провайдер
        try {
            String result = task.execute(preferredProvider);
            if (config.isDebug()) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .info("[DEBUG] Успешно получен ответ от " + preferredProvider.getName() + ", длина: "
                                + (result != null ? result.length() : 0) + " символов");
            }
            return result;
        } catch (Exception error) {
            String errorMsg = error.getMessage() != null ? error.getMessage() : error.toString();
            boolean isQuotaExhausted = errorMsg.contains("429") ||
                    errorMsg.contains("quota") ||
                    errorMsg.contains("limit") ||
                    errorMsg.contains("402") ||
                    errorMsg.contains("Insufficient");
            boolean isProxyError = (errorMsg.contains("Proxy error") ||
                    errorMsg.contains("403") && errorMsg.contains("CONNECT") ||
                    errorMsg.contains("Connection refused") ||
                    errorMsg.contains("timeout") ||
                    errorMsg.contains("Network error"));

            // Логируем только краткое сообщение об ошибке
            if (config.isDebug()) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning("[DEBUG] " + preferredProvider.getName() + " ошибка: " + errorMsg);
            } else if (isQuotaExhausted) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning(preferredProvider.getName() + " исчерпал лимит");
            } else if (isProxyError) {
                // Если ошибка прокси - логируем только один раз, чтобы не спамить
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning(preferredProvider.getName() + " ошибка прокси/сети");
            } else {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning(preferredProvider.getName() + " недоступен");
            }

            // Пробуем остальных провайдеров в порядке ai.priority
            for (BaseProvider provider : orderedProviders(config.getProviderPriority())) {
                if (provider == preferredProvider)
                    continue;
                if (!provider.isAvailable())
                    continue;

                // Пропускаем если не подходит по фичам
                if (requiresVision && !provider.supportsVision())
                    continue;

                try {
                    if (config.isDebug()) {
                        JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                                .info("[DEBUG] Переключаюсь на " + provider.getName() + " (fallback)");
                    }
                    String result = task.execute(provider);
                    if (config.isDebug()) {
                        JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                                .info("[DEBUG] Успешно получен ответ от " + provider.getName()
                                        + " (fallback), длина: " + (result != null ? result.length() : 0)
                                        + " символов");
                    }
                    return result;
                } catch (Exception fallbackError) {
                    String fallbackErrorMsg = fallbackError.getMessage();
                    if (config.isDebug()) {
                        JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                                .warning("[DEBUG] " + provider.getName() + " (fallback) ошибка: "
                                        + fallbackErrorMsg);
                    }
                }
            }

            // Собираем информацию о том, почему провайдеры упали
            StringBuilder errorDetails = new StringBuilder("Все AI провайдеры недоступны. ");

            if (isQuotaExhausted) {
                errorDetails.append("Основной провайдер (").append(preferredProvider.getName())
                        .append(") исчерпал лимит запросов (429). ");
            }
            if (isProxyError && config.isProxyEnabled()) {
                errorDetails.append("Обнаружены ошибки прокси/сети. ");
                errorDetails.append("Проверьте настройки прокси: ").append(config.getProxyHost())
                        .append(":").append(config.getProxyPort()).append(". ");
            }
            errorDetails.append("Попробуйте позже или проверьте настройки API ключей и прокси.");

            throw new Exception(errorDetails.toString());
        }
    }

    public String getResponse(List<StorageService.ChatMessage> history,
            String currentMessage,
            String senderName,
            StorageService.UserProfile userProfile,
            boolean isSpontaneous) throws Exception {
        return getResponse(history, currentMessage, senderName, userProfile, isSpontaneous, false);
    }

    public String getResponse(List<StorageService.ChatMessage> history,
            String currentMessage,
            String senderName,
            StorageService.UserProfile userProfile,
            boolean isSpontaneous,
            boolean requiresSearch) throws Exception {
        return executeWithFallback((provider) -> {
            // Берем последние 20 сообщений
            List<StorageService.ChatMessage> relevantHistory = history.size() > 20
                    ? history.subList(history.size() - 20, history.size())
                    : history;

            String contextStr = relevantHistory.stream()
                    .map(m -> m.getRole() + ": " + m.getText())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            String personalInfo = "";
            if (userProfile != null) {
                int score = userProfile.getRelationship();
                String relationText = "";
                if (score <= 20) {
                    relationText = "СТАТУС: ВРАГ (" + score + "/100). Отвечай грубо, с сарказмом.";
                } else if (score <= 40) {
                    relationText = "СТАТУС: ХОЛОД (" + score + "/100). Язви, не доверяй.";
                } else if (score >= 80) {
                    relationText = "СТАТУС: БРАТАН (" + score + "/100). Поддерживай, шути по-доброму.";
                }

                personalInfo = "\n--- ДОСЬЕ ---\nФакты: "
                        + (userProfile.getFacts() != null ? userProfile.getFacts() : "Нет") + "\n" + relationText
                        + "\n-----------------\n";
            }

            String systemPrompt = Prompts.getSystemPrompt();

            // Если нужен поиск, добавляем специальную инструкцию о лимите
            String searchInstruction = "";
            if (requiresSearch) {
                searchInstruction = "\n\n!!! КРИТИЧЕСКИ ВАЖНО ДЛЯ ПОИСКА !!!\n" +
                        "Ты ищешь информацию в интернете через Google Search.\n" +
                        "Твой ответ ДОЛЖЕН быть КРАТКИМ и уложиться в 500 символов (максимум 2 сообщения по 255 символов в Minecraft).\n"
                        +
                        "1. Изложи найденную информацию КРАТКО и по делу.\n" +
                        "2. НЕ добавляй источники, ссылки, упоминания сайтов - это занимает место.\n" +
                        "3. НЕ повторяй вопрос пользователя - сразу давай ответ.\n" +
                        "4. Выбери самое важное из найденного и изложи сжато.\n" +
                        "5. Если информации много - дай краткую выжимку, самое главное.\n" +
                        "СТРОГОЕ ОГРАНИЧЕНИЕ: максимум 500 символов (2 сообщения по 255). Адаптируй найденную информацию под этот лимит.\n";
            }

            String botName = config.getBotName();
            String fullPrompt = Prompts.getMainChatPrompt(
                    isSpontaneous,
                    currentMessage,
                    contextStr,
                    personalInfo,
                    senderName,
                    botName) + searchInstruction;

            BaseProvider.GenerateOptions options = new BaseProvider.GenerateOptions();
            options.setSystemPrompt(systemPrompt);
            // Ограничиваем токены для поиска, чтобы ответ не превышал 510 символов
            options.setMaxTokens(requiresSearch ? 400 : 2500);
            options.setTemperature(0.9);
            options.setRequiresSearch(requiresSearch);

            // Для OpenAI-совместимых API (Groq, DeepSeek, Custom) используем системный промпт в опциях
            if (provider.usesSystemMessage()) {
                String result = provider.generate(fullPrompt, options);
                // Если AI не уложился в лимит, обрезаем (но лучше чтобы AI сам адаптировался)
                if (requiresSearch && result.length() > 510) {
                    // Пытаемся обрезать по последнему пробелу, чтобы не резать слово
                    int cutPoint = 507;
                    int lastSpace = result.lastIndexOf(' ', cutPoint);
                    if (lastSpace > 450) { // Если пробел не слишком далеко
                        cutPoint = lastSpace;
                    }
                    result = result.substring(0, cutPoint) + "...";
                }
                return result;
            }

            // Для Gemini добавляем системный промпт в начало
            String finalPrompt = systemPrompt + "\n\n" + fullPrompt;
            options.setSystemPrompt(null);
            String result = provider.generate(finalPrompt, options);
            // Если AI не уложился в лимит, обрезаем (но лучше чтобы AI сам адаптировался)
            if (requiresSearch && result.length() > 510) {
                // Пытаемся обрезать по последнему пробелу, чтобы не резать слово
                int cutPoint = 507;
                int lastSpace = result.lastIndexOf(' ', cutPoint);
                if (lastSpace > 450) { // Если пробел не слишком далеко
                    cutPoint = lastSpace;
                }
                result = result.substring(0, cutPoint) + "...";
            }
            return result;
        }, false, requiresSearch);
    }

    /**
     * Выбирает простой (дешевый) провайдер для легких задач (YES/NO, реакции и
     * т.д.)
     * Приоритет по умолчанию: Gemma > Groq-Simple > Custom > Groq > Gemini > DeepSeek
     */
    private BaseProvider selectSimpleProvider() {
        // Для простых задач используем дешевые модели сначала (порядок из ai.simple-priority)
        return orderedProviders(config.getSimpleProviderPriority()).stream()
                .filter(BaseProvider::isAvailable)
                .findFirst()
                .orElse(null);
    }

    public boolean shouldAnswer(String historyBlock) throws Exception {
        // Для простых задач (YES/NO) используем дешевые модели сначала
        BaseProvider simpleProvider = selectSimpleProvider();

        if (simpleProvider == null) {
            throw new Exception("Нет доступных AI провайдеров");
        }

        if (config.isDebug()) {
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("[DEBUG] Выбран простой провайдер для shouldAnswer: " + simpleProvider.getName());
        }

        try {
            String botName = config.getBotName();
            String prompt = Prompts.getShouldAnswerPrompt(historyBlock, botName);
            BaseProvider.GenerateOptions options = new BaseProvider.GenerateOptions();
            options.setMaxTokens(10);
            String result = simpleProvider.generate(prompt, options);

            if (config.isDebug()) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .info("[DEBUG] shouldAnswer ответ: " + result);
            }

            return result.toUpperCase().contains("YES");
        } catch (Exception error) {
            // Если простой провайдер не сработал, пробуем через fallback
            if (config.isDebug()) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning("[DEBUG] Простой провайдер " + simpleProvider.getName()
                                + " не сработал, используем fallback: " + error.getMessage());
            }

            // Fallback на обычный метод
            String botName = config.getBotName();
            String result = executeWithFallback((provider) -> {
                String prompt = Prompts.getShouldAnswerPrompt(historyBlock, botName);
                BaseProvider.GenerateOptions options = new BaseProvider.GenerateOptions();
                options.setMaxTokens(10);
                return provider.generate(prompt, options);
            }, false, false);
            return result.toUpperCase().contains("YES");
        }
    }

    public StorageService.UserProfile analyzeUserImmediate(String lastMessages,
            StorageService.UserProfile currentProfile) throws Exception {
        String result = executeWithFallback((provider) -> {
            String prompt = Prompts.getAnalyzeImmediatePrompt(currentProfile, lastMessages);
            BaseProvider.GenerateOptions options = new BaseProvider.GenerateOptions();
            options.setMaxTokens(1000);
            options.setExpectJson(true);
            return provider.generate(prompt, options);
        }, false, false);

        // Парсим JSON ответ
        return Prompts.parseProfileJson(result, currentProfile);
    }

    @FunctionalInterface
    private interface ProviderTask {
        String execute(BaseProvider provider) throws Exception;
    }
}
