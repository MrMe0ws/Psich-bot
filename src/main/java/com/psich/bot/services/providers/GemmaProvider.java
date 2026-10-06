package com.psich.bot.services.providers;

import java.util.List;

public class GemmaProvider extends GoogleProvider {

    public GemmaProvider(List<String> keys, com.psich.bot.utils.ConfigManager config) {
        super("Gemma", config.getGemmaModel(), keys, false, config);
    }
}
