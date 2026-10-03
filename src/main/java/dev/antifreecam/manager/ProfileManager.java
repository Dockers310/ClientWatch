package dev.antifreecam.manager;

import com.github.retrooper.packetevents.PacketEvents;
import dev.antifreecam.AntiFreecam;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.util.LauncherUtil;
import dev.antifreecam.util.SchedulerUtil;
import dev.antifreecam.watch.ModProject;
import dev.antifreecam.watch.WatchStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Профили игроков: какой клиент (brand) и какие моды замечены при заходах,
 * плюс общий список «отмеченных» модов, на которые надо обращать внимание.
 *
 * Источники модов:
 *  - fp:<название>  - мод из config.yml, найденный табличкой (клавиши/переводы);
 *  - ch:<namespace> - мод, зарегистрировавший сетевой канал (клиент присылает их список при входе).
 */
public final class ProfileManager {

    public static final class PMod {
        public final String id;
        public volatile String display;
        public volatile boolean cheat;
        public volatile String detail = "";
        /** SHA-512 локального JAR, если ClientWatch companion смог получить реальный файл. */
        public volatile String sha512 = "";
        public volatile String sha1 = "";
        /** Время первого обнаружения мода в миллисекундах Unix time. */
        public volatile long first;
        /** Время последнего обнаружения мода в миллисекундах Unix time. */
        public volatile long last;
        /** номер захода, в котором мод видели в последний раз */
        public volatile int session;

        PMod(String id, String display) {
            this.id = id;
            this.display = display;
        }

        public boolean isChannel() { return id.startsWith("ch:"); }
        public boolean isCompanion() { return id.startsWith("cw:"); }
    }

    public record CompanionMod(String id, String name, String version, String sha512, String sha1) {}

    public record CompanionReport(String companionVersion, String launcher, String launcherConfidence,
                                  String minecraftVersion, String fabricLoaderVersion, List<CompanionMod> mods) {}

    public static final class SessionRec {
        public volatile long time;
        public volatile String brand = "";
        /** человекочитаемое название лаунчера/клиента, угаданное по brand (см. LauncherUtil), может быть пустым */
        public volatile String launcher = "";
        public volatile String companionLauncher = "";
        public volatile String companionLauncherConfidence = "";
        public volatile String companionMinecraftVersion = "";
        public volatile String companionFabricLoaderVersion = "";
        /** версия клиента (например "1.21.4"), определяется по протоколу через PacketEvents */
        public volatile String version = "";
        /** ok / timeout / пусто = нет данных */
        public volatile String check = "";
        public final Set<String> mods = ConcurrentHashMap.newKeySet();
    }

    public static final class Profile {
        public final UUID uuid;
        public volatile String name;
        public volatile String brand = "";
        public volatile String launcher = "";
        public volatile String version = "";
        public volatile int session;
        public volatile long lastJoin;
        public volatile String checkState = "";
        public volatile long lastCheck;
        /** ClientWatch companion сообщил полный список модов для последнего захода. */
        public volatile boolean companion;
        public volatile String companionVersion = "";
        public volatile String companionLauncher = "";
        public volatile String companionLauncherConfidence = "";
        public volatile String companionMinecraftVersion = "";
        public volatile String companionFabricLoaderVersion = "";
        public volatile long companionAt;
        public final Map<String, PMod> mods = new ConcurrentHashMap<>();
        public final List<SessionRec> history = new CopyOnWriteArrayList<>();

        Profile(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        /** Мод был замечен в последнем заходе (белый), иначе - только раньше (серый). */
        public boolean present(PMod m) { return m.session == session; }
    }

    private final AntiFreecam plugin;
    private final File file;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    /** ник (lower) -> каналы, которые клиент зарегистрировал; заполняется из сетевого потока */
    private final Map<String, Set<String>> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Player> online = new ConcurrentHashMap<>();
    private final Map<UUID, String> onlineDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, UUID> onlineNames = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public ProfileManager(AntiFreecam plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "profiles.yml");
        load();
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("profile.enabled", true); }

    // ---------------- доступ ----------------

    public List<Profile> all() { return new ArrayList<>(profiles.values()); }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Profile p : profiles.values()) out.add(p.name);
        return out;
    }

    public static boolean hasCheatNow(Profile p) {
        for (PMod m : p.mods.values()) if (m.cheat && p.present(m)) return true;
        return false;
    }

    /** Текст статуса проверки (MiniMessage) или null, если данных нет. */
    public static String checkText(String state) {
        return switch (state == null ? "" : state) {
            case "ok" -> "<green>пройдена";
            case "timeout" -> "<red>клиент не ответил";
            default -> null;
        };
    }

    public Profile get(UUID id) { return profiles.get(id); }

    public List<Player> onlinePlayers() {
        return new ArrayList<>(online.values());
    }

    public Player onlinePlayer(UUID id) {
        return online.get(id);
    }

    public boolean isOnline(UUID id) {
        return online.containsKey(id);
    }

    public String onlineName(UUID id) {
        return onlineDisplayNames.get(id);
    }

    public Player onlinePlayerExact(String name) {
        if (name == null) return null;
        UUID id = onlineNames.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : online.get(id);
    }

    public UUID onlineUuidExact(String name) {
        if (name == null) return null;
        return onlineNames.get(name.toLowerCase(Locale.ROOT));
    }

    public List<String> onlineNames() {
        return new ArrayList<>(onlineDisplayNames.values());
    }

    public Map<UUID, String> onlineEntries() {
        return Map.copyOf(onlineDisplayNames);
    }

    public Profile find(String name) {
        for (Profile p : profiles.values()) if (p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    public boolean matches(UUID id, String q) {
        Profile p = profiles.get(id);
        if (p == null) return false;
        if (p.brand.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (PMod m : p.mods.values()) if (m.display.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    public Set<String> watchedIds() { return Collections.unmodifiableSet(watched); }

    public boolean isWatched(String id) { return watched.contains(id); }

    public boolean toggleWatch(String id) {
        boolean now;
        if (watched.remove(id)) now = false;
        else { watched.add(id); now = true; }
        dirty.set(true);
        flush();
        return now;
    }

    public String displayOf(String id) {
        for (Profile p : profiles.values()) {
            PMod m = p.mods.get(id);
            if (m != null) return m.display;
        }
        return id.substring(id.indexOf(':') + 1);
    }

    /** У кого из игроков есть (или был) этот мод. */
    public List<Profile> withMod(String id) {
        List<Profile> out = new ArrayList<>();
        for (Profile p : profiles.values()) if (p.mods.containsKey(id)) out.add(p);
        out.sort(Comparator.comparing((Profile p) -> !isOnline(p.uuid)).thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    // ---------------- события ----------------

    public void onJoin(Player pl) {
        UUID id = pl.getUniqueId();
        String name = pl.getName();
        online.put(id, pl);
        onlineDisplayNames.put(id, name);
        onlineNames.put(name.toLowerCase(Locale.ROOT), id);
        plugin.access().onlineJoin(pl);

        if (!enabled()) return;
        Profile pr = profiles.computeIfAbsent(id, u -> new Profile(u, name));
        pr.name = name;
        pr.session++;
        startNewSession(pr);
        pr.lastJoin = System.currentTimeMillis();
        pr.checkState = "";
        String brand = pl.getClientBrandName();
        pr.brand = brand == null ? "" : brand;
        String launcher = LauncherUtil.identify(pr.brand);
        pr.launcher = launcher == null ? "" : launcher;
        String version = clientVersionOf(pl);
        pr.version = version == null ? "" : version;

        SessionRec rec = new SessionRec();
        rec.time = pr.lastJoin;
        rec.brand = pr.brand;
        rec.launcher = pr.launcher;
        rec.version = pr.version;
        rec.companionLauncher = "";
        rec.companionLauncherConfidence = "";
        rec.companionMinecraftVersion = "";
        rec.companionFabricLoaderVersion = "";
        pr.history.add(rec);
        int max = Math.max(1, plugin.getConfig().getInt("profile.max-history", 30));
        while (pr.history.size() > max) pr.history.remove(0);
        dirty.set(true);

        applyChannels(pl, pr);
    }

    /** Версия клиента ("1.21.4" и т.п.) по протоколу подключения - это данные хендшейка, их нельзя подделать так же
     *  просто, как client brand, поэтому в отличие от лаунчера это довольно надёжное определение. */
    private static String clientVersionOf(Player pl) {
        try {
            var cv = PacketEvents.getAPI().getPlayerManager().getClientVersion(pl);
            return cv == null ? null : cv.getReleaseName();
        } catch (Exception e) {
            return null;
        }
    }

    public void onQuit(Player pl) {
        UUID id = pl.getUniqueId();
        String name = pl.getName();
        online.remove(id, pl);
        onlineDisplayNames.remove(id, name);
        onlineNames.remove(name.toLowerCase(Locale.ROOT), id);
        plugin.access().onlineQuit(pl);
        channels.remove(name.toLowerCase(Locale.ROOT));
        dirty.set(true);
    }

    /** Итог проверки табличкой: ok - клиент ответил, timeout - не ответил после всех попыток. */
    public void checkResult(Player pl, String state) {
        Profile pr = profiles.get(pl.getUniqueId());
        if (pr == null) return;
        pr.checkState = state;
        pr.lastCheck = System.currentTimeMillis();
        if (!pr.history.isEmpty()) pr.history.get(pr.history.size() - 1).check = state;
        dirty.set(true);
    }

    /**
     * Полный отчёт от ClientWatch companion. Он не заменяет обычную проверку:
     * tab/key/lang detection продолжает работать параллельно.
     */
    public void applyCompanionReport(Player pl, CompanionReport report) {
        if (!enabled()) return;
        Profile pr = profiles.get(pl.getUniqueId());
        if (pr == null) return;

        pr.companion = true;
        pr.companionVersion = report.companionVersion() == null ? "" : report.companionVersion();
        pr.companionLauncher = report.launcher() == null ? "" : report.launcher();
        pr.companionLauncherConfidence = report.launcherConfidence() == null ? "" : report.launcherConfidence();
        pr.companionMinecraftVersion = report.minecraftVersion() == null ? "" : report.minecraftVersion();
        pr.companionFabricLoaderVersion = report.fabricLoaderVersion() == null ? "" : report.fabricLoaderVersion();
        pr.companionAt = System.currentTimeMillis();
        if (!pr.history.isEmpty()) {
            SessionRec current = pr.history.get(pr.history.size() - 1);
            current.companionLauncher = pr.companionLauncher;
            current.companionLauncherConfidence = pr.companionLauncherConfidence;
            current.companionMinecraftVersion = pr.companionMinecraftVersion;
            current.companionFabricLoaderVersion = pr.companionFabricLoaderVersion;
        }

        // Если сервер попросил обновление дважды за один заход, старые companion-записи
        // переводим в предыдущий слот сессии, чтобы список точно соответствовал последнему отчёту.
        for (PMod m : pr.mods.values()) {
            if (m.isCompanion() && m.session == pr.session) m.session = pr.session - 1;
        }

        int limit = Math.max(1, plugin.getConfig().getInt("companion.max-mods", 500));
        int count = 0;
        List<CompanionMod> mods = report.mods() == null ? List.of() : report.mods();
        for (CompanionMod cm : mods) {
            if (count++ >= limit) break;
            String id = cm.id() == null || cm.id().isBlank() ? cm.name() : cm.id();
            String display = cm.name() == null || cm.name().isBlank() ? id : cm.name();
            String profileId = "cw:" + id;
            PMod m = pr.mods.get(profileId);
            if (m == null) {
                m = new PMod(profileId, display);
                m.first = System.currentTimeMillis();
                pr.mods.put(profileId, m);
            }
            m.display = display;
            m.detail = cm.version() == null ? "" : cm.version();
            m.sha512 = cm.sha512() == null ? "" : cm.sha512().toLowerCase(Locale.ROOT);
            m.sha1 = cm.sha1() == null ? "" : cm.sha1().toLowerCase(Locale.ROOT);
            m.last = System.currentTimeMillis();
            m.session = pr.session;

            ModProject.Classification c = plugin.watch().classify(id, display, m.sha512, m.sha1);
            m.cheat = c.status() == WatchStatus.FORBIDDEN;
            if (!pr.history.isEmpty()) pr.history.get(pr.history.size() - 1).mods.add(display);
        }

        dirty.set(true);
        plugin.getLogger().info(pl.getName() + ": ClientWatch companion прислал " + Math.min(count, limit)
                + " модов" + (pr.companionVersion.isBlank() ? "" : " v" + pr.companionVersion)
                + (pr.companionLauncher.isBlank() ? "" : "; лаунчер: " + pr.companionLauncher));
    }

    /** Применяет текущую классификацию watchlist к уже полученным данным без нового захода. */
    public void refreshCompanionClassification() {
        if (!enabled()) return;
        for (Profile pr : profiles.values()) {
            if (!pr.companion) continue;
            for (PMod m : pr.mods.values()) {
                if (!m.isCompanion() || !pr.present(m)) continue;
                String id = m.id.substring(3);
                ModProject.Classification c = plugin.watch().classify(id, m.display, m.sha512, m.sha1);
                m.cheat = c.status() == WatchStatus.FORBIDDEN;
            }
        }
        dirty.set(true);
        flush();
    }

    /** Сбрасывает факт companion при начале нового захода, но старые записи не удаляет. */
    private void startNewSession(Profile pr) {
        pr.companion = false;
        pr.companionVersion = "";
        pr.companionAt = 0L;
    }

    /** Результат проверки табличкой: найденные моды из config.yml (название -> клавиша). */
    public void applyDetection(Player pl, Map<String, String> mods) {
        if (!enabled() || mods.isEmpty()) return;
        Profile pr = profiles.get(pl.getUniqueId());
        if (pr == null) return; // заход ещё не обработан
        for (var e : mods.entrySet()) {
            touch(pl, pr, "fp:" + e.getKey(), e.getKey(), plugin.detection().isCheat(e.getKey()), e.getValue());
        }
    }

    /**
     * Из сетевого потока: любое пользовательское сообщение клиента. Список каналов (…:register) разбираем,
     * остальные каналы, которыми клиент пользуется, тоже считаем - по ним видно моды с сетью.
     */
    public void channelPacket(String userName, String channel, byte[] data) {
        if (userName == null || channel == null) return;
        if (channel.toLowerCase(Locale.ROOT).startsWith("clientwatch:")) return;
        Set<String> set = channels.computeIfAbsent(userName.toLowerCase(Locale.ROOT), k -> ConcurrentHashMap.newKeySet());
        boolean added = false;
        if (channel.equals("minecraft:register") || channel.endsWith(":register")) {
            if (data == null) return;
            for (String part : new String(data, StandardCharsets.UTF_8).split("\0")) {
                String id = part.strip();
                if (!id.isEmpty() && id.length() < 128) added |= set.add(id);
            }
        } else if (!channel.endsWith(":unregister") && !channel.equals("minecraft:brand") && channel.length() < 128) {
            added = set.add(channel.strip());
        }
        if (added) {
            Player pl = onlinePlayerExact(userName);
            if (pl != null) {
                SchedulerUtil.onEntity(plugin, pl, () -> {
                    Profile pr = profiles.get(pl.getUniqueId());
                    if (pr != null) applyChannels(pl, pr);
                });
            }
        }
    }

    private void applyChannels(Player pl, Profile pr) {
        Set<String> ids = channels.get(pl.getName().toLowerCase(Locale.ROOT));
        if (ids == null) return;
        Map<String, List<String>> byNs = new TreeMap<>();
        for (String id : ids) {
            if (isServerChannel(id)) continue; // канал самого сервера/его плагинов - это не мод игрока
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
            if (!ns.isEmpty() && !ignored(ns)) byNs.computeIfAbsent(ns, k -> new ArrayList<>()).add(id);
        }
        for (var e : byNs.entrySet()) {
            List<String> list = new ArrayList<>(e.getValue());
            Collections.sort(list);
            String detail = String.join(", ", list.subList(0, Math.min(3, list.size())));
            touch(pl, pr, "ch:" + e.getKey(), knownName(e.getKey()), false, detail);
        }
    }

    /**
     * Канал зарегистрирован каким-то плагином на самом сервере (AuthMe, BungeeCord-мост,
     * прокси-плагины и т.п.) - клиент лишь отвечает на него по протоколу, это не значит,
     * что у игрока стоит мод. Раньше такие каналы (authme, bungeecord, sr, vgcore и подобные)
     * ошибочно попадали в список модов даже у чистого клиента без единого мода.
     */
    private boolean isServerChannel(String id) {
        var m = Bukkit.getMessenger();
        if (m.getOutgoingChannels().contains(id) || m.getIncomingChannels().contains(id)) return true;
        // Paper/Spigot на проводе переводит легаси-канал "BungeeCord" в современный "bungeecord:main"
        if (id.equalsIgnoreCase("bungeecord:main")
                && (m.getOutgoingChannels().contains("BungeeCord") || m.getIncomingChannels().contains("BungeeCord"))) {
            return true;
        }
        return false;
    }

    private boolean ignored(String ns) {
        for (String p : plugin.getConfig().getStringList("profile.ignore-namespaces")) {
            if (p.endsWith("*") ? ns.startsWith(p.substring(0, p.length() - 1)) : ns.equals(p)) return true;
        }
        return false;
    }

    private String knownName(String ns) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("profile.known-channels");
        String name = sec == null ? null : sec.getString(ns);
        return name == null || name.isBlank() ? ns : name;
    }

    private void touch(Player pl, Profile pr, String id, String display, boolean cheat, String detail) {
        long now = System.currentTimeMillis();
        PMod m = pr.mods.get(id);
        boolean firstThisSession = m == null || m.session != pr.session;
        if (m == null) {
            m = new PMod(id, display);
            m.first = now;
            pr.mods.put(id, m);
        }
        m.display = display;
        m.cheat = cheat;
        if (detail != null && !detail.isEmpty()) m.detail = detail;
        m.last = now;
        m.session = pr.session;
        if (!pr.history.isEmpty()) pr.history.get(pr.history.size() - 1).mods.add(display);
        dirty.set(true);
        if (firstThisSession && watched.contains(id)) alertWatched(pl, display);
    }

    private void alertWatched(Player pl, String display) {
        Component msg = Component.text("[CW ⭐] ", NamedTextColor.GOLD)
                .append(Component.text(pl.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" → отмеченный мод: ", NamedTextColor.GRAY))
                .append(Component.text(display, NamedTextColor.WHITE))
                .clickEvent(ClickEvent.runCommand("/afc mods " + pl.getName()));
        for (Player staff : onlinePlayers()) {
            SchedulerUtil.onEntity(plugin, staff, () -> {
                if (plugin.access().receives(staff)) staff.sendMessage(msg);
            });
        }
    }

    /** Данные из старого списка срабатываний: моды считаются «заходил раньше» (серые), пока их не увидят снова. */
    public Profile ensure(DetectionManager.Flag f) {
        Profile p = profiles.get(f.uuid);
        if (p == null) {
            p = new Profile(f.uuid, f.name);
            profiles.put(f.uuid, p);
        }
        for (var e : f.mods.entrySet()) {
            String id = "fp:" + e.getKey();
            if (p.mods.containsKey(id)) continue;
            PMod m = new PMod(id, e.getKey());
            m.cheat = plugin.detection().isCheat(e.getKey());
            m.detail = e.getValue();
            m.first = f.firstSeen;
            m.last = f.lastSeen;
            m.session = -1;
            p.mods.put(id, m);
            dirty.set(true);
        }
        return p;
    }

    public void seedFromFlags(Collection<DetectionManager.Flag> flags) {
        for (DetectionManager.Flag f : flags) ensure(f);
    }

    /**
     * Полный сброс статистики: профили, моды и история заходов удаляются.
     * Игроки, которые сейчас в сети, сразу получают чистый профиль (как при первом заходе).
     * Отметки «⭐ внимание» на модах - это настройка, а не статистика, поэтому остаются.
     *
     * @return сколько профилей было удалено
     */
    public int resetAll() {
        int n = profiles.size();
        profiles.clear();
        dirty.set(true);
        for (Player p : onlinePlayers()) SchedulerUtil.onEntity(plugin, p, () -> onJoin(p));
        flush();
        return n;
    }

    // ---------------- сохранение ----------------

    public void flush() {
        if (!dirty.compareAndSet(true, false)) return;
        trimClean();
        save();
    }

    /** Профили без модов хранятся только для последних N игроков (profile.keep-clean). */
    private void trimClean() {
        int keep = Math.max(0, plugin.getConfig().getInt("profile.keep-clean", 300));
        List<Profile> clean = new ArrayList<>();
        for (Profile p : profiles.values()) if (p.mods.isEmpty() && !isOnline(p.uuid)) clean.add(p);
        clean.sort(Comparator.comparingLong((Profile p) -> p.lastJoin).reversed());
        for (int i = keep; i < clean.size(); i++) profiles.remove(clean.get(i).uuid);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        watched.addAll(y.getStringList("watched"));
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Profile p = new Profile(uuid, sec.getString(key + ".name", key));
                p.brand = sec.getString(key + ".brand", "");
                p.launcher = sec.getString(key + ".launcher", "");
                p.version = sec.getString(key + ".version", "");
                p.session = sec.getInt(key + ".session");
                p.lastJoin = sec.getLong(key + ".last-join");
                p.checkState = sec.getString(key + ".check", "");
                p.lastCheck = sec.getLong(key + ".last-check");
                p.companion = sec.getBoolean(key + ".companion", false);
                p.companionVersion = sec.getString(key + ".companion-version", "");
                p.companionAt = sec.getLong(key + ".companion-at", 0L);
                p.companionLauncher = sec.getString(key + ".companion-launcher", "");
                p.companionLauncherConfidence = sec.getString(key + ".companion-launcher-confidence", "");
                p.companionMinecraftVersion = sec.getString(key + ".companion-minecraft", "");
                p.companionFabricLoaderVersion = sec.getString(key + ".companion-fabric-loader", "");
                for (Map<?, ?> m : sec.getMapList(key + ".mods")) {
                    PMod pm = new PMod(str(m.get("id")), str(m.get("display")));
                    pm.cheat = Boolean.TRUE.equals(m.get("cheat"));
                    pm.detail = str(m.get("detail"));
                    pm.sha512 = str(m.get("sha512"));
                    pm.sha1 = str(m.get("sha1"));
                    pm.first = num(m.get("first"));
                    pm.last = num(m.get("last"));
                    pm.session = (int) num(m.get("session"));
                    if (!pm.id.isEmpty()) p.mods.put(pm.id, pm);
                }
                for (Map<?, ?> h : sec.getMapList(key + ".history")) {
                    SessionRec r = new SessionRec();
                    r.time = num(h.get("time"));
                    r.brand = str(h.get("brand"));
                    r.launcher = str(h.get("launcher"));
                    r.companionLauncher = str(h.get("companion-launcher"));
                    r.companionLauncherConfidence = str(h.get("companion-launcher-confidence"));
                    r.companionMinecraftVersion = str(h.get("companion-minecraft"));
                    r.companionFabricLoaderVersion = str(h.get("companion-fabric-loader"));
                    r.version = str(h.get("version"));
                    r.check = str(h.get("check"));
                    if (h.get("mods") instanceof List<?> l) for (Object o : l) r.mods.add(str(o));
                    p.history.add(r);
                }
                profiles.put(uuid, p);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("watched", new ArrayList<>(watched));
        for (Profile p : profiles.values()) {
            String b = "players." + p.uuid;
            y.set(b + ".name", p.name);
            y.set(b + ".brand", p.brand);
            y.set(b + ".launcher", p.launcher);
            y.set(b + ".version", p.version);
            y.set(b + ".session", p.session);
            y.set(b + ".last-join", p.lastJoin);
            y.set(b + ".check", p.checkState);
            y.set(b + ".last-check", p.lastCheck);
            y.set(b + ".companion", p.companion);
            y.set(b + ".companion-version", p.companionVersion);
            y.set(b + ".companion-at", p.companionAt);
            y.set(b + ".companion-launcher", p.companionLauncher);
            y.set(b + ".companion-launcher-confidence", p.companionLauncherConfidence);
            y.set(b + ".companion-minecraft", p.companionMinecraftVersion);
            y.set(b + ".companion-fabric-loader", p.companionFabricLoaderVersion);
            List<Map<String, Object>> mods = new ArrayList<>();
            for (PMod m : p.mods.values()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", m.id);
                mm.put("display", m.display);
                mm.put("cheat", m.cheat);
                mm.put("detail", m.detail);
                mm.put("sha512", m.sha512);
                mm.put("sha1", m.sha1);
                mm.put("first", m.first);
                mm.put("last", m.last);
                mm.put("session", m.session);
                mods.add(mm);
            }
            y.set(b + ".mods", mods);
            List<Map<String, Object>> hist = new ArrayList<>();
            for (SessionRec r : p.history) {
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("time", r.time);
                hm.put("brand", r.brand);
                hm.put("launcher", r.launcher);
                hm.put("companion-launcher", r.companionLauncher);
                hm.put("companion-launcher-confidence", r.companionLauncherConfidence);
                hm.put("companion-minecraft", r.companionMinecraftVersion);
                hm.put("companion-fabric-loader", r.companionFabricLoaderVersion);
                hm.put("version", r.version);
                hm.put("check", r.check);
                hm.put("mods", new ArrayList<>(r.mods));
                hist.add(hm);
            }
            y.set(b + ".history", hist);
        }
        String data = y.saveToString();
        if (plugin.isEnabled() && !plugin.isStopping()) SchedulerUtil.async(plugin, () -> write(data));
        else write(data);
    }

    private synchronized void write(String data) {
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить profiles.yml: " + e.getMessage());
        }
    }
}
