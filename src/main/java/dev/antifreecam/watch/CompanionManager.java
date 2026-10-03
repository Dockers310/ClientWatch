package dev.antifreecam.watch;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.manager.ProfileManager;
import dev.antifreecam.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handshake with the optional ClientWatch Fabric companion.
 * The companion reports its currently loaded Fabric mods and SHA-512 where a real jar path is available.
 */
public final class CompanionManager implements PluginMessageListener, Listener {
    public static final String REPORT_CHANNEL = "clientwatch:mods";
    public static final String REQUEST_CHANNEL = "clientwatch:request";

    private final AntiFreecam plugin;
    private final Map<UUID, String> nonces = new ConcurrentHashMap<>();

    public CompanionManager(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, REPORT_CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, REQUEST_CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, REPORT_CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, REQUEST_CHANNEL);
        nonces.clear();
    }

    public boolean has(Player p) {
        ProfileManager.Profile profile = plugin.profiles().get(p.getUniqueId());
        return profile != null && profile.companion;
    }

    public void requestRefresh(Player player) {
        if (player == null || !player.isOnline()) return;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        nonces.put(player.getUniqueId(), nonce);
        sendRequest(player, nonce, 1);
    }

    /**
     * Отправляет один и тот же запрос до трёх раз. Используется тот же nonce,
     * поэтому запоздалый ответ от предыдущей попытки не становится «чужим».
     */
    private void sendRequest(Player player, String nonce, int attempt) {
        if (player == null || !player.isOnline()) return;
        String current = nonces.get(player.getUniqueId());
        if (!nonce.equals(current)) return;

        byte[] raw = nonce.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length + 5);
        writeVarInt(out, raw.length);
        out.writeBytes(raw);

        try {
            player.sendPluginMessage(plugin, REQUEST_CHANNEL, out.toByteArray());
            if (attempt == 1) {
                plugin.getLogger().info(player.getName() + ": отправлен запрос ClientWatch Companion.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning(player.getName() + ": не удалось отправить запрос ClientWatch Companion: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        if (attempt < 3) {
            SchedulerUtil.onEntityLater(plugin, player, 20L, () -> sendRequest(player, nonce, attempt + 1));
        } else {
            SchedulerUtil.onEntityLater(plugin, player, 20L, () -> {
                if (nonce.equals(nonces.get(player.getUniqueId()))) {
                    nonces.remove(player.getUniqueId(), nonce);
                    plugin.getLogger().warning(player.getName()
                            + ": ClientWatch Companion не прислал ответ после 3 запросов.");
                }
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        nonces.remove(p.getUniqueId());
        if (!plugin.getConfig().getBoolean("companion.enabled", true)) return;
        long delay = Math.max(20L, plugin.getConfig().getLong("companion.request-delay-ticks", 40L));
        SchedulerUtil.onEntityLater(plugin, p, delay, () -> requestRefresh(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nonces.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!REPORT_CHANNEL.equals(channel) || player == null || message == null) return;
        if (!plugin.getConfig().getBoolean("companion.enabled", true)) return;
        if (message.length > 1024 * 1024) {
            plugin.getLogger().warning("Слишком большой ClientWatch companion пакет от " + player.getName());
            return;
        }

        String json = readVarIntString(message);
        if (json == null) return;
        try {
            Map<String, Object> root = MiniJson.object(json);
            String nonce = string(root.get("nonce"));
            String expected = nonces.get(player.getUniqueId());
            if (expected == null || !expected.equals(nonce)) {
                return;
            }

            String version = string(root.get("companionVersion"));
            String launcher = string(root.get("launcher"));
            String launcherConfidence = string(root.get("launcherConfidence"));
            String minecraftVersion = string(root.get("minecraftVersion"));
            String fabricLoaderVersion = string(root.get("fabricLoaderVersion"));
            List<ProfileManager.CompanionMod> mods = parseMods(root.get("mods"));
            if (mods.size() > Math.max(1, plugin.getConfig().getInt("companion.max-mods", 500))) {
                plugin.getLogger().warning("Слишком много модов в companion-пакете от " + player.getName());
                return;
            }

            nonces.remove(player.getUniqueId());
            plugin.profiles().applyCompanionReport(player, new ProfileManager.CompanionReport(
                    version, launcher, launcherConfidence, minecraftVersion, fabricLoaderVersion, mods));
        } catch (Exception e) {
            plugin.getLogger().warning("Некорректный ClientWatch companion пакет от "
                    + player.getName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ProfileManager.CompanionMod> parseMods(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        java.util.ArrayList<ProfileManager.CompanionMod> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String id = string(map.get("id"));
            String name = string(map.get("name"));
            String version = string(map.get("version"));
            String sha512 = string(map.get("sha512"));
            String sha1 = string(map.get("sha1"));
            if (id.isBlank() && name.isBlank()) continue;
            if (id.length() > 128 || name.length() > 256 || version.length() > 128 || sha512.length() > 128 || sha1.length() > 64) continue;
            out.add(new ProfileManager.CompanionMod(
                    id.isBlank() ? name : id,
                    name.isBlank() ? id : name,
                    version,
                    sha512,
                    sha1));
        }
        return out;
    }

    private static String readVarIntString(byte[] data) {
        try {
            int numRead = 0;
            int result = 0;
            int read;
            do {
                if (numRead >= data.length) return null;
                read = data[numRead++] & 0xFF;
                result |= (read & 0x7F) << (7 * (numRead - 1));
                if (numRead > 5) return null;
            } while ((read & 0x80) != 0);
            if (result < 0 || result > data.length - numRead) return null;
            return new String(data, numRead, result, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeVarInt(java.io.ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write((value & 127) | 128);
            value >>>= 7;
        }
        out.write(value);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
