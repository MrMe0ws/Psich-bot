package com.psich.bot.commands;

import com.psich.bot.PsichBot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PsichCommand implements CommandExecutor {
    
    private final PsichBot plugin;
    
    public PsichCommand(PsichBot plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e[Псич] §7Используй: /psich help");
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "help":
                return handleHelp(sender);
            case "mute":
                return handleMute(sender);
            case "reload":
                return handleReload(sender);
            case "reset":
                return handleReset(sender);
            default:
                sender.sendMessage("§e[Псич] §7Неизвестная команда. Используй: /psich help");
                return true;
        }
    }
    
    private boolean handleHelp(CommandSender sender) {
        sender.sendMessage("§6=== Помощь по боту Псич ===");
        sender.sendMessage("§e/psich help §7- Показать эту справку");
        sender.sendMessage("");
        sender.sendMessage("§6Как общаться с ботом:");
        sender.sendMessage("§7• Напиши в чат: §eПсич, [твое сообщение]");
        sender.sendMessage("");
        if (sender.hasPermission("psich.admin")) {
            sender.sendMessage("§cАдминистративные команды:");
            sender.sendMessage("§e/psich mute §7- Включить/выключить режим тишины");
            sender.sendMessage("§e/psich reload §7- Перезагрузить конфигурацию");
            sender.sendMessage("§e/psich reset §7- Сбросить историю чата");
        }
        return true;
    }
    
    private boolean handleMute(CommandSender sender) {
        if (!sender.hasPermission("psich.admin")) {
            sender.sendMessage("§c[Псич] §7У вас нет прав на использование этой команды.");
            return true;
        }
        
        String chatId = "global";
        plugin.getStorageService().toggleMute(chatId);
        boolean isMuted = plugin.getStorageService().isMuted(chatId);
        
        sender.sendMessage("§e[Псич] §7" + (isMuted ? "Окей молчу" : "Я тут"));
        return true;
    }
    
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("psich.admin")) {
            sender.sendMessage("§c[Псич] §7У вас нет прав на использование этой команды.");
            return true;
        }
        
        plugin.reload();
        sender.sendMessage("§e[Псич] §7Конфигурация перезагружена!");
        return true;
    }
    
    private boolean handleReset(CommandSender sender) {
        if (!sender.hasPermission("psich.admin")) {
            sender.sendMessage("§c[Псич] §7У вас нет прав на использование этой команды.");
            return true;
        }
        
        String chatId = "global";
        plugin.getStorageService().clearHistory(chatId);
        sender.sendMessage("§e[Псич] §7Окей, всё забыл, ну было и было");
        return true;
    }
}

