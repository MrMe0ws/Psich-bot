package com.psich.bot.listeners;

import com.psich.bot.PsichBot;
import com.psich.bot.integrations.DiscordWebhookIntegration;
import com.psich.bot.services.StorageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

public class ChatListener implements Listener {

    private final PsichBot plugin;
    private final Random random = new Random();
    private final Pattern triggerPattern;

    public ChatListener(PsichBot plugin) {
        this.plugin = plugin;
        String trigger = plugin.getConfigManager().getTrigger();
        // Создаем regex для поиска триггера как отдельного слова
        this.triggerPattern = Pattern
                .compile("(?i)(?<![а-яёa-z0-9_])(" + Pattern.quote(trigger) + "|psych)(?![а-яёa-z0-9_])");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // Игнорируем сообщения от консоли или других источников
        if (event.getPlayer() == null)
            return;

        String message = event.getMessage();
        String playerName = event.getPlayer().getName();
        String playerId = event.getPlayer().getUniqueId().toString();
        String chatId = "global"; // В Minecraft один глобальный чат

        // Игнорируем пустые сообщения
        if (message == null || message.trim().isEmpty())
            return;

        // Игнорируем сообщения, которые начинаются с "[Псич]" или "<Псич>" - это
        // сообщения от самого бота
        // Это предотвращает бесконечные ответы самому себе
        String cleanMessageForCheck = message.replaceAll("§[0-9a-fk-or]", ""); // Убираем цветовые коды для проверки
        if (cleanMessageForCheck.startsWith("[Псич]") || cleanMessageForCheck.startsWith("<Псич>")) {
            // Это сообщение от бота, сохраняем в историю, но не обрабатываем триггер
            plugin.getStorageService().addToHistory(chatId, "Псич", message);
            return; // Не обрабатываем сообщения от самого бота
        }

        // Сохраняем сообщение в историю
        plugin.getStorageService().addToHistory(chatId, playerName, message);
        plugin.getStorageService().trackUser(chatId, playerId, playerName);

        // Проверяем, не в муте ли чат
        if (plugin.getStorageService().isMuted(chatId)) {
            return;
        }

        String cleanText = message.toLowerCase();
        boolean hasTrigger = triggerPattern.matcher(cleanText).find();

        // Проверяем, нужен ли поиск в интернете (ключевые слова: найди, поищи, гугл)
        boolean requiresSearch = hasTrigger && (cleanText.contains("найди") ||
                cleanText.contains("поищи"));

        // Если прямое обращение - сразу обрабатываем асинхронно
        if (hasTrigger) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Прямое обращение к боту от " + playerName + ": " + message);
                if (requiresSearch) {
                    plugin.getLogger().info("[DEBUG] Обнаружен запрос на поиск в интернете");
                }
            }
            // Отвечаем асинхронно
            final boolean finalRequiresSearch = requiresSearch;
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        processMessage(chatId, playerId, playerName, message, true, finalRequiresSearch);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Ошибка обработки сообщения: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }.runTaskAsynchronously(plugin);
        } else {
            // Спонтанный ответ с шансом - проверяем асинхронно
            if (message.length() >= plugin.getConfigManager().getMinMessageLength() &&
                    random.nextDouble() < plugin.getConfigManager().getSpontaneousChanceMessage()) {

                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[DEBUG] Проверка спонтанного ответа для сообщения от " + playerName);
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
                                                + " на спонтанное сообщение");
                            }

                            if (shouldAnswer) {
                                // Обрабатываем сообщение
                                processMessage(chatId, playerId, playerName, message, false);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Ошибка при проверке shouldAnswer: " + e.getMessage());
                            if (plugin.getConfigManager().isDebug()) {
                                e.printStackTrace();
                            }
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
        }
    }

    private void processMessage(String chatId, String playerId, String playerName, String message,
            boolean isDirectlyCalled) {
        processMessage(chatId, playerId, playerName, message, isDirectlyCalled, false);
    }

    /**
     * Отправляет части сообщения с задержками через runTaskLater (не блокирует
     * главный поток)
     * 
     * @param chatId       ID чата
     * @param fullResponse Полный ответ для отправки
     * @param startIndex   Индекс символа, с которого начинать отправку
     * @param partNumber   Номер части (начинается с 1)
     */
    private void sendMessageParts(String chatId, String fullResponse, int startIndex, int partNumber) {
        final int maxLength = 250;
        final int maxParts = 2; // Максимум 2 сообщения

        if (partNumber > maxParts || startIndex >= fullResponse.length()) {
            // Все части отправлены, сохраняем в историю
            plugin.getStorageService().addToHistory(chatId, "Псич", fullResponse);
            return;
        }

        // Берем следующую часть
        String remaining = fullResponse.substring(startIndex);
        String part;
        int nextStartIndex;

        if (remaining.length() <= maxLength) {
            part = remaining;
            nextStartIndex = fullResponse.length(); // Все отправлено
        } else {
            // Ищем последний пробел перед лимитом для красивого разрыва
            int breakPoint = maxLength;
            int lastSpace = remaining.lastIndexOf(' ', breakPoint);
            if (lastSpace > maxLength * 0.7) { // Если пробел не слишком далеко
                breakPoint = lastSpace;
            }
            part = remaining.substring(0, breakPoint);
            nextStartIndex = startIndex + breakPoint;
        }

        // Формируем сообщение
        String colorCode = plugin.getConfigManager().getNameColorCode();
        String messageToSend;
        if (plugin.getConfigManager().isSendAsPlayer()) {
            // Формат игрока: <Псич> сообщение
            if (partNumber == 1) {
                messageToSend = colorCode + "<Псич> §f" + part;
            } else {
                messageToSend = colorCode + "<Псич> §7(продолжение) §f" + part;
            }
        } else {
            // Формат консоли: [Псич] сообщение
            if (partNumber == 1) {
                messageToSend = colorCode + "[Псич] §f" + part;
            } else {
                messageToSend = colorCode + "[Псич] §7(продолжение) §f" + part;
            }
        }

        // Отправляем сообщение в игру
        plugin.getServer().broadcastMessage(messageToSend);

        // Отправляем сообщение в Discord через Webhook (если настроено)
        if (plugin.getConfigManager().isDiscordEnabled()
                && !plugin.getConfigManager().getDiscordWebhookUrl().isEmpty()) {
            // Убираем цветовые коды Minecraft для Discord
            String cleanMessage = messageToSend.replaceAll("§[0-9a-fk-or]", "");
            // Убираем префикс "<Псич>" или "[Псич]" из сообщения для Discord
            final String discordMessage = cleanMessage.replaceAll("^\\s*[<\\[]Псич[>\\]]\\s*", "")
                    .trim();
            // Отправляем в Discord асинхронно
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                DiscordWebhookIntegration.sendMessage(
                        plugin.getConfigManager().getDiscordWebhookUrl(),
                        discordMessage,
                        plugin.getConfigManager().getDiscordUsername(),
                        plugin.getConfigManager().getDiscordAvatarUrl());
            });
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[DEBUG] Отправлено сообщение #" + partNumber + " (длина: "
                    + part.length() + " символов)");
        }

        // Если есть еще части для отправки, планируем следующую с задержкой 2 тика
        // (100мс)
        if (nextStartIndex < fullResponse.length() && partNumber < maxParts) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMessageParts(chatId, fullResponse, nextStartIndex, partNumber + 1);
                }
            }.runTaskLater(plugin, 2); // 2 тика = 100мс при 20 TPS
        } else {
            // Все части отправлены, сохраняем в историю
            plugin.getStorageService().addToHistory(chatId, "Псич", fullResponse);
        }
    }

    private void processMessage(String chatId, String playerId, String playerName, String message,
            boolean isDirectlyCalled, boolean requiresSearch) {
        try {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger()
                        .info("[DEBUG] Обработка сообщения от " + playerName + " (ID: " + playerId + "): " + message);
                plugin.getLogger()
                        .info("[DEBUG] Режим: " + (isDirectlyCalled ? "прямое обращение" : "спонтанный ответ"));
            }

            // Получаем профиль игрока
            StorageService.UserProfile userProfile = plugin.getStorageService().getProfile(chatId, playerId);

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Профиль игрока: репутация=" + userProfile.getRelationship()
                        + ", факты="
                        + (userProfile.getFacts() != null
                                ? userProfile.getFacts().substring(0, Math.min(50, userProfile.getFacts().length()))
                                : "нет"));
            }

            // Получаем историю
            List<StorageService.ChatMessage> history = plugin.getStorageService().getHistory(chatId);

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Размер истории: " + history.size() + " сообщений");
            }

            // Если сообщение только триггер, добавляем контекст
            String processedMessage = message;
            if (message.trim().equalsIgnoreCase(plugin.getConfigManager().getTrigger()) ||
                    message.trim().equalsIgnoreCase("psych")) {
                processedMessage = "Псич, привет! Что нужно?";
            }

            // Генерируем ответ
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Запрос к AI для генерации ответа...");
            }

            String response = plugin.getAIManager().getResponse(
                    history,
                    processedMessage,
                    playerName,
                    userProfile,
                    !isDirectlyCalled,
                    requiresSearch);

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Получен ответ от AI (длина: "
                        + (response != null ? response.length() : 0) + " символов)");
            }

            if (response == null || response.trim().isEmpty()) {
                plugin.getLogger().warning("AI вернул пустой ответ");
                return;
            }

            // Разбиваем ответ на части по 256 символов (лимит Minecraft)
            // Ограничение: максимум 2 сообщения подряд (510 символов)
            final String fullResponse = response;
            final double delaySeconds = plugin.getConfigManager().getResponseDelay();

            // Вычисляем задержку в тиках (1 секунда = 20 тиков при 20 TPS)
            long delayTicks = delaySeconds > 0 ? (long) (delaySeconds * 20) : 0;

            // Используем runTaskLater для задержки вместо Thread.sleep
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMessageParts(chatId, fullResponse, 0, 1);
                }
            }.runTaskLater(plugin, delayTicks);

            // Анализируем репутацию игрока
            List<StorageService.ChatMessage> recentHistory = history.size() > 5
                    ? history.subList(history.size() - 5, history.size())
                    : history;

            String contextForAnalysis = recentHistory.stream()
                    .map(m -> m.getRole() + ": " + m.getText())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            // Асинхронный анализ репутации
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("[DEBUG] Начинаем анализ репутации для " + playerName);
                        }
                        StorageService.UserProfile updated = plugin.getAIManager().analyzeUserImmediate(
                                contextForAnalysis,
                                userProfile);
                        if (updated != null) {
                            if (plugin.getConfigManager().isDebug()) {
                                int oldRep = userProfile.getRelationship();
                                int newRep = updated.getRelationship();
                                plugin.getLogger().info(
                                        "[DEBUG] Репутация " + playerName + " изменена: " + oldRep + " -> " + newRep);
                            }
                            plugin.getStorageService().updateProfile(chatId, playerId, updated);
                            plugin.getStorageService().save();
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Ошибка анализа репутации: " + e.getMessage());
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().warning("[DEBUG] Ошибка анализа репутации: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }.runTaskAsynchronously(plugin);

        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка AI: " + e.getMessage());
            e.printStackTrace();

            // Отправляем сообщение об ошибке
            final String errorColorCode = plugin.getConfigManager().getNameColorCode();
            final String errorPrefix = plugin.getConfigManager().isSendAsPlayer() ? "<Псич>" : "[Псич]";
            final String errorMessage = errorColorCode + errorPrefix
                    + " §7У меня шестеренки встали. Какая-то дичь в коде";
            new BukkitRunnable() {
                @Override
                public void run() {
                    plugin.getServer().broadcastMessage(errorMessage);
                }
            }.runTask(plugin);
        }
    }
}
