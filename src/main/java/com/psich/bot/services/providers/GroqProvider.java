package com.psich.bot.services.providers;

import java.util.List;

public class GroqProvider extends OpenAICompatibleProvider {

    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    public GroqProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        this(keys, false, config);
    }

    public GroqProvider(List<String> keys, boolean useSimpleModel, com.psich.bot.utils.ConfigManager config) {
        super("Groq" + (useSimpleModel ? "-Simple" : ""), API_URL,
                useSimpleModel ? config.getGroqSimpleModel() : config.getGroqModel(),
                keys, true, config);
    }

    @Override
    public boolean supportsVision() {
        return true;
    }
}
