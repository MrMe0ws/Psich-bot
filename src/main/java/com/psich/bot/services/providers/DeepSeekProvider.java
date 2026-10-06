package com.psich.bot.services.providers;

import java.util.List;

public class DeepSeekProvider extends OpenAICompatibleProvider {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    public DeepSeekProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        super("DeepSeek", API_URL, config.getDeepseekModel(), keys, false, config);
    }
}
