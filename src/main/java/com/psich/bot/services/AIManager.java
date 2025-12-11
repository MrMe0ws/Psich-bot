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

        // Инициализируем провайдеры
        if (!config.getGeminiKeys().isEmpty()) {
            providers.add(new GeminiProvider(config.getGeminiKeys(), config));
            // Добавляем Gemma (использует те же ключи что и Gemini)
            providers.add(new GemmaProvider(config.getGeminiKeys(), config));
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("Gemini провайдер инициализирован с " + config.getGeminiKeys().size() + " ключами");
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("Gemma провайдер инициализирован с " + config.getGeminiKeys().size() + " ключами");
        }

        if (!config.getGroqKeys().isEmpty()) {
            providers.add(new GroqProvider(config.getGroqKeys(), config));
            // Добавляем простую модель Groq для fallback
            providers.add(new GroqProvider(config.getGroqKeys(), true, config));
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("Groq провайдер инициализирован с " + config.getGroqKeys().size() + " ключами");
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("Groq-Simple провайдер инициализирован с " + config.getGroqKeys().size() + " ключами");
        }

        if (!config.getDeepseekKeys().isEmpty()) {
            providers.add(new DeepSeekProvider(config.getDeepseekKeys(), config));
            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                    .info("DeepSeek провайдер инициализирован с " + config.getDeepseekKeys().size() + " ключами");
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

        // Для обычного общения - приоритет: Groq > Gemini > Gemma > Groq-Simple >
        // DeepSeek (платный, в крайнем случае)
        String[] priorityOrder = { "Groq", "Gemini", "Gemma", "Groq-Simple", "DeepSeek" };

        for (String priorityName : priorityOrder) {
            for (BaseProvider provider : providers) {
                if (provider.getName().equals(priorityName) && provider.isAvailable()) {
                    return provider;
                }
            }
        }

        // Если ничего не нашли, берем любой доступный
        return providers.stream()
                .filter(BaseProvider::isAvailable)
                .findFirst()
                .orElse(null);
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
            String errorMsg = error.getMessage();
            boolean isQuotaExhausted = errorMsg.contains("429") ||
                    errorMsg.contains("quota") ||
                    errorMsg.contains("limit") ||
                    errorMsg.contains("402") ||
                    errorMsg.contains("Insufficient");

            // Логируем только краткое сообщение об ошибке
            if (config.isDebug()) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning("[DEBUG] " + preferredProvider.getName() + " ошибка: " + errorMsg);
            } else if (isQuotaExhausted) {
                JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                        .warning(preferredProvider.getName() + " исчерпал лимит");
            } else {
                // Показываем только если это не ошибка прокси (чтобы не спамить)
                if (!errorMsg.contains("Proxy error") && !errorMsg.contains("403") && !errorMsg.contains("CONNECT")) {
                    JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                            .warning(preferredProvider.getName() + " недоступен");
                }
            }

            // Пробуем других провайдеров (сначала основные, потом простые, DeepSeek в
            // последнюю очередь)
            String[] fallbackOrder = { "Groq", "Gemini", "Gemma", "Groq-Simple", "DeepSeek" };

            for (String providerName : fallbackOrder) {
                for (BaseProvider provider : providers) {
                    if (provider == preferredProvider)
                        continue;
                    if (!provider.isAvailable())
                        continue;
                    if (!provider.getName().equals(providerName))
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
                        continue;
                    }
                }
            }

            // Если все основные провайдеры исчерпаны, пробуем простые модели
            for (BaseProvider provider : providers) {
                if (provider == preferredProvider)
                    continue;
                if (!provider.isAvailable())
                    continue;
                if (provider.getName().contains("Simple") || provider.getName().equals("Gemma")) {
                    // Пропускаем если не подходит по фичам
                    if (requiresVision && !provider.supportsVision())
                        continue;

                    try {
                        if (config.isDebug()) {
                            JavaPlugin.getPlugin(com.psich.bot.PsichBot.class).getLogger()
                                    .info("[DEBUG] Переключаюсь на простую модель " + provider.getName()
                                            + " (последний fallback)");
                        }
                        return task.execute(provider);
                    } catch (Exception fallbackError) {
                        continue;
                    }
                }
            }

            throw new Exception("Все AI провайдеры исчерпали лимиты или недоступны");
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

            String fullPrompt = Prompts.getMainChatPrompt(
                    isSpontaneous,
                    currentMessage,
                    contextStr,
                    personalInfo,
                    senderName) + searchInstruction;

            BaseProvider.GenerateOptions options = new BaseProvider.GenerateOptions();
            options.setSystemPrompt(systemPrompt);
            // Ограничиваем токены для поиска, чтобы ответ не превышал 510 символов
            options.setMaxTokens(requiresSearch ? 400 : 2500);
            options.setTemperature(0.9);
            options.setRequiresSearch(requiresSearch);

            // Для Groq и DeepSeek используем системный промпт в опциях
            if (provider.getName().equals("Groq") || provider.getName().equals("DeepSeek")) {
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
     * Приоритет: Gemma > Groq-Simple > Groq > Gemini > DeepSeek
     */
    private BaseProvider selectSimpleProvider() {
        // Для простых задач используем дешевые модели сначала
        String[] simplePriorityOrder = { "Gemma", "Groq-Simple", "Groq", "Gemini", "DeepSeek" };

        for (String priorityName : simplePriorityOrder) {
            for (BaseProvider provider : providers) {
                if (provider.getName().equals(priorityName) && provider.isAvailable()) {
                    return provider;
                }
            }
        }

        // Если ничего не нашли, берем любой доступный
        return providers.stream()
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
            String prompt = Prompts.getShouldAnswerPrompt(historyBlock);
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
            String result = executeWithFallback((provider) -> {
                String prompt = Prompts.getShouldAnswerPrompt(historyBlock);
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
