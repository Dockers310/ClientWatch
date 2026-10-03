package dev.antifreecam;

import dev.antifreecam.command.AfcCommand;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.gui.GuiListener;
import dev.antifreecam.gui.SearchPrompt;
import dev.antifreecam.manager.AccessManager;
import dev.antifreecam.manager.ProfileManager;
import dev.antifreecam.manager.PumpkinManager;
import dev.antifreecam.util.SchedulerUtil;
import dev.antifreecam.watch.CompanionManager;
import dev.antifreecam.watch.WatchRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class AntiFreecam extends JavaPlugin {

    /** Версия формата config.yml: при повышении раздел mods обновляется автоматически. */
    private static final int CONFIG_VERSION = 10;

    private DetectionManager detection;
    private AccessManager access;
    private ProfileManager profiles;
    private SearchPrompt search;
    private PumpkinManager pumpkin;
    private WatchRegistry watch;
    private CompanionManager companion;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        stopping = false;
        saveDefaultConfig();
        migrateConfig();
        access = new AccessManager(this);
        watch = new WatchRegistry(this);
        profiles = new ProfileManager(this);
        companion = new CompanionManager(this);
        companion.start();
        detection = new DetectionManager(this);
        detection.start();
        profiles.seedFromFlags(detection.flags());
        SchedulerUtil.globalTimer(this, 1200L, 1200L, profiles::flush);
        pumpkin = new PumpkinManager(this);
        pumpkin.start();
        getServer().getPluginManager().registerEvents(pumpkin, this);
        search = new SearchPrompt(this);
        getServer().getPluginManager().registerEvents(search, this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        AfcCommand cmd = new AfcCommand(this);
        var command = getCommand("afc");
        if (command == null) {
            getLogger().severe("Команда /afc не зарегистрирована в plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(cmd);
        command.setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (detection != null) detection.stop();
        if (companion != null) companion.stop();
        if (profiles != null) profiles.flush();
        if (watch != null) watch.shutdown();
    }

    public boolean isStopping() {
        return stopping;
    }

    /** Добавляет новые ключи из встроенного config.yml, не затирая существующие настройки сервера. */
    private void migrateConfig() {
        int fileVersion = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"))
                .getInt("config-version", 0);

        // В старых версиях config.yml миграция могла заменять весь раздел mods.
        // Начиная с 2.2 это больше не делается: copyDefaults только добавляет отсутствующие поля.
        getConfig().options().copyDefaults(true);
        if (!getConfig().isSet("detection.join-spread-seconds")
                || fileVersion < CONFIG_VERSION) {
            // Старое значение 10 секунд в 2.1.5 было слишком долгим. Сам механизм также
            // ограничен 5 секундами в DetectionManager, поэтому здесь лишь приводим явный default.
            getConfig().set("detection.join-spread-seconds",
                    Math.min(5L, Math.max(1L, getConfig().getLong("detection.join-spread-seconds", 5L))));
        }
        getConfig().set("config-version", CONFIG_VERSION);
        saveConfig();

        if (fileVersion > 0 && fileVersion < CONFIG_VERSION) {
            getLogger().info("ClientWatch config.yml обновлён без удаления существующих настроек ("
                    + fileVersion + " -> " + CONFIG_VERSION + ").");
        }
    }

    /** Возвращает раздел mods к стандартному (из jar), остальное в конфиге не трогает. */
    public void resetMods() {
        try (Reader r = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            ConfigurationSection def = YamlConfiguration.loadConfiguration(r).getConfigurationSection("mods");
            if (def == null) return;
            getConfig().set("mods", null);
            for (String id : def.getKeys(false)) {
                ConfigurationSection m = def.getConfigurationSection(id);
                if (m == null) continue;
                for (String field : m.getKeys(false)) { // копируем все поля мода: name, keys, require, forbid, group, category...
                    getConfig().set("mods." + id + "." + field, m.get(field));
                }
            }
            saveConfig();
        } catch (IOException e) {
            getLogger().warning("Не удалось обновить список модов: " + e.getMessage());
        }
    }

    /**
     * Сброс всей накопленной статистики: срабатывания Freecam + профили игроков (моды, клиенты, история заходов).
     * Не трогает: config.yml, доступы, исключения, тыкву и отметки модов.
     *
     * @return {удалено срабатываний, удалено профилей}
     */
    public int[] resetStats() {
        int flags = detection.resetFlags();
        int profs = profiles.resetAll();
        return new int[]{flags, profs};
    }

    public DetectionManager detection() { return detection; }

    public AccessManager access() { return access; }

    public ProfileManager profiles() { return profiles; }

    public SearchPrompt search() { return search; }

    public PumpkinManager pumpkin() { return pumpkin; }

    public WatchRegistry watch() { return watch; }

    public CompanionManager companion() { return companion; }

    /** Переклассифицирует уже полученные companion-отчёты после изменения watchlist. */
    public void refreshWatchClassifications() {
        if (profiles != null) profiles.refreshCompanionClassification();
    }

    /** Игрок из списка срабатываний в исключениях (по нику или по праву antifreecam.bypass, если онлайн). */
    public boolean isExemptFlag(DetectionManager.Flag f) {
        return isExemptName(f.name) || detection.isBypassOnline(f.uuid);
    }

    public boolean isExempt(Player p) {
        return p.hasPermission("antifreecam.bypass") || isExemptName(p.getName());
    }

    public boolean isExemptName(String name) {
        return getConfig().getStringList("exempt").stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    public void addExempt(String name) {
        List<String> list = getConfig().getStringList("exempt");
        if (list.stream().noneMatch(n -> n.equalsIgnoreCase(name))) list.add(name);
        getConfig().set("exempt", list);
        saveConfig();
    }

    public void removeExempt(String name) {
        List<String> list = getConfig().getStringList("exempt");
        list.removeIf(n -> n.equalsIgnoreCase(name));
        getConfig().set("exempt", list);
        saveConfig();
    }
}
