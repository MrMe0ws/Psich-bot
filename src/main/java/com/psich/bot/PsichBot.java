package com.psich.bot;

import com.psich.bot.commands.PsichCommand;
import com.psich.bot.integrations.DiscordWebhookIntegration;
import com.psich.bot.listeners.ChatListener;
import com.psich.bot.listeners.GameEventListener;
import com.psich.bot.services.AIManager;
import com.psich.bot.services.StorageService;
import com.psich.bot.utils.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

public class PsichBot extends JavaPlugin {
    
    private static PsichBot instance;
    private ConfigManager configManager;
    private StorageService storageService;
    private AIManager aiManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Сохраняем конфиг по умолчанию
        saveDefaultConfig();
        
        // Инициализируем компоненты
        configManager = new ConfigManager(this);
        storageService = new StorageService(this);
        aiManager = new AIManager(configManager);
        
        // Регистрируем слушателей
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GameEventListener(this), this);
        
        // Регистрируем команды
        getCommand("psich").setExecutor(new PsichCommand(this));
        
        // Инициализируем интеграцию с Discord Webhook
        DiscordWebhookIntegration.initialize(this);
        
        getLogger().info("Плагин PsichBot успешно загружен!");
    }
    
    @Override
    public void onDisable() {
        if (storageService != null) {
            storageService.forceSave();
        }
        getLogger().info("Плагин PsichBot выгружен!");
    }
    
    public static PsichBot getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public StorageService getStorageService() {
        return storageService;
    }
    
    public AIManager getAIManager() {
        return aiManager;
    }
    
    public void reload() {
        reloadConfig();
        configManager.reload();
        // Обновляем промпт в Prompts
        com.psich.bot.utils.Prompts.setSystemPrompt(configManager.getSystemPrompt());
        getLogger().info("Конфигурация перезагружена!");
    }
}

