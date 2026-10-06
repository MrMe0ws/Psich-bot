package com.psich.bot.services.providers;

import java.util.List;

public class GeminiProvider extends GoogleProvider {

    public GeminiProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        super("Gemini", config.getGeminiModel(), keys, true, config);
    }
}
