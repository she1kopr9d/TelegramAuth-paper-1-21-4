package com.example.telegramtest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import java.time.Duration;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {
    private final File dataFile;
    private final Map<String, Long> playerTelegramIds; // minecraft username -> telegram chat id
    private final Map<Long, String> telegramPlayerIds; // telegram chat id -> minecraft username
    private final Map<UUID, Boolean> pendingAuth; // Ожидающие подтверждения
    private final Map<String, Long> ipSessions; // ip -> expiry time
    private final Map<Long, String> lastBannedIps; // telegram id -> last banned ip
    private final Gson gson;
    private final MainPlugin plugin;

    private final long sessionDuration;

    public AuthManager(MainPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "auth_data.json");
        this.playerTelegramIds = new ConcurrentHashMap<>();
        this.telegramPlayerIds = new ConcurrentHashMap<>();
        this.pendingAuth = new ConcurrentHashMap<>();
        this.ipSessions = new ConcurrentHashMap<>();
        this.lastBannedIps = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.sessionDuration = plugin.getConfig().getLong("session-duration", 30) * 60 * 1000; // конвертируем минуты в миллисекунды
        loadData();
    }

    public void registerPlayer(String minecraftUsername, long telegramChatId) {
        if (telegramPlayerIds.containsKey(telegramChatId)) {
            throw new IllegalStateException("Этот Telegram аккаунт уже привязан к нику: " + 
                telegramPlayerIds.get(telegramChatId));
        }

        playerTelegramIds.put(minecraftUsername.toLowerCase(), telegramChatId);
        telegramPlayerIds.put(telegramChatId, minecraftUsername.toLowerCase());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist add " + minecraftUsername);
        });
        saveData();
    }

    public void unregisterPlayer(long telegramChatId) {
        String username = telegramPlayerIds.get(telegramChatId);
        if (username != null) {
            playerTelegramIds.remove(username);
            telegramPlayerIds.remove(telegramChatId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist remove " + username);
            });
            saveData();
        }
    }

    public String getMinecraftUsername(long telegramChatId) {
        return telegramPlayerIds.get(telegramChatId);
    }

    public boolean isRegistered(String minecraftUsername) {
        return playerTelegramIds.containsKey(minecraftUsername.toLowerCase());
    }

    public Long getTelegramId(String minecraftUsername) {
        return playerTelegramIds.get(minecraftUsername.toLowerCase());
    }

    public void setPendingAuth(UUID playerUuid) {
        pendingAuth.put(playerUuid, false);
    }

    public void confirmAuth(UUID playerUuid) {
        pendingAuth.put(playerUuid, true);
        // Уведомляем игрока о успешной авторизации
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            player.showTitle(Title.title(
                Component.text("Вход подтвержден!", NamedTextColor.GREEN),
                Component.text("Приятной игры!", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(1500), Duration.ofMillis(500))
            ));
        }
    }

    public boolean isAuthConfirmed(UUID playerUuid) {
        return pendingAuth.getOrDefault(playerUuid, false);
    }

    public void removeAuthStatus(UUID playerUuid) {
        pendingAuth.remove(playerUuid);
    }

    public boolean isSessionValid(String ip) {
        if (sessionDuration == 0) return false; // если сессии отключены
        Long expiry = ipSessions.get(ip);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public void createSession(String ip) {
        if (sessionDuration > 0) {
            ipSessions.put(ip, System.currentTimeMillis() + sessionDuration);
        }
    }

    public void invalidateSession(String ip) {
        ipSessions.remove(ip);
    }

    public void saveBannedIp(long telegramId, String ip) {
        lastBannedIps.put(telegramId, ip);
    }

    public String getLastBannedIp(long telegramId) {
        return lastBannedIps.get(telegramId);
    }

    public void clearLastBannedIp(long telegramId) {
        lastBannedIps.remove(telegramId);
    }

    private void loadData() {
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            saveData();
            return;
        }

        try (Reader reader = new FileReader(dataFile)) {
            Type type = new TypeToken<Map<String, Long>>(){}.getType();
            Map<String, Long> data = gson.fromJson(reader, type);
            if (data != null) {
                playerTelegramIds.putAll(data);
                // Также загружаем данные для telegramPlayerIds
                for (Map.Entry<String, Long> entry : data.entrySet()) {
                    telegramPlayerIds.put(entry.getValue(), entry.getKey());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load auth data: " + e.getMessage());
        }
    }

    private void saveData() {
        try (Writer writer = new FileWriter(dataFile)) {
            gson.toJson(playerTelegramIds, writer);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save auth data: " + e.getMessage());
        }
    }
} 