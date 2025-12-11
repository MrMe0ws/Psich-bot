package com.psich.bot.utils;

import com.psich.bot.PsichBot;
import okhttp3.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

public class HttpClientFactory {
    
    /**
     * Создает OkHttpClient с настройками прокси из конфига
     */
    public static OkHttpClient createClient(ConfigManager config) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
        
        // Настраиваем прокси если включен
        if (config.isProxyEnabled() && config.getProxyHost() != null && !config.getProxyHost().isEmpty()) {
            try {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, 
                    new InetSocketAddress(config.getProxyHost(), config.getProxyPort()));
                builder.proxy(proxy);
                
                // Если есть авторизация прокси
                if (config.getProxyUser() != null && !config.getProxyUser().isEmpty()) {
                    String credentials = Credentials.basic(config.getProxyUser(), 
                        config.getProxyPassword() != null ? config.getProxyPassword() : "");
                    builder.proxyAuthenticator((route, response) -> {
                        return response.request().newBuilder()
                            .header("Proxy-Authorization", credentials)
                            .build();
                    });
                }
                
                if (config.isDebug()) {
                    JavaPlugin.getPlugin(PsichBot.class).getLogger()
                        .info("[DEBUG] Используется прокси: " + config.getProxyHost() + ":" + config.getProxyPort());
                }
            } catch (Exception e) {
                // Если ошибка настройки прокси - используем без прокси
                JavaPlugin.getPlugin(PsichBot.class).getLogger()
                    .warning("Ошибка настройки прокси: " + e.getMessage() + ". Используется прямое подключение.");
            }
        }
        
        return builder.build();
    }
}

