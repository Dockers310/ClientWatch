package dev.antifreecam.detection;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.util.SchedulerUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиенту отправляется фейковая табличка с компонентами keybind. Клиент сам их
 * резолвит (мод есть - вернёт название клавиши, иначе - сырой ключ) и присылает
 * результат в UPDATE_SIGN. Ключи проверяются пачками по 4 (по одной табличке на пачку).
 */
public final class DetectionManager implements Listener {

    public static final class Flag {
        public final UUID uuid;
        public volatile String name;
        /** мод -> как клиент назвал клавишу (например F4) */
        public final Map<String, String> mods = new ConcurrentHashMap<>();
        public final long firstSeen;
        public volatile long lastSeen;
        /** когда игрок вышел с сервера (0 = сейчас в сети / неизвестно) */
        public volatile long left;

        Flag(UUID uuid, String name, Map<String, String> mods, long first, long last, long left) {
            this.uuid = uuid;
            this.name = name;
            this.mods.putAll(mods);
            this.firstSeen = first;
            this.lastSeen = last;
            this.left = left;
        }
    }

    /** wire - что уходит на табличку; raw - что вернёт клиент, если ключа нет; expected - допустимые тексты (пусто = любой). */
    private record Spec(String wire, String raw, Set<String> expected) {
        static Spec parse(String spec) {
            boolean lang = spec.startsWith("lang:");
            String body = lang ? spec.substring(5) : spec;
            Set<String> expected = new HashSet<>();
            if (lang) {
                int eq = body.indexOf('=');
                if (eq > 0) {
                    for (String alt : body.substring(eq + 1).split("\\|")) {
                        if (!alt.isBlank()) expected.add(alt.strip());
                    }
                    body = body.substring(0, eq).strip();
                }
            }
            return new Spec((lang ? "lang:" : "") + body, body, expected);
        }
    }

    /** group - взаимоисключающая группа модов (или null); order - порядок в конфиге. */
    private record ModMeta(String group, int order, boolean plain) {}

    private static final class Session {
        final Map<String, List<String>> keyMods; // спецификация -> моды, которым она принадлежит
        final Map<String, List<String>> requires; // мод -> спецификации, которые обязаны совпасть
        final Map<String, List<String>> forbids;  // мод -> спецификации, при совпадении которых мод исключается
        final Map<String, ModMeta> meta;          // мод -> группа и порядок
        final Map<String, Spec> specs = new LinkedHashMap<>();
        final Deque<List<String>> batches = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final Map<String, String> resp = new HashMap<>();   // wire-ключ -> ответ клиента
        final Map<String, String> actual = new LinkedHashMap<>(); // спецификация -> что вернул клиент (если ключ есть)
        final Set<String> hits = new HashSet<>();           // спецификации, которые совпали
        final Map<String, String> mods = new LinkedHashMap<>();
        final Map<String, String> rejected = new LinkedHashMap<>(); // мод -> почему отброшен (для /afc test)
        /** мод -> (спецификация -> как клиент её назвал) */
        final Map<String, Map<String, String>> matched = new LinkedHashMap<>();
        final CommandSender probe; // не null = режим /afc probe / test
        /** сколько пачек было изначально (для расчёта задержки при "растянутой" проверке) */
        int totalBatches;
        /** сколько пачек реально получили ответ от клиента */
        volatile int completedBatches;
        /** задержка между пачками в тиках; 0 = слать сразу одну за другой (как раньше) */
        long batchDelayTicks;
        /** true - это фоновая проверка при заходе: можно и нужно ждать, пока игрок не освободится (см. busy()) */
        boolean spread;
        /** отложенный переход к следующей пачке/повтору, чтобы его можно было отменить при перепроверке/выходе */
        volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduled;

        Session(Map<String, List<String>> keyMods, Map<String, List<String>> requires,
                Map<String, List<String>> forbids, Map<String, ModMeta> meta, CommandSender probe) {
            this.keyMods = keyMods;
            this.requires = requires;
            this.forbids = forbids;
            this.meta = meta;
            this.probe = probe;
            LinkedHashSet<String> wires = new LinkedHashSet<>();
            for (String spec : keyMods.keySet()) {
                Spec sp = Spec.parse(spec);
                specs.put(spec, sp);
                wires.add(sp.wire());
            }
            // условия исключения тоже нужно спросить у клиента (мода они не "засчитывают")
            for (List<String> fl : forbids.values()) {
                for (String spec : fl) {
                    if (specs.containsKey(spec)) continue;
                    Spec sp = Spec.parse(spec);
                    specs.put(spec, sp);
                    wires.add(sp.wire());
                }
            }
            List<String> keys = new ArrayList<>(wires);
            for (int i = 0; i < keys.size(); i += 4) {
                batches.add(new ArrayList<>(keys.subList(i, Math.min(i + 4, keys.size()))));
            }
            totalBatches = batches.size();
        }
    }

    private record Pending(Session session, Vector3i pos, Location loc, BlockData original,
                           List<String> keys, Player player, io.papermc.paper.threadedregions.scheduler.ScheduledTask timeout) {}

    private final AntiFreecam plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Flag> flagged = Collections.synchronizedMap(new LinkedHashMap<>());
    private final PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
        @Override
        public void onPacketReceive(PacketReceiveEvent e) {
            // список сетевых каналов клиента (c:register / minecraft:register) - по нему видно часть модов
            if (e.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
                WrapperConfigClientPluginMessage w = new WrapperConfigClientPluginMessage(e);
                plugin.profiles().channelPacket(e.getUser().getName(), w.getChannelName(), w.getData());
                return;
            }
            if (e.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
                WrapperPlayClientPluginMessage w = new WrapperPlayClientPluginMessage(e);
                plugin.profiles().channelPacket(e.getUser().getName(), w.getChannelName(), w.getData());
                return;
            }
            if (e.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
            UUID id = e.getUser().getUUID();
            if (id == null) return;
            Pending p = pending.get(id);
            if (p == null) return;
            WrapperPlayClientUpdateSign w = new WrapperPlayClientUpdateSign(e);
            if (!w.getBlockPosition().equals(p.pos())) return;
            e.setCancelled(true);
            String[] lines = w.getTextLines();
            Player player = plugin.profiles().onlinePlayer(id);
            if (player != null) {
                SchedulerUtil.onEntity(plugin, player, () -> onResponse(id, lines));
            }
        }
    };
        /** сколько раз подряд клиент не ответил на проверку (для повторных попыток) */
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    /** когда игрок последний раз писал в чат (мс) - чтобы не перебивать его прямо во время набора следующего сообщения */
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    /** растянутые проверки, которые сейчас идут (для индикатора прогресса в меню) */
    private final Map<UUID, Session> activeSpread = new ConcurrentHashMap<>();
    private final Set<UUID> bypassOnline = ConcurrentHashMap.newKeySet();
    private final File flagsFile;
    /** Встроенная база известных модов (не читы, не срабатывания - только профиль игрока). */
    private final YamlConfiguration db;

    public DetectionManager(AntiFreecam plugin) {
        this.plugin = plugin;
        this.flagsFile = new File(plugin.getDataFolder(), "flags.yml");
        YamlConfiguration loaded = new YamlConfiguration();
        try (Reader r = new InputStreamReader(Objects.requireNonNull(plugin.getResource("mods-db.yml")), StandardCharsets.UTF_8)) {
            loaded = YamlConfiguration.loadConfiguration(r);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось загрузить mods-db.yml (база известных модов): " + e.getMessage());
        }
        this.db = loaded;
        loadFlags();
    }

    public void start() {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Автоматической периодической и ручной перепроверки нет: проверка идёт только при входе.
        // Пока игрок просто играет, новых проверок не будет.
    }

    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        pending.values().forEach(p -> {
            if (p.timeout() != null) p.timeout().cancel();
        });
        pending.clear();
        saveFlags();
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        lastChat.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Занят ли игрок прямо сейчас чем-то своим, что нельзя перебивать открытием фейковой таблички:
     * открыл сундук/наковальню/меню (включая меню самого плагина), либо только что написал в чат
     * (скорее всего наберёт ещё одно сообщение). В этом случае табличку лучше отложить, а не слать.
     */
    private boolean busy(Player p) {
        if (p.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) return true;
        Long chat = lastChat.get(p.getUniqueId());
        long graceMs = plugin.getConfig().getLong("detection.chat-grace-ticks", 100) * 50L;
        return chat != null && System.currentTimeMillis() - chat < graceMs;
    }

    /** Проверка группы игроков; force=true оставлен для совместимости, но ручной запуск отключён. */
    public void checkAll() {
        checkAll(false);
    }

    /** force = true: проверить вообще всех онлайн (ручной запуск). */
    public void checkAll(boolean force) {
        if (force) return;
        int i = 0;
        for (Player p : plugin.profiles().onlinePlayers()) {
            plugin.companion().requestRefresh(p);
            long delay = (i++) * 2L;
            SchedulerUtil.onEntityLater(plugin, p, delay, () -> {
                if (!force && p.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) return;
                checkLocal(p, force, false);
            });
        }
    }

    /** Сначала те, кто онлайн, затем по времени последней проверки. */
    public List<Flag> flags() {
        List<Flag> list;
        synchronized (flagged) {
            list = new ArrayList<>(flagged.values());
        }
        list.sort(Comparator
                .comparing((Flag f) -> !plugin.profiles().isOnline(f.uuid))
                .thenComparing(f -> -f.lastSeen));
        return list;
    }

    public Flag findFlag(String name) {
        synchronized (flagged) {
            for (Flag f : flagged.values()) if (f.name.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    public void unflag(UUID id) {
        boolean removed;
        synchronized (flagged) {
            removed = flagged.remove(id) != null;
        }
        if (removed) saveFlags();
    }

    /** Очищает весь список срабатываний Freecam (и flags.yml). @return сколько записей было */
    public int resetFlags() {
        int n;
        synchronized (flagged) {
            n = flagged.size();
            flagged.clear();
        }
        saveFlags();
        return n;
    }

    // ---- хранение срабатываний (переживают рестарт) ----

    private void loadFlags() {
        if (!flagsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(flagsFile);
        ConfigurationSection sec = y.getConfigurationSection("flags");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                List<String> names = sec.getStringList(id + ".mods");
                List<String> keys = sec.getStringList(id + ".keys");
                Map<String, String> mods = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) mods.put(names.get(i), i < keys.size() ? keys.get(i) : "?");
                long left = sec.getLong(id + ".left", 0);
                flagged.put(uuid, new Flag(uuid, sec.getString(id + ".name", id), mods,
                        sec.getLong(id + ".first"), sec.getLong(id + ".last"), left));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private synchronized void saveFlags() {
        YamlConfiguration y = new YamlConfiguration();
        List<Flag> snapshot;
        synchronized (flagged) {
            snapshot = new ArrayList<>(flagged.values());
        }
        snapshot.sort(Comparator.comparing((Flag f) -> !plugin.profiles().isOnline(f.uuid))
                .thenComparing(f -> -f.lastSeen));
        for (Flag f : snapshot) {
            String b = "flags." + f.uuid;
            y.set(b + ".name", f.name);
            y.set(b + ".first", f.firstSeen);
            y.set(b + ".last", f.lastSeen);
            y.set(b + ".left", f.left);
            y.set(b + ".mods", new ArrayList<>(f.mods.keySet()));
            y.set(b + ".keys", new ArrayList<>(f.mods.values()));
        }
        String data = y.saveToString();
        if (plugin.isEnabled() && !plugin.isStopping()) SchedulerUtil.async(plugin, () -> writeFlags(data));
        else writeFlags(data);
    }

    private synchronized void writeFlags(String data) {
        try {
            plugin.getDataFolder().mkdirs();
            java.nio.file.Files.writeString(flagsFile.toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить flags.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        plugin.profiles().onJoin(player);
        if (player.hasPermission("antifreecam.bypass")) bypassOnline.add(player.getUniqueId());
        else bypassOnline.remove(player.getUniqueId());

        Flag known = flagged.get(player.getUniqueId());
        if (known != null) {
            known.left = 0;
            known.name = player.getName();
        }
        if (!plugin.getConfig().getBoolean("detection.check-on-join", true)) return;
        long delay = plugin.getConfig().getLong("detection.join-delay-ticks", 60);
        // растянуто во времени и с оглядкой на игрока: не резко все пачки сразу, а понемногу,
        // и в обход моментов, когда он открыл сундук/меню или только что писал в чат
        SchedulerUtil.onEntityLater(plugin, player, delay, () -> checkLocal(player, true, true));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        plugin.profiles().onQuit(player);
        bypassOnline.remove(id);
        attempts.remove(id);
        lastChat.remove(id);
        Session spread = activeSpread.remove(id);
        if (spread != null && spread.scheduled != null) spread.scheduled.cancel();
        Pending p = pending.remove(id);
        if (p != null && p.timeout() != null) p.timeout().cancel();
        Flag f;
        synchronized (flagged) {
            f = flagged.get(id);
            if (f != null) f.left = System.currentTimeMillis();
        }
        if (f != null) saveFlags();
    }

    // ---- запуск проверок ----

    /** Записи модов: из config.yml (mods:) и, если full, из встроенной базы известных модов (mods-db.yml). */
    private List<ConfigurationSection> modSections(boolean full) {
        List<ConfigurationSection> out = new ArrayList<>();
        addSections(out, plugin.getConfig().getConfigurationSection("mods"));
        if (full && plugin.getConfig().getBoolean("detection.scan-known-mods", true)) {
            addSections(out, db.getConfigurationSection("mods"));
        }
        return out;
    }

    private static void addSections(List<ConfigurationSection> out, ConfigurationSection parent) {
        if (parent == null) return;
        for (String id : parent.getKeys(false)) {
            ConfigurationSection m = parent.getConfigurationSection(id);
            if (m != null) out.add(m);
        }
    }

    private static String nameOf(ConfigurationSection m) {
        return m.getString("name", m.getName());
    }

    private Map<String, List<String>> loadKeyMods(boolean full) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ConfigurationSection m : modSections(full)) {
            String name = nameOf(m);
            List<String> all = new ArrayList<>(m.getStringList("keys"));
            all.addAll(m.getStringList("require")); // require тоже нужно спросить у клиента
            for (String key : all) {
                if (key == null || key.isBlank()) continue;
                List<String> owners = out.computeIfAbsent(key.trim(), x -> new ArrayList<>());
                if (!owners.contains(name)) owners.add(name);
            }
        }
        return out;
    }

    private Map<String, ModMeta> loadMeta(boolean full) {
        Map<String, ModMeta> out = new HashMap<>();
        int i = 0;
        for (ConfigurationSection m : modSections(full)) {
            String group = m.getString("group");
            out.put(nameOf(m), new ModMeta(group == null || group.isBlank() ? null : group.trim(), i++, m.getRoot() == db));
        }
        return out;
    }

    private Map<String, List<String>> loadList(String field, boolean full) {
        Map<String, List<String>> out = new HashMap<>();
        for (ConfigurationSection m : modSections(full)) {
            List<String> list = m.getStringList(field).stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
            if (!list.isEmpty()) out.put(nameOf(m), list);
        }
        return out;
    }

    private Map<String, List<String>> loadForbids(boolean full) { return loadList("forbid", full); }

    private Map<String, List<String>> loadRequires(boolean full) { return loadList("require", full); }

    /** Полная проверка: config.yml + база известных модов. */
    public void check(Player p) {
        check(p, true);
    }

    /** full = false: только моды из config.yml; используется внутренними этапами входной проверки. */
    public void check(Player p, boolean full) {
        check(p, full, false);
    }

    /**
     * spread = true - не слать все пачки одну за другой сразу, а растянуть их на
     * {@code detection.join-spread-seconds} (не более 5 секунд) и в обход моментов,
     * когда игрок открыл сундук/меню или только что писал в чат (см. {@link #busy(Player)}),
     * чтобы не мешать ему играть как обычно. Используется только для автоматической проверки
     * при заходе. В текущей версии ручная перепроверка во время игры отключена.
     */
    public void check(Player p, boolean full, boolean spread) {
        if (!Bukkit.isOwnedByCurrentRegion(p)) {
            SchedulerUtil.onEntity(plugin, p, () -> checkLocal(p, full, spread));
            return;
        }
        checkLocal(p, full, spread);
    }

    private void checkLocal(Player p, boolean full, boolean spread) {
        if (!p.isOnline() || pending.containsKey(p.getUniqueId())) return;
        // исключения по умолчанию тоже проверяются (чтобы быть видными в списке), но без уведомлений
        if (!plugin.getConfig().getBoolean("detection.scan-exempt", true) && plugin.isExempt(p)) return;
        Map<String, List<String>> keyMods = loadKeyMods(full);
        if (keyMods.isEmpty()) return;
        Session session = new Session(keyMods, loadRequires(full), loadForbids(full), loadMeta(full), null);
        session.spread = spread;
        // Проверка при входе не должна растягиваться дольше 10 секунд даже если в старом
        // config.yml остался прежний лимит (например, 120 секунд).
        long configuredWindowSeconds = plugin.getConfig().getLong("detection.join-spread-seconds", 10);
        long windowSeconds = Math.max(0L, Math.min(configuredWindowSeconds, 5L));
        long windowTicks = windowSeconds * 20L;
        if (spread && windowTicks > 0 && session.totalBatches > 1) {
            session.batchDelayTicks = Math.max(1L, windowTicks / session.totalBatches);
        }
        if (spread) activeSpread.put(p.getUniqueId(), session);
        sendBatch(p, session);
    }

    /** Прогресс растянутой проверки при заходе: сколько пачек уже обработано из скольких всего. */
    public record Progress(int done, int total) {}

    /** @return null, если сейчас не идёт растянутая проверка этого игрока (или она уже завершилась) */
    public Progress progress(UUID id) {
        Session s = activeSpread.get(id);
        if (s == null) return null;
        return new Progress(Math.max(0, Math.min(s.completedBatches, s.totalBatches)), s.totalBatches);
    }

    /** Активная входная проверка этого игрока. */
    public boolean isChecking(UUID id) {
        return activeSpread.containsKey(id) || pending.containsKey(id);
    }

    /**
     * Ручная перепроверка ОДНОГО игрока. Обычная автоматическая проверка при входе
     * остаётся единственной фоновой проверкой; повторно проверить игрока во время игры
     * можно только явным действием администратора из его карточки/командой /afc check <ник>.
     */
    public void recheck(Player p) {
        if (p == null || !p.isOnline()) return;
        SchedulerUtil.onEntity(plugin, p, () -> recheckLocal(p));
    }

    private void recheckLocal(Player p) {
        if (!p.isOnline()) return;
        UUID id = p.getUniqueId();

        Session oldSession = activeSpread.remove(id);
        if (oldSession != null && oldSession.scheduled != null) oldSession.scheduled.cancel();
        Pending old = pending.remove(id);
        if (old != null) {
            if (old.timeout() != null) old.timeout().cancel();
            p.sendBlockChange(old.loc(), old.original());
        }
        attempts.remove(id);

        // Для игрока с Companion обновляем полный список модов; обычная табличная
        // проверка запускается отдельно. Других игроков эта операция не затрагивает.
        plugin.companion().requestRefresh(p);
        checkLocal(p, true, false);
    }

    /** Проверка произвольных ключей (для поиска нужных ключей мода). */
    public void probe(Player p, List<String> keys, CommandSender requester) {
        if (requester != null) requester.sendMessage("§eПроверка выполняется только при входе игрока. Ручной запрос отключён.");
    }

    private void probeLocal(Player p, List<String> keys, CommandSender requester) {
        if (pending.containsKey(p.getUniqueId())) {
            send(requester, "§cУ игрока уже идёт проверка, повтори через пару секунд.");
            return;
        }
        Map<String, List<String>> km = new LinkedHashMap<>();
        keys.forEach(k -> km.put(k, List.of(k)));
        sendBatch(p, new Session(km, Map.of(), Map.of(), Map.of(), requester));
    }

    /** Старая диагностическая команда оставлена для совместимости, но не запускает проверку во время игры. */
    public void test(Player p, CommandSender requester) {
        if (requester != null) requester.sendMessage("§eПроверка выполняется только при входе игрока. Диагностическая проверка во время игры отключена.");
    }

    private void testLocal(Player p, CommandSender requester) {
        if (!p.isOnline()) return;
        Map<String, List<String>> km = loadKeyMods(true);
        if (km.isEmpty()) {
            send(requester, "§cВ config.yml нет ни одного ключа (раздел mods).");
            return;
        }
        if (pending.containsKey(p.getUniqueId())) {
            send(requester, "§cУ игрока уже идёт проверка, повтори через пару секунд.");
            return;
        }
        sendBatch(p, new Session(km, loadRequires(true), loadForbids(true), loadMeta(true), requester));
    }

    private void send(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            SchedulerUtil.onEntity(plugin, player, () -> player.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    /** "lang:<ключ>" - строка перевода, иначе - keybind. */
    private static Component component(String wire) {
        return wire.startsWith("lang:") ? Component.translatable(wire.substring(5)) : Component.keybind(wire);
    }

    private void sendBatch(Player p, Session s) {
        if (!p.isOnline()) return;
        if (s.spread && busy(p)) {
            // игрок открыл сундук/меню или только что писал в чат - не перебиваем его, попробуем через секунду
            s.scheduled = SchedulerUtil.onEntityLater(plugin, p, 20L, () -> {
                s.scheduled = null;
                sendBatch(p, s);
            });
            return;
        }
        List<String> keys = s.batches.poll();
        if (keys == null) {
            finish(p, s);
            return;
        }

        Location base = p.getLocation();
        Location loc = new Location(p.getWorld(), base.getBlockX(), p.getWorld().getMinHeight(), base.getBlockZ());
        BlockData original = loc.getBlock().getBlockData();

        BlockData signData = Material.OAK_SIGN.createBlockData();
        p.sendBlockChange(loc, signData);
        Sign sign = (Sign) signData.createBlockState();
        for (int i = 0; i < 4; i++) {
            sign.getSide(Side.FRONT).line(i, i < keys.size() ? component(keys.get(i)) : Component.empty());
        }
        p.sendBlockUpdate(loc, sign);

        Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        UUID id = p.getUniqueId();
        long timeoutTicks = Math.max(10L, Math.min(
                plugin.getConfig().getLong("detection.timeout-ticks", 40L), 40L));
        io.papermc.paper.threadedregions.scheduler.ScheduledTask timeout = SchedulerUtil.onEntityLater(plugin, p, timeoutTicks, () -> {
            Pending pd = pending.remove(id);
            if (pd == null) return;
            if (p.isOnline()) p.sendBlockChange(pd.loc(), pd.original());
            if (s.probe != null) {
                send(s.probe, "§cНет ответа от клиента " + p.getName() + ".");
                return;
            }
            // клиент мог ещё грузиться или лагать - повторяем именно эту сессию,
            // иначе activeSpread остаётся со старой сессией и прогресс визуально зависает.
            int max = Math.max(0, Math.min(plugin.getConfig().getInt("detection.retries", 1), 1));
            int n = attempts.merge(id, 1, Integer::sum);
            if (n <= max && p.isOnline()) {
                s.batches.addFirst(pd.keys());
                long delay = Math.max(1L, Math.min(
                        plugin.getConfig().getLong("detection.retry-delay-ticks", 20L), 20L));
                s.scheduled = SchedulerUtil.onEntityLater(plugin, p, delay, () -> {
                    s.scheduled = null;
                    sendBatch(p, s);
                });
            } else {
                attempts.remove(id);
                activeSpread.remove(id, s);
                plugin.getLogger().info(p.getName() + ": клиент не ответил на проверку (попыток: " + n + ")");
                plugin.profiles().checkResult(p, "timeout");
            }
        });

        pending.put(id, new Pending(s, pos, loc, original, keys, p, timeout));

        var pm = PacketEvents.getAPI().getPlayerManager();
        pm.sendPacket(p, new WrapperPlayServerOpenSignEditor(pos, true));
        pm.sendPacket(p, new WrapperPlayServerCloseWindow(0));
    }

    private void onResponse(UUID id, String[] lines) {
        Pending pd = pending.remove(id);
        if (pd == null) return;
        if (pd.timeout() != null) pd.timeout().cancel();
        Player p = pd.player();
        if (!p.isOnline()) return;
        p.sendBlockChange(pd.loc(), pd.original());

        Session s = pd.session();
        List<String> keys = pd.keys();
        s.completedBatches++;
        for (int i = 0; i < keys.size(); i++) {
            String line = (i < lines.length && lines[i] != null) ? lines[i].strip() : "";
            s.resp.put(keys.get(i), line);
        }
        if (s.batchDelayTicks > 0 && !s.batches.isEmpty()) {
            // растянутая проверка: ждём перед следующей табличкой, а не шлём их пачками подряд
            s.scheduled = SchedulerUtil.onEntityLater(plugin, p, s.batchDelayTicks, () -> {
                s.scheduled = null;
                sendBatch(p, s);
            });
        } else {
            sendBatch(p, s); // следующая пачка или финиш
        }
    }

    private void finish(Player p, Session s) {
        if (s.scheduled != null) {
            s.scheduled.cancel();
            s.scheduled = null;
        }
        activeSpread.remove(p.getUniqueId(), s);
        evaluate(s);
        resolveMods(s);
        if (s.probe != null) {
            send(s.probe, "§eРезультат для §f" + p.getName() + "§e:");
            for (String spec : s.specs.keySet()) {
                String act = s.actual.getOrDefault(spec, "");
                List<String> ms = s.keyMods.get(spec);
                String label = ms == null ? " §8(условие исключения)"
                        : ms.equals(List.of(spec)) ? "" : " §8(" + String.join(", ", ms) + ")";
                if (s.hits.contains(spec)) {
                    send(s.probe, "§a✔ §f" + spec + label + " §8→ §7" + act);
                } else if (!act.isEmpty()) {
                    send(s.probe, "§e≠ §f" + spec + label + " §8→ §7ключ есть, текст: " + act);
                } else {
                    send(s.probe, "§7✘ §f" + spec + label + " §8→ §7нет");
                }
            }
            send(s.probe, s.mods.isEmpty() ? "§7Итог: ничего не найдено."
                    : "§aИтог: §f" + String.join(", ", s.mods.keySet()));
            s.rejected.forEach((mod, why) -> send(s.probe, "§8– отброшен " + mod + ": " + why));
            s.mods.keySet().forEach(mod -> send(s.probe, "§8  " + mod + " ← "
                    + String.join(", ", s.matched.getOrDefault(mod, Map.of()).keySet())));
            return;
        }
        attempts.remove(p.getUniqueId());
        plugin.profiles().checkResult(p, "ok");
        plugin.profiles().applyDetection(p, s.mods); // профиль игрока: какие моды замечены в этом заходе
        UUID id = p.getUniqueId();
        // в список срабатываний идут только моды из config.yml; известные моды из базы - лишь в профиль
        Map<String, String> flagMods = new LinkedHashMap<>();
        for (var e : s.mods.entrySet()) {
            ModMeta mm = s.meta.get(e.getKey());
            if (mm == null || !mm.plain()) flagMods.put(e.getKey(), e.getValue());
        }
        if (flagMods.isEmpty()) {
            unflag(id);
            return;
        }
        Flag toAlert = null;
        boolean persist = false;
        synchronized (flagged) {
            Flag existing = flagged.get(id);
            if (existing == null) {
                long now = System.currentTimeMillis();
                Flag f = new Flag(id, p.getName(), flagMods, now, now, 0);
                flagged.put(id, f);
                toAlert = f;
                persist = true;
            } else {
                existing.lastSeen = System.currentTimeMillis();
                if (!existing.mods.keySet().equals(flagMods.keySet())) {
                    existing.mods.clear();
                    existing.mods.putAll(flagMods);
                    toAlert = existing;
                    persist = true;
                }
            }
        }
        if (persist) {
            saveFlags();
            if (toAlert != null) alert(toAlert);
        }
    }

    /** Сопоставляет ответы клиента со спецификациями из конфига. */
    private static void evaluate(Session s) {
        s.matched.clear();
        s.hits.clear();
        s.actual.clear();
        for (var se : s.specs.entrySet()) {
            String spec = se.getKey();
            Spec sp = se.getValue();
            String line = s.resp.getOrDefault(sp.wire(), "");
            boolean resolved = !line.isEmpty() && !line.equals(sp.raw());
            if (resolved) s.actual.put(spec, line);
            if (resolved && (sp.expected().isEmpty() || sp.expected().contains(line))) {
                s.hits.add(spec);
                for (String mod : s.keyMods.getOrDefault(spec, List.of())) {
                    s.matched.computeIfAbsent(mod, x -> new LinkedHashMap<>()).put(spec, line);
                }
            }
        }
        // require: должны совпасть ВСЕ; forbid: не должен совпасть НИ ОДИН
        s.rejected.clear();
        Iterator<String> it = s.matched.keySet().iterator();
        while (it.hasNext()) {
            String mod = it.next();
            String reason = null;
            for (String req : s.requires.getOrDefault(mod, List.of())) {
                if (!s.hits.contains(req)) { reason = "нет " + req; break; }
            }
            if (reason == null) {
                for (String fb : s.forbids.getOrDefault(mod, List.of())) {
                    if (s.hits.contains(fb)) { reason = "запрещено, есть " + fb; break; }
                }
            }
            if (reason != null) {
                s.rejected.put(mod, reason);
                it.remove();
            }
        }
    }

    /**
     * Если у мода A совпали только те ключи, которые есть и у мода B (у B совпало больше),
     * то A - это просто общий ключ форка/оригинала, и в результат он не попадает.
     */
    private static void resolveMods(Session s) {
        s.mods.clear();
        for (var e : s.matched.entrySet()) {
            boolean dominated = false;
            for (var o : s.matched.entrySet()) {
                if (o != e && o.getValue().size() > e.getValue().size()
                        && o.getValue().keySet().containsAll(e.getValue().keySet())) {
                    dominated = true;
                    break;
                }
            }
            if (dominated) continue;
            // показываем название клавиши (F4), а не текст перевода
            String shown = null;
            for (var k : e.getValue().entrySet()) {
                if (!k.getKey().startsWith("lang:")) { shown = k.getValue(); break; }
            }
            if (shown == null) shown = ""; // мод найден только по строкам перевода - клавиши нет
            s.mods.put(e.getKey(), shown);
        }
        // из взаимоисключающей группы оставляем один мод: где совпало больше ключей, при равенстве - первый в конфиге
        Map<String, String> best = new HashMap<>();
        for (String mod : s.mods.keySet()) {
            ModMeta m = s.meta.get(mod);
            if (m == null || m.group() == null) continue;
            String cur = best.get(m.group());
            if (cur == null) { best.put(m.group(), mod); continue; }
            int a = s.matched.get(mod).size(), b = s.matched.get(cur).size();
            if (a > b || (a == b && m.order() < s.meta.get(cur).order())) best.put(m.group(), mod);
        }
        s.mods.keySet().removeIf(mod -> {
            ModMeta m = s.meta.get(mod);
            return m != null && m.group() != null && !mod.equals(best.get(m.group()));
        });
    }

    public boolean isBypassOnline(UUID id) {
        return bypassOnline.contains(id);
    }

    /** Мод помечен в конфиге как читерский (category: cheat). */
    public boolean isCheat(String modName) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("mods");
        if (sec == null) return false;
        for (String id : sec.getKeys(false)) {
            if (modName.equals(sec.getString(id + ".name", id))) {
                return "cheat".equalsIgnoreCase(sec.getString(id + ".category"));
            }
        }
        return false;
    }

    public boolean hasCheat(Flag f) {
        for (String mod : f.mods.keySet()) if (isCheat(mod)) return true;
        return false;
    }

    private void alert(Flag f) {
        boolean cheat = hasCheat(f);
        // Исключение может быть настроено по нику офлайн или по permission у текущей сессии.
        if ((plugin.isExemptName(f.name) || bypassOnline.contains(f.uuid)) && !cheat) return;

        Component mods = Component.empty();
        boolean first = true;
        for (var e : f.mods.entrySet()) {
            if (!first) mods = mods.append(Component.text(", ", NamedTextColor.GRAY));
            first = false;
            mods = mods.append(Component.text(e.getKey(), isCheat(e.getKey()) ? NamedTextColor.RED : NamedTextColor.WHITE))
                    .append(Component.text(e.getValue().isEmpty() ? "" : " [" + e.getValue() + "]", NamedTextColor.DARK_GRAY));
        }
        Component msg = (cheat ? Component.text("[CW ЧИТ] ", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                : Component.text("[Проверка] ", NamedTextColor.RED))
                .append(Component.text(f.name, NamedTextColor.YELLOW))
                .append(Component.text(" → ", NamedTextColor.GRAY))
                .append(mods)
                .clickEvent(ClickEvent.runCommand("/afc list"))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть список срабатываний")));

        for (Player staff : plugin.profiles().onlinePlayers()) {
            SchedulerUtil.onEntity(plugin, staff, () -> {
                if (plugin.access().receives(staff)) staff.sendMessage(msg);
            });
        }
        if (plugin.getConfig().getBoolean("actions.log-to-console", true)) {
            StringBuilder sb = new StringBuilder(f.name).append(" -> ");
            f.mods.forEach((m, k) -> sb.append(m).append(k.isEmpty() ? "" : " [" + k + "]").append(" "));
            plugin.getLogger().info(sb.toString().trim());
        }
    }
}
