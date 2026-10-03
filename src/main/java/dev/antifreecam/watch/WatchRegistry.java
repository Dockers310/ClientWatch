package dev.antifreecam.watch;

import dev.antifreecam.AntiFreecam;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WatchRegistry {
    private final AntiFreecam plugin;
    private final PlatformApi api;
    private final File file;
    private final Map<String, ModProject.Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public WatchRegistry(AntiFreecam plugin) {
        this.plugin = plugin;
        this.api = new PlatformApi(plugin::getConfig);
        this.file = new File(plugin.getDataFolder(), "watchlist.yml");
        load();
    }

    public List<ModProject.Entry> all() {
        List<ModProject.Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparing(e -> e.project().title(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public ModProject.Entry get(String key) { return entries.get(key); }

    public int size() { return entries.size(); }

    public CompletableFuture<List<ModProject.SearchResult>> search(String query) {
        return api.search(query);
    }

    public CompletableFuture<ModProject.Entry> inspect(ModProject.SearchResult project, String query) {
        return api.loadEntry(project, query);
    }

    public CompletableFuture<ModProject.Entry> add(ModProject.SearchResult project, String query) {
        return api.loadEntry(project, query).thenApply(entry -> {
            // Если этот мод ранее был добавлен прямо из меню игрока как локальное
            // правило, заменяем локальную запись полноценной записью каталога.
            for (ModProject.Entry old : new ArrayList<>(entries.values())) {
                if (old.key().startsWith("local:")
                        && old.matchesInstalledMod(entry.project().projectId(), entry.project().title())) {
                    entries.remove(old.key());
                }
            }
            entries.put(entry.key(), entry);
            dirty.set(true);
            flush();
            plugin.refreshWatchClassifications();
            return entry;
        });
    }

    public synchronized void reload() {
        entries.clear();
        load();
        plugin.refreshWatchClassifications();
    }

    public void remove(String key) {
        if (entries.remove(key) != null) {
            dirty.set(true);
            flush();
            plugin.refreshWatchClassifications();
        }
    }

    public WatchStatus cycle(String key) {
        ModProject.Entry e = entries.get(key);
        if (e == null) return null;
        WatchStatus next = e.status().next();
        e.status(next);
        dirty.set(true);
        flush();
        plugin.refreshWatchClassifications();
        return next;
    }

    public boolean toggleAllVersions(String key) {
        ModProject.Entry e = entries.get(key);
        if (e == null) return false;
        e.allVersions(!e.allVersions());
        dirty.set(true);
        flush();
        plugin.refreshWatchClassifications();
        return e.allVersions();
    }

    public void setStatus(String key, WatchStatus status) {
        ModProject.Entry e = entries.get(key);
        if (e == null) return;
        e.status(status);
        dirty.set(true);
        flush();
        plugin.refreshWatchClassifications();
    }

    public ModProject.Classification classify(String modId, String display, String sha512, String sha1) {
        // Статус из «Запрошенных модов» определяется ТОЛЬКО по самому проекту.
        // Мы намеренно не используем глобальное совпадение SHA как способ назначить
        // статус другому моду: одинаковый файл/зависимость не должны перекрашивать
        // весь список игрока.
        ModProject.Entry identity = findMatchingEntry(modId, display);

        if (identity == null) {
            return new ModProject.Classification(null, null, false,
                    "мод не найден в списке запрошенных модификаций");
        }

        boolean sha512Match = identity.hasSha512(sha512);
        boolean sha1Match = identity.hasSha1(sha1);
        boolean exactHash = sha512Match || sha1Match;

        if (identity.status() == WatchStatus.FORBIDDEN) {
            return new ModProject.Classification(identity.status(), identity, exactHash,
                    exactHash
                            ? (sha512Match
                                    ? "проект запрещён; SHA-512 совпадает с опубликованным оригиналом"
                                    : "проект запрещён; SHA-1 совпадает с опубликованным оригиналом")
                            : "проект находится в «Запрошенных модах» и имеет статус «Запрещён»");
        }

        if (identity.allVersions()) {
            return new ModProject.Classification(identity.status(), identity, exactHash,
                    exactHash
                            ? "проект найден; проверочный код совпадает с одним из опубликованных файлов"
                            : "для этого проекта включено применение статуса ко всем версиям");
        }

        if (exactHash) {
            return new ModProject.Classification(identity.status(), identity, true,
                    sha512Match
                            ? "SHA-512 совпадает с опубликованным оригиналом этого проекта"
                            : "SHA-1 совпадает с опубликованным оригиналом этого проекта");
        }

        return new ModProject.Classification(WatchStatus.SUSPICIOUS, identity, false,
                "название/идентификатор проекта совпадает, но проверочный код файла не совпадает с опубликованным файлом");
    }

    /** Ищет конкретную запись «Запрошенных модов» для установленного мода. */
    public ModProject.Entry findMatchingEntry(String modId, String display) {
        ModProject.Entry best = null;
        for (ModProject.Entry e : entries.values()) {
            if (!e.matchesInstalledMod(modId, display)) continue;
            best = prefer(best, e);
        }

        // Если старая запись сохранила в title технический ключ вроде
        // "modrinth:XeEZ3fK2", восстанавливаем нормальное имя из Companion/профиля.
        if (best != null && display != null && !display.isBlank()
                && looksLikeProjectKey(best.project().title())
                && !looksLikeProjectKey(display)) {
            best.title(display);
            dirty.set(true);
            flush();
        }
        return best;
    }

    /**
     * Создаёт локальное правило прямо из меню игрока, если мод ещё не был добавлен
     * через «Запрошенные моды». Первый Shift+ПКМ делает его запрещённым.
     * Это позволяет администратору менять статус любого реально обнаруженного мода.
     */
    public ModProject.Entry ensureInstalled(String modId, String display) {
        ModProject.Entry existing = findMatchingEntry(modId, display);
        if (existing != null) return existing;

        String canonical = canonicalInstalledId(modId);
        String title = display == null || display.isBlank() ? canonical : display.trim();
        String base = canonical.isBlank() ? slugify(title) : canonical;
        if (base.isBlank()) base = "unknown";

        String key = "local:" + base;
        ModProject.SearchResult project = new ModProject.SearchResult(
                key,
                ModProject.Platform.MODRINTH,
                canonical.isBlank() ? base : canonical,
                title,
                base,
                "",
                "Добавлено непосредственно из меню игрока.",
                "");
        ModProject.Entry entry = new ModProject.Entry(
                key, title, project, WatchStatus.FORBIDDEN, List.of(),
                System.currentTimeMillis(), true);
        entries.put(key, entry);
        dirty.set(true);
        flush();
        plugin.refreshWatchClassifications();
        return entry;
    }

    private static String canonicalInstalledId(String id) {
        if (id == null) return "";
        String value = id.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("cw:") || value.startsWith("fp:") || value.startsWith("ch:")) {
            value = value.substring(3);
        }
        return value;
    }

    private static String slugify(String value) {
        if (value == null) return "";
        String s = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("_+", "_");
        return s;
    }

    /** Совместимость для внутренних вызовов старых расширений. */
    public ModProject.Classification classify(String modId, String display, String sha512) {
        return classify(modId, display, sha512, "");
    }

    private ModProject.Entry prefer(ModProject.Entry a, ModProject.Entry b) {
        // Первый найденный проект передаётся сюда как null. Старый код сразу
        // обращался к a.status(), из-за чего открытие меню модов падало с
        // NullPointerException и сервер писал "Could not pass event InventoryClickEvent".
        if (a == null) return b;
        if (b == null) return a;

        // Если по какой-то причине проект совпал с несколькими записями,
        // сохраняем наиболее строгий статус.
        if (a.status() == WatchStatus.FORBIDDEN && b.status() != WatchStatus.FORBIDDEN) return a;
        if (b.status() == WatchStatus.FORBIDDEN && a.status() != WatchStatus.FORBIDDEN) return b;
        if (a.status() == WatchStatus.SUSPICIOUS && b.status() == WatchStatus.ALLOWED) return a;
        if (b.status() == WatchStatus.SUSPICIOUS && a.status() == WatchStatus.ALLOWED) return b;
        return b;
    }

    public List<ModProject.Entry> related(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
        return all().stream()
                .filter(e -> q.isBlank()
                        || e.project().title().toLowerCase(Locale.ROOT).contains(q)
                        || e.project().slug().toLowerCase(Locale.ROOT).contains(q)
                        || e.query().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }

    public String statusText(ModProject.Entry e) {
        return e.status().display();
    }

    public void flush() {
        if (!dirty.compareAndSet(true, false)) return;
        save();
    }

    public void shutdown() { flush(); }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("entries");
        if (sec == null) return;

        boolean repairedNames = false;
        for (String key : sec.getKeys(false)) {
            try {
                String base = "entries." + key;
                String platform = sec.getString(base + ".platform", "MODRINTH");
                String projectId = sec.getString(base + ".project-id", "");
                String storedTitle = sec.getString(base + ".title", "");
                String storedSlug = sec.getString(base + ".slug", "");
                String query = sec.getString(base + ".query", "");

                String title = storedTitle;
                if (title.isBlank() || looksLikeProjectKey(title)) {
                    String fallback = !query.isBlank() && !looksLikeProjectKey(query) ? query
                            : (!storedSlug.isBlank() && !looksLikeProjectKey(storedSlug) ? storedSlug : "");
                    if (!fallback.isBlank()) {
                        title = prettyProjectName(fallback);
                        repairedNames = true;
                    }
                }
                if (title.isBlank()) title = key;

                String slug = storedSlug.isBlank() ? title : storedSlug;
                String author = sec.getString(base + ".author", "");
                String description = sec.getString(base + ".description", "");
                String url = sec.getString(base + ".url", "");
                if (query.isBlank()) query = title;
                boolean allVersions = sec.getBoolean(base + ".all-versions", false);
                WatchStatus status = WatchStatus.parse(sec.getString(base + ".status", "FORBIDDEN"));
                long updated = sec.getLong(base + ".updated", 0L);

                List<ModProject.FileInfo> files = new ArrayList<>();
                for (Map<?, ?> rawFile : sec.getMapList(base + ".files")) {
                    String version = str(rawFile.get("version"));
                    String filename = str(rawFile.get("filename"));
                    String sha512 = str(rawFile.get("sha512"));
                    String sha1 = str(rawFile.get("sha1"));
                    String fingerprint = str(rawFile.get("fingerprint"));
                    List<ModProject.Dependency> deps = new ArrayList<>();
                    Object depRaw = rawFile.get("dependencies");
                    if (depRaw instanceof List<?> l) {
                        for (Object d : l) {
                            if (d instanceof Map<?, ?> dm) {
                                deps.add(new ModProject.Dependency(str(dm.get("name")), str(dm.get("relation"))));
                            } else {
                                deps.add(new ModProject.Dependency(String.valueOf(d), "required"));
                            }
                        }
                    }
                    files.add(new ModProject.FileInfo(version, filename, sha512, sha1, fingerprint, deps));
                }

                ModProject.Platform p = ModProject.Platform.valueOf(platform.toUpperCase(Locale.ROOT));
                ModProject.SearchResult sr = new ModProject.SearchResult(
                        key, p, projectId, title, slug, author, description, url);
                entries.put(key, new ModProject.Entry(key, query, sr, status, files,
                        updated > 0 ? updated : System.currentTimeMillis(), allVersions));
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось прочитать запись watchlist.yml " + key + ": " + e.getMessage());
            }
        }
        if (repairedNames) {
            dirty.set(true);
            flush();
        }
    }

    private static boolean looksLikeProjectKey(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.matches("(modrinth|curseforge):[^\\s]+");
    }

    private static String prettyProjectName(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank()) return v;
        String[] parts = v.replace('_', ' ').replace('-', ' ').split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (out.length() > 0) out.append(' ');
            if (part.length() == 1) out.append(part.toUpperCase(Locale.ROOT));
            else out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", 1);
        for (ModProject.Entry e : entries.values()) {
            String b = "entries." + e.key();
            y.set(b + ".platform", e.project().platform().name());
            y.set(b + ".project-id", e.project().projectId());
            y.set(b + ".title", e.project().title());
            y.set(b + ".slug", e.project().slug());
            y.set(b + ".author", e.project().author());
            y.set(b + ".description", e.project().description());
            y.set(b + ".url", e.project().url());
            y.set(b + ".query", e.query());
            y.set(b + ".status", e.status().name());
            y.set(b + ".all-versions", e.allVersions());
            y.set(b + ".updated", e.updated());

            List<Map<String, Object>> files = new ArrayList<>();
            for (ModProject.FileInfo f : e.files()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("version", f.version());
                fm.put("filename", f.filename());
                fm.put("sha512", f.sha512());
                fm.put("sha1", f.sha1());
                fm.put("fingerprint", f.fingerprint());
                List<Map<String, Object>> deps = new ArrayList<>();
                for (ModProject.Dependency d : f.dependencies()) {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("name", d.name());
                    dm.put("relation", d.relation());
                    deps.add(dm);
                }
                fm.put("dependencies", deps);
                files.add(fm);
            }
            y.set(b + ".files", files);
        }

        String data = y.saveToString();
        if (plugin.isEnabled() && !plugin.isStopping()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> write(data));
        } else {
            write(data);
        }
    }

    private void write(String data) {
        try {
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить watchlist.yml: " + e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
