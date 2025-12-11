package com.psich.bot.services.providers;

import com.psich.bot.utils.ConfigManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseProvider {
    
    protected String name;
    protected List<String> keys;
    protected AtomicInteger currentKeyIndex;
    protected ConfigManager config;
    
    public BaseProvider(String name, List<String> keys, ConfigManager config) {
        this.name = name;
        this.keys = keys;
        this.currentKeyIndex = new AtomicInteger(0);
        this.config = config;
    }
    
    public String getName() {
        return name;
    }
    
    public boolean isAvailable() {
        return keys != null && !keys.isEmpty();
    }
    
    protected String getCurrentKey() {
        if (!isAvailable()) {
            return null;
        }
        return keys.get(currentKeyIndex.get() % keys.size());
    }
    
    protected boolean rotateKey() {
        if (!isAvailable()) {
            return false;
        }
        int oldIndex = currentKeyIndex.get();
        int newIndex = (oldIndex + 1) % keys.size();
        currentKeyIndex.set(newIndex);
        return newIndex != oldIndex;
    }
    
    public abstract boolean supportsVision();
    public abstract boolean supportsSearch();
    public abstract String generate(String prompt, GenerateOptions options) throws Exception;
    
    public static class GenerateOptions {
        private String systemPrompt;
        private Integer maxTokens = 2500;
        private Double temperature = 0.9;
        private Boolean expectJson = false;
        
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        
        public Boolean getExpectJson() { return expectJson; }
        public void setExpectJson(Boolean expectJson) { this.expectJson = expectJson; }
    }
}

