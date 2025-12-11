package com.psich.bot.listeners;

import com.psich.bot.PsichBot;
import com.psich.bot.integrations.DiscordWebhookIntegration;
import com.psich.bot.services.StorageService;
import org.bukkit.advancement.Advancement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Random;

/**
 * Слушатель игровых событий (смерть, достижения, подключение, отключение)
 */
public class GameEventListener implements Listener {

    private final PsichBot plugin;
    private final Random random = new Random();

    public GameEventListener(PsichBot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getEntity() == null) {
            return;
        }

        String playerName = event.getEntity().getName();
        String playerId = event.getEntity().getUniqueId().toString();
        String chatId = "global";
        String deathMessage = event.getDeathMessage();

        // Проверяем шанс реакции на событие
        if (random.nextDouble() < plugin.getConfigManager().getSpontaneousChanceDeath()) {
            // Шанс выпал, продолжаем обработку
        } else {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Смерть игрока " + playerName + " - шанс не выпал");
            }
            return;
        }

        // НЕ отправляем системные сообщения о событиях в Discord - только ответы бота

        // Проверяем, не заглушен ли чат
        if (plugin.getStorageService().isMuted(chatId)) {
            return;
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[DEBUG] Проверка реакции на смерть игрока " + playerName);
        }

        // Создаем контекст события
        final String eventContext = "Событие: Игрок " + playerName
                + (deathMessage != null ? " - " + deathMessage : " умер");

        // Получаем историю чата для контекста
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
                    String fullContext = historyBlock + "\n" + eventContext;
                    boolean shouldAnswer = plugin.getAIManager().shouldAnswer(fullContext);
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[DEBUG] AI решил " + (shouldAnswer ? "ответить" : "не отвечать")
                                + " на смерть игрока");
                    }

                    if (shouldAnswer) {
                        // Обрабатываем событие
                        processEvent(chatId, playerId, playerName, eventContext, "death");
                    }
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Ошибка при проверке shouldAnswer для события смерти: " + e.getMessage());
                    if (plugin.getConfigManager().isDebug()) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        if (event.getPlayer() == null || event.getAdvancement() == null) {
            return;
        }

        String playerName = event.getPlayer().getName();
        String playerId = event.getPlayer().getUniqueId().toString();
        String chatId = "global";
        Advancement advancement = event.getAdvancement();

        String advancementKey = advancement.getKey().getKey();

        // Пропускаем root достижения и рецепты (recipes/*)
        if (advancementKey.contains("root") || advancementKey.startsWith("recipes/")) {
            return;
        }

        // Получаем название достижения
        String advancementName = advancementKey;
        if (advancement.getDisplay() != null && advancement.getDisplay().title() != null) {
            advancementName = advancement.getDisplay().title().toString();
        }

        // Проверяем шанс реакции на событие
        if (random.nextDouble() < plugin.getConfigManager().getSpontaneousChanceAdvancement()) {
            // Шанс выпал, продолжаем обработку
        } else {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Достижение игрока " + playerName + " - шанс не выпал");
            }
            return;
        }

        // НЕ отправляем системные сообщения о событиях в Discord - только ответы бота

        // Проверяем, не заглушен ли чат
        if (plugin.getStorageService().isMuted(chatId)) {
            return;
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[DEBUG] Проверка реакции на достижение игрока " + playerName);
        }

        // Создаем контекст события
        final String eventContext = "Событие: Игрок " + playerName + " получил достижение: " + advancementName;

        // Получаем историю чата для контекста
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
                    String fullContext = historyBlock + "\n" + eventContext;
                    boolean shouldAnswer = plugin.getAIManager().shouldAnswer(fullContext);
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[DEBUG] AI решил " + (shouldAnswer ? "ответить" : "не отвечать")
                                + " на достижение игрока");
                    }

                    if (shouldAnswer) {
                        // Обрабатываем событие
                        processEvent(chatId, playerId, playerName, eventContext, "advancement");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Ошибка при проверке shouldAnswer для достижения: " + e.getMessage());
                    if (plugin.getConfigManager().isDebug()) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        String playerName = event.getPlayer().getName();
        String playerId = event.getPlayer().getUniqueId().toString();
        String chatId = "global";

        // Проверяем, первый ли раз игрок на сервере
        boolean isFirstJoin = !event.getPlayer().hasPlayedBefore();

        // Проверяем шанс реакции на событие
        if (random.nextDouble() < plugin.getConfigManager().getSpontaneousChanceJoinQuit()) {
            // Шанс выпал, продолжаем обработку
        } else {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Подключение игрока " + playerName + " - шанс не выпал");
            }
            return;
        }

        // НЕ отправляем системные сообщения о событиях в Discord - только ответы бота

        // Проверяем, не заглушен ли чат
        if (plugin.getStorageService().isMuted(chatId)) {
            return;
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[DEBUG] Проверка реакции на подключение игрока " + playerName
                    + (isFirstJoin ? " (первый раз)" : ""));
        }

        // Создаем контекст события
        final String eventContext = isFirstJoin
                ? "Событие: Игрок " + playerName + " впервые присоединился к серверу"
                : "Событие: Игрок " + playerName + " присоединился к серверу";

        // Получаем историю чата для контекста
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
                    String fullContext = historyBlock + "\n" + eventContext;
                    boolean shouldAnswer = plugin.getAIManager().shouldAnswer(fullContext);
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[DEBUG] AI решил " + (shouldAnswer ? "ответить" : "не отвечать")
                                + " на подключение игрока");
                    }

                    if (shouldAnswer) {
                        // Обрабатываем событие
                        processEvent(chatId, playerId, playerName, eventContext, "join");
                    }
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Ошибка при проверке shouldAnswer для события подключения: " + e.getMessage());
                    if (plugin.getConfigManager().isDebug()) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        String playerName = event.getPlayer().getName();
        String playerId = event.getPlayer().getUniqueId().toString();
        String chatId = "global";

        // Проверяем шанс реакции на событие
        if (random.nextDouble() < plugin.getConfigManager().getSpontaneousChanceJoinQuit()) {
            // Шанс выпал, продолжаем обработку
        } else {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] Отключение игрока " + playerName + " - шанс не выпал");
            }
            return;
        }

        // НЕ отправляем системные сообщения о событиях в Discord - только ответы бота

        // Проверяем, не заглушен ли чат
        if (plugin.getStorageService().isMuted(chatId)) {
            return;
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[DEBUG] Проверка реакции на отключение игрока " + playerName);
        }

        // Создаем контекст события
        final String eventContext = "Событие: Игрок " + playerName + " отключился от сервера";

        // Получаем историю чата для контекста
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
                    String fullContext = historyBlock + "\n" + eventContext;
                    boolean shouldAnswer = plugin.getAIManager().shouldAnswer(fullContext);
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[DEBUG] AI решил " + (shouldAnswer ? "ответить" : "не отвечать")
                                + " на отключение игрока");
                    }

                    if (shouldAnswer) {
                        // Обрабатываем событие
                        processEvent(chatId, playerId, playerName, eventContext, "quit");
                    }
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Ошибка при проверке shouldAnswer для события отключения: " + e.getMessage());
                    if (plugin.getConfigManager().isDebug()) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Обрабатывает игровое событие и генерирует ответ
     */
    private void processEvent(String chatId, String playerId, String playerName, String eventContext,
            String eventType) {
        try {
            // Получаем историю чата
            List<StorageService.ChatMessage> history = plugin.getStorageService().getHistory(chatId);
            int contextSize = plugin.getConfigManager().getContextSize();
            int historySize = Math.min(contextSize, history.size());
            List<StorageService.ChatMessage> recentHistory = history.subList(
                    Math.max(0, history.size() - historySize),
                    history.size());

            // Формируем контекст
            String contextStr = recentHistory.stream()
                    .map(m -> m.getRole() + ": " + m.getText())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            // Добавляем информацию о событии в контекст
            contextStr += "\n" + eventContext;

            // Получаем профиль игрока
            StorageService.UserProfile profile = plugin.getStorageService().getProfile(chatId, playerId);

            // Генерируем ответ через AI
            String response = plugin.getAIManager().getResponse(
                    recentHistory,
                    eventContext, // Используем событие как "сообщение"
                    playerName,
                    profile,
                    true); // Это спонтанный ответ

            if (response == null || response.trim().isEmpty()) {
                plugin.getLogger().warning("AI вернул пустой ответ на событие");
                return;
            }

            // Отправляем ответ в игру и Discord
            sendResponse(chatId, response);

            // Сохраняем событие и ответ в историю
            plugin.getStorageService().addToHistory(chatId, playerName, eventContext);
            plugin.getStorageService().addToHistory(chatId, "Псич", response);

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка обработки события: " + e.getMessage());
            if (plugin.getConfigManager().isDebug()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Отправляет ответ бота в игру и Discord
     */
    private void sendResponse(String chatId, String response) {
        // Разбиваем ответ на части по 256 символов (лимит Minecraft)
        // Ограничение: максимум 2 сообщения подряд (510 символов)
        final int maxLength = 250;
        final String fullResponse = response;
        final double delaySeconds = plugin.getConfigManager().getResponseDelay();

        new BukkitRunnable() {
            @Override
            public void run() {
                // Задержка перед отправкой для более естественного поведения
                if (delaySeconds > 0) {
                    try {
                        Thread.sleep((long) (delaySeconds * 1000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                String remaining = fullResponse;
                int partNumber = 1;
                int maxParts = 2; // Максимум 2 сообщения

                while (!remaining.isEmpty() && partNumber <= maxParts) {
                    String part;
                    if (remaining.length() <= maxLength) {
                        part = remaining;
                        remaining = "";
                    } else {
                        int breakPoint = maxLength;
                        int lastSpace = remaining.lastIndexOf(' ', breakPoint);
                        if (lastSpace > maxLength * 0.7) {
                            breakPoint = lastSpace;
                        }
                        part = remaining.substring(0, breakPoint);
                        remaining = remaining.substring(breakPoint).trim();
                    }

                    // Формируем сообщение
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

                    // Отправляем в игру
                    plugin.getServer().broadcastMessage(messageToSend);

                    // Отправляем в Discord
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
}
