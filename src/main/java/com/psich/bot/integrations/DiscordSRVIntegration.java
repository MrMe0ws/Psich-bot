package com.psich.bot.integrations;

import com.psich.bot.PsichBot;
import com.psich.bot.services.StorageService;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePostProcessEvent;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Интеграция с DiscordSRV для обработки сообщений из Discord
 * Использует рефлексию для доступа к API DiscordSRV
 */
public class DiscordSRVIntegration {

    private final PsichBot plugin;
    private final Random random = new Random();
    private final Pattern triggerPattern;
    private boolean discordSRVAvailable = false;
    private Object discordSRVApi;

    public DiscordSRVIntegration(PsichBot plugin) {
        this.plugin = plugin;
        String trigger = plugin.getConfigManager().getTrigger();
        this.triggerPattern = Pattern
                .compile("(?i)(?<![а-яёa-z0-9_])(" + Pattern.quote(trigger) + "|psych)(?![а-яёa-z0-9_])");
    }

    /**
     * Инициализирует интеграцию с DiscordSRV
     * Возвращает true, если DiscordSRV найден и интеграция успешно настроена
     */
    public boolean initialize() {
        try {
            // Проверяем, установлен ли DiscordSRV
            if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[DEBUG] DiscordSRV не найден, интеграция отключена");
                }
                return false;
            }

            // Получаем API DiscordSRV через рефлексию
            try {
                // Получаем класс DiscordSRV
                Class<?> discordSRVClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");

                // Пытаемся получить API через статическое поле "api"
                try {
                    java.lang.reflect.Field apiField = discordSRVClass.getField("api");
                    discordSRVApi = apiField.get(null);
                } catch (NoSuchFieldException e) {
                    // Если поле недоступно, пытаемся через метод getPlugin().getApi()
                    Method getPluginMethod = discordSRVClass.getMethod("getPlugin");
                    Object discordSRVPlugin = getPluginMethod.invoke(null);

                    // Пытаемся получить API из плагина
                    try {
                        Method getApiMethod = discordSRVPlugin.getClass().getMethod("getApi");
                        discordSRVApi = getApiMethod.invoke(discordSRVPlugin);
                    } catch (NoSuchMethodException e2) {
                        // Пытаемся получить через поле api в плагине
                        java.lang.reflect.Field apiField = discordSRVPlugin.getClass().getField("api");
                        discordSRVApi = apiField.get(discordSRVPlugin);
                    }
                }

                // Подписываемся на события через DiscordSRV API
                // Метод с аннотацией @Subscribe будет найден автоматически
                Method subscribeMethod = discordSRVApi.getClass().getMethod("subscribe", Object.class);
                subscribeMethod.invoke(discordSRVApi, this);

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[DEBUG] Слушатель DiscordSRV зарегистрирован");
                }

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[DEBUG] API DiscordSRV получен: " + discordSRVApi.getClass().getName());
                    plugin.getLogger().info("[DEBUG] Слушатель событий DiscordSRV зарегистрирован");

                    // Проверяем, какие методы есть в этом классе
                    Method[] methods = this.getClass().getMethods();
                    plugin.getLogger().info("[DEBUG] Доступные методы для DiscordSRV:");
                    for (Method m : methods) {
                        if (m.getName().contains("discord") || m.getName().contains("Discord") ||
                                m.getName().contains("Message")) {
                            plugin.getLogger().info("[DEBUG]   - " + m.getName() + "(" +
                                    java.util.Arrays.toString(m.getParameterTypes()) + ")");
                        }
                    }
                }

                discordSRVAvailable = true;
                plugin.getLogger().info("Интеграция с DiscordSRV успешно инициализирована!");
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось загрузить DiscordSRV API: " + e.getMessage());
                if (plugin.getConfigManager().isDebug()) {
                    e.printStackTrace();
                }
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка инициализации интеграции с DiscordSRV: " + e.getMessage());
            if (plugin.getConfigManager().isDebug()) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Обработчик события DiscordSRV с правильной сигнатурой и аннотацией @Subscribe
     * Этот метод будет автоматически найден DiscordSRV через аннотацию
     */
    @Subscribe
    public void onDiscordMessagePostProcess(DiscordGuildMessagePostProcessEvent event) {
        if (!discordSRVAvailable) {
            return;
        }

        try {
            // Получаем автора
            String authorName = event.getAuthor().getName();

            // Получаем оригинальное сообщение из Discord (чистый текст)
            String originalMessage = event.getMessage().getContentRaw();

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Сообщение из Discord от " + authorName + ": " + originalMessage);
            }

            // Обрабатываем сообщение
            processDiscordMessage(authorName, originalMessage);
        } catch (Exception e) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger()
                        .info("[DEBUG] Ошибка обработки DiscordGuildMessagePostProcessEvent: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Обрабатывает сообщение из Discord
     */
    private void processDiscordMessage(String authorName, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        String chatId = "global";

        // Игнорируем сообщения от бота
        String cleanMessage = message.replaceAll("§[0-9a-fk-or]", "");
        if (cleanMessage.startsWith("[Псич]") || cleanMessage.startsWith("<Псич>")) {
            plugin.getStorageService().addToHistory(chatId, "Псич", message);
            return;
        }

        // Используем имя автора из события
        String playerName = authorName != null ? authorName : "Discord";
        String actualMessage = message.trim();

        // Игнорируем пустые сообщения
        if (actualMessage == null || actualMessage.isEmpty()) {
            return;
        }

        // Сохраняем сообщение в историю
        plugin.getStorageService().addToHistory(chatId, playerName, actualMessage);
        // Используем фиктивный UUID для Discord пользователей
        String playerId = "discord-" + playerName.toLowerCase().replaceAll("[^a-z0-9]", "");
        plugin.getStorageService().trackUser(chatId, playerId, playerName);

        // Проверяем, не в муте ли чат
        if (plugin.getStorageService().isMuted(chatId)) {
            return;
        }

        String lowerMessage = actualMessage.toLowerCase();
        boolean hasTrigger = triggerPattern.matcher(lowerMessage).find();

        // Проверяем, нужен ли поиск в интернете (ключевые слова: найди, поищи, гугл)
        boolean requiresSearch = hasTrigger && (lowerMessage.contains("найди") ||
                lowerMessage.contains("поищи"));

        // Если прямое обращение - обрабатываем
        if (hasTrigger) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger()
                        .info("[DEBUG] Прямое обращение к боту из Discord от " + playerName + ": " + actualMessage);
                if (requiresSearch) {
                    plugin.getLogger().info("[DEBUG] Обнаружен запрос на поиск в интернете из Discord");
                }
            }
            final boolean finalRequiresSearch = requiresSearch;
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        processMessage(chatId, playerId, playerName, actualMessage, true, finalRequiresSearch);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Ошибка обработки сообщения из Discord: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }.runTaskAsynchronously(plugin);
        } else {
            // Спонтанный ответ с шансом для сообщений из Discord
            if (actualMessage.length() >= plugin.getConfigManager().getMinMessageLength() &&
                    random.nextDouble() < plugin.getConfigManager().getSpontaneousChanceMessage()) {

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger()
                            .info("[DEBUG] Проверка спонтанного ответа для сообщения из Discord от " + playerName);
                }

                // Анализируем последние 15 сообщений асинхронно
                final List<StorageService.ChatMessage> history = plugin.getStorageService().getHistory(chatId);
                final int historySize = Math.min(15, history.size());
                final List<StorageService.ChatMessage> recentHistory = history.subList(
                        Math.max(0, history.size() - historySize),
                        history.size());

                final String historyBlock = recentHistory.stream()
                        .map(m -> m.getRole() + ": " + m.getText())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");

                // Проверяем shouldAnswer асинхронно
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            boolean shouldAnswer = plugin.getAIManager().shouldAnswer(historyBlock);
                            if (plugin.getConfigManager().isDebug()) {
                                plugin.getLogger()
                                        .info("[DEBUG] AI решил " + (shouldAnswer ? "ответить" : "не отвечать")
                                                + " на спонтанное сообщение из Discord");
                            }

                            if (shouldAnswer) {
                                // Обрабатываем сообщение
                                processMessage(chatId, playerId, playerName, actualMessage, false);
                            }
                        } catch (Exception e) {
                            plugin.getLogger()
                                    .warning("Ошибка при проверке shouldAnswer для Discord: " + e.getMessage());
                            if (plugin.getConfigManager().isDebug()) {
                                e.printStackTrace();
                            }
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
        }
    }

    /**
     * Обрабатывает сообщение и генерирует ответ (использует тот же метод, что и
     * ChatListener)
     */
    private void processMessage(String chatId, String playerId, String playerName, String message,
            boolean isDirectlyCalled) {
        processMessage(chatId, playerId, playerName, message, isDirectlyCalled, false);
    }

    private void processMessage(String chatId, String playerId, String playerName, String message,
            boolean isDirectlyCalled, boolean requiresSearch) {
        try {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger()
                        .info("[DEBUG] Обработка сообщения из Discord от " + playerName + " (ID: " + playerId + "): "
                                + message);
                plugin.getLogger()
                        .info("[DEBUG] Режим: " + (isDirectlyCalled ? "прямое обращение" : "спонтанный ответ"));
            }

            // Получаем профиль пользователя
            StorageService.UserProfile userProfile = plugin.getStorageService().getProfile(chatId, playerId);

            // Получаем историю
            List<StorageService.ChatMessage> history = plugin.getStorageService().getHistory(chatId);

            // Если сообщение только триггер, добавляем контекст
            String processedMessage = message;
            if (message.trim().equalsIgnoreCase(plugin.getConfigManager().getTrigger()) ||
                    message.trim().equalsIgnoreCase("psych")) {
                processedMessage = "Псич, привет! Что нужно?";
            }

            // Генерируем ответ
            String response = plugin.getAIManager().getResponse(
                    history,
                    processedMessage,
                    playerName,
                    userProfile,
                    !isDirectlyCalled,
                    requiresSearch);

            if (response == null || response.trim().isEmpty()) {
                plugin.getLogger().warning("AI вернул пустой ответ на сообщение из Discord");
                return;
            }

            // Отправляем ответ
            sendResponse(chatId, response);

            // Сохраняем ответ в историю
            plugin.getStorageService().addToHistory(chatId, "Псич", response);

        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка AI при обработке сообщения из Discord: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Отправляет ответ бота в игру и Discord
     */
    private void sendResponse(String chatId, String response) {
        final String fullResponse = response;
        final double delaySeconds = plugin.getConfigManager().getResponseDelay();

        new BukkitRunnable() {
            @Override
            public void run() {
                // Задержка перед отправкой
                if (delaySeconds > 0) {
                    try {
                        Thread.sleep((long) (delaySeconds * 1000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                // Разбиваем на части по 250 символов (оставляем запас для префикса "[Псич] ")
                int maxLength = 250;
                String remaining = fullResponse;
                int partNumber = 1;
                int maxParts = 2; // Максимум 2 сообщения

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger()
                            .info("[DEBUG] Начало разбиения сообщения (длина: " + fullResponse.length() + " символов)");
                }

                while (!remaining.isEmpty() && partNumber <= maxParts) {
                    String part;
                    if (remaining.length() <= maxLength) {
                        part = remaining;
                        remaining = "";
                    } else {
                        // Ищем последний пробел перед лимитом для красивого разрыва
                        int breakPoint = maxLength;
                        int lastSpace = remaining.lastIndexOf(' ', breakPoint);
                        if (lastSpace > maxLength * 0.7) { // Если пробел не слишком далеко
                            breakPoint = lastSpace;
                        }
                        part = remaining.substring(0, breakPoint);
                        remaining = remaining.substring(breakPoint).trim();
                    }

                    String colorCode = plugin.getConfigManager().getNameColorCode();
                    String messageToSend;
                    if (plugin.getConfigManager().isSendAsPlayer()) {
                        if (partNumber == 1) {
                            messageToSend = colorCode + "<Псич> §f" + part;
                        } else {
                            messageToSend = colorCode + "<Псич> §7(продолжение) §f" + part;
                        }
                    } else {
                        if (partNumber == 1) {
                            messageToSend = colorCode + "[Псич] §f" + part;
                        } else {
                            messageToSend = colorCode + "[Псич] §7(продолжение) §f" + part;
                        }
                    }

                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[DEBUG] Отправка сообщения #" + partNumber + " (длина части: "
                                + part.length() + " символов, общая длина: " + messageToSend.length() + " символов)");
                    }

                    // Проверяем, не превышает ли сообщение лимит Minecraft (256 символов)
                    if (messageToSend.length() > 256) {
                        plugin.getLogger().warning("[WARNING] Сообщение превышает лимит Minecraft (256 символов): "
                                + messageToSend.length() + " символов. Обрезаем до 256.");
                        messageToSend = messageToSend.substring(0, 256);
                    }

                    // Отправляем в игру
                    plugin.getServer().broadcastMessage(messageToSend);

                    // Отправляем в Discord через Webhook (если настроено)
                    if (plugin.getConfigManager().isDiscordEnabled()
                            && !plugin.getConfigManager().getDiscordWebhookUrl().isEmpty()) {
                        String cleanMessage = messageToSend.replaceAll("§[0-9a-fk-or]", "");
                        final String discordMessage = cleanMessage.replaceAll("^\\s*[<\\[]Псич[>\\]]\\s*", "").trim();
                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                            DiscordWebhookIntegration.sendMessage(
                                    plugin.getConfigManager().getDiscordWebhookUrl(),
                                    discordMessage,
                                    plugin.getConfigManager().getDiscordUsername(),
                                    plugin.getConfigManager().getDiscordAvatarUrl());
                        });
                    }

                    partNumber++;

                    if (!remaining.isEmpty()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }.runTask(plugin);
    }

    public boolean isAvailable() {
        return discordSRVAvailable;
    }
}
