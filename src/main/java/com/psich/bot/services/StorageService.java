package com.psich.bot.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.psich.bot.PsichBot;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StorageService {
    
    private final PsichBot plugin;
    private final Gson gson;
    private final File dataFolder;
    private final File dbFile;
    private final File profilesFile;
    
    // В памяти храним данные
    private Map<String, ChatData> chats = new ConcurrentHashMap<>();
    private Map<String, Map<String, UserProfile>> profiles = new ConcurrentHashMap<>();
    private Set<String> mutedChats = new HashSet<>();
    
    public StorageService(PsichBot plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        this.dbFile = new File(dataFolder, "db.json");
        this.profilesFile = new File(dataFolder, "profiles.json");
        
        // Создаем папки
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        
        load();
    }
    
    public void load() {
        // Загружаем db.json
        if (dbFile.exists()) {
            try {
                String json = new String(Files.readAllBytes(dbFile.toPath()));
                Type type = new TypeToken<Map<String, ChatData>>() {}.getType();
                Map<String, ChatData> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    chats = new ConcurrentHashMap<>(loaded);
                }
                
                // Загружаем muted chats
                mutedChats.clear();
                for (ChatData chat : chats.values()) {
                    if (chat.isMuted()) {
                        mutedChats.add(chat.getChatId());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка загрузки db.json: " + e.getMessage());
                chats = new ConcurrentHashMap<>();
            }
        }
        
        // Загружаем profiles.json
        if (profilesFile.exists()) {
            try {
                String json = new String(Files.readAllBytes(profilesFile.toPath()));
                Type type = new TypeToken<Map<String, Map<String, UserProfile>>>() {}.getType();
                Map<String, Map<String, UserProfile>> loaded = gson.fromJson(json, type);
                if (loaded != null) {
                    profiles = new ConcurrentHashMap<>(loaded);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка загрузки profiles.json: " + e.getMessage());
                profiles = new ConcurrentHashMap<>();
            }
        }
    }
    
    public void save() {
        // Сохраняем db.json
        try {
            String json = gson.toJson(chats);
            Files.write(dbFile.toPath(), json.getBytes());
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка сохранения db.json: " + e.getMessage());
        }
        
        // Сохраняем profiles.json
        try {
            String json = gson.toJson(profiles);
            Files.write(profilesFile.toPath(), json.getBytes());
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка сохранения profiles.json: " + e.getMessage());
        }
    }
    
    public void forceSave() {
        save();
    }
    
    public ChatData getChat(String chatId) {
        return chats.computeIfAbsent(chatId, k -> new ChatData(chatId));
    }
    
    public void addToHistory(String chatId, String sender, String text) {
        ChatData chat = getChat(chatId);
        chat.addMessage(sender, text);
        
        // Ограничиваем размер истории
        if (chat.getHistory().size() > plugin.getConfigManager().getContextSize()) {
            chat.getHistory().remove(0);
        }
    }
    
    public List<ChatMessage> getHistory(String chatId) {
        return getChat(chatId).getHistory();
    }
    
    public void trackUser(String chatId, String userId, String username) {
        ChatData chat = getChat(chatId);
        chat.addUser(userId, username);
    }
    
    public UserProfile getProfile(String chatId, String userId) {
        if (!profiles.containsKey(chatId)) {
            profiles.put(chatId, new ConcurrentHashMap<>());
        }
        return profiles.get(chatId).computeIfAbsent(userId, k -> new UserProfile());
    }
    
    public void updateProfile(String chatId, String userId, UserProfile profile) {
        if (!profiles.containsKey(chatId)) {
            profiles.put(chatId, new ConcurrentHashMap<>());
        }
        profiles.get(chatId).put(userId, profile);
    }
    
    public void bulkUpdateProfiles(String chatId, Map<String, UserProfile> updates) {
        if (!profiles.containsKey(chatId)) {
            profiles.put(chatId, new ConcurrentHashMap<>());
        }
        profiles.get(chatId).putAll(updates);
    }
    
    public boolean isMuted(String chatId) {
        return mutedChats.contains(chatId);
    }
    
    public void toggleMute(String chatId) {
        if (mutedChats.contains(chatId)) {
            mutedChats.remove(chatId);
            getChat(chatId).setMuted(false);
        } else {
            mutedChats.add(chatId);
            getChat(chatId).setMuted(true);
        }
        save();
    }
    
    public void clearHistory(String chatId) {
        ChatData chat = getChat(chatId);
        chat.getHistory().clear();
        save();
    }
    
    // Внутренние классы для хранения данных
    public static class ChatData {
        private String chatId;
        private List<ChatMessage> history = new ArrayList<>();
        private Map<String, String> users = new HashMap<>();
        private boolean muted = false;
        
        public ChatData(String chatId) {
            this.chatId = chatId;
        }
        
        public void addMessage(String sender, String text) {
            history.add(new ChatMessage(sender, text));
        }
        
        public void addUser(String userId, String username) {
            users.put(userId, username);
        }
        
        // Getters and setters
        public String getChatId() { return chatId; }
        public List<ChatMessage> getHistory() { return history; }
        public Map<String, String> getUsers() { return users; }
        public boolean isMuted() { return muted; }
        public void setMuted(boolean muted) { this.muted = muted; }
    }
    
    public static class ChatMessage {
        private String role;
        private String text;
        
        public ChatMessage(String role, String text) {
            this.role = role;
            this.text = text;
        }
        
        public String getRole() { return role; }
        public String getText() { return text; }
    }
    
    public static class UserProfile {
        private String realName;
        private String facts = "";
        private String attitude = "Нейтральное";
        private int relationship = 50;
        
        public UserProfile() {}
        
        // Getters and setters
        public String getRealName() { return realName; }
        public void setRealName(String realName) { this.realName = realName; }
        
        public String getFacts() { return facts; }
        public void setFacts(String facts) { this.facts = facts; }
        
        public String getAttitude() { return attitude; }
        public void setAttitude(String attitude) { this.attitude = attitude; }
        
        public int getRelationship() { return relationship; }
        public void setRelationship(int relationship) { this.relationship = relationship; }
    }
}

