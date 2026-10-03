package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.watch.ModProject;
import dev.antifreecam.watch.WatchStatus;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.manager.ProfileManager;
import dev.antifreecam.util.SchedulerUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** Список модов одного игрока: белые - в последнем заходе, серые - были раньше; ЛКМ - отметить вниманием. */
public final class ModsGui implements InventoryHolder {

    public static final int PER_PAGE = 45, PREV = 45, INFO = 47, BACK = 49, HISTORY = 51, RECHECK = 52, NEXT = 53;

    private final AntiFreecam plugin;
    private final ProfileManager.Profile profile;
    private final List<ProfileManager.PMod> mods;
    private final int page;
    private final boolean backToPlayers;
    private Inventory inv;
    private ScheduledTask refreshTask;

    public ModsGui(AntiFreecam plugin, ProfileManager.Profile profile, int page) {
        this(plugin, profile, page, false);
    }

    public ModsGui(AntiFreecam plugin, ProfileManager.Profile profile, int page, boolean backToPlayers) {
        this.backToPlayers = backToPlayers;
        this.plugin = plugin;
        this.profile = profile;
        ProfileManager pm = plugin.profiles();
        List<ProfileManager.PMod> list = collapseDuplicates(profile.mods.values());
        list.sort(Comparator
                .comparing((ProfileManager.PMod m) -> !m.cheat)
                .thenComparing(m -> !pm.isWatched(m.id))
                .thenComparing(m -> !profile.present(m))
                .thenComparing(m -> -m.last));
        this.mods = list;
        int maxPage = Math.max(0, (list.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(page, 0), maxPage);
    }

    public int page() { return page; }

    public boolean backToPlayers() { return backToPlayers; }

    public ProfileManager.Profile profile() { return profile; }

    public ProfileManager.PMod modAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return (slot >= 0 && slot < PER_PAGE && idx < mods.size()) ? mods.get(idx) : null;
    }

    private static ItemStack build(Material m, String name, List<String> lore, boolean glint) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Gui.c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Gui.c(s));
        meta.lore(l);
        if (glint) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    public void open(Player viewer) {
        ProfileManager pm = plugin.profiles();
        boolean online = plugin.profiles().isOnline(profile.uuid);
        int pages = Math.max(1, (int) Math.ceil(mods.size() / (double) PER_PAGE));
        String titleName = profile.companion
                ? "<gold><bold>" + Gui.esc(profile.name) + "</bold>"
                : "<yellow>" + Gui.esc(profile.name);
        inv = Bukkit.createInventory(this, 54, Gui.c("<dark_red>Моды: " + titleName
                + " <gray>(" + (page + 1) + "/" + pages + ")"));
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");

        for (int i = 0; i < PER_PAGE; i++) {
            ProfileManager.PMod m = modAt(i);
            if (m == null) break;
            boolean present = profile.present(m);
            boolean watched = pm.isWatched(m.id);
            List<String> lore = new ArrayList<>();
            ModProject.Classification classification = classification(m);
            WatchStatus status = classification.status();
            if (m.isCompanion()) {
                lore.add("<gray>Источник: <gold>мод полной проверки");
                lore.add("<gray>Полный список модов получен от клиента: <green>да");
                lore.add("<gray>Статус: " + statusText(status));
                lore.add("<blue>Проверка файла");
                lore.add("<gray>" + Gui.esc(humanHashExplanation(status, classification, m.sha512, m.sha1)));
                lore.add("<gray>Результат проверки: <white>" + Gui.esc(classification.reason()));
                if (!m.sha512.isBlank()) {
                    lore.add("<blue>SHA-512 файла (основной код):");
                    lore.add("<gray>Полный отпечаток именно этого JAR-файла.");
                    lore.addAll(wrapHash(m.sha512));
                } else {
                    lore.add("<blue>SHA-512 файла: <dark_gray>не получен");
                    lore.add("<gray>Точное сравнение содержимого JAR невозможно.");
                }
                if (!m.sha1.isBlank()) {
                    lore.add("<blue>SHA-1 файла (дополнительный код):");
                    lore.add("<gray>Ещё один отпечаток того же JAR-файла.");
                    lore.add("<white>" + Gui.esc(m.sha1));
                }
                if (!m.detail.isBlank()) lore.add("<gray>Версия: <white>" + Gui.esc(m.detail));
            } else {
                lore.add("<gray>Тип: " + (m.cheat ? "<red>читерский клиент" : m.isChannel() ? "<white>мод с сетевым каналом" : "<white>известный мод"));
                if (!m.detail.isEmpty() && !m.isChannel()) lore.add("<gray>Клавиша: <white>" + Gui.esc(m.detail));
                if (!m.detail.isEmpty() && m.isChannel()) lore.add("<gray>Каналы: <white>" + Gui.esc(m.detail));
            }
            lore.add(present ? (online ? "<green>Стоит сейчас" : "<green>Был в последнем заходе")
                    : "<gray>Заходил с ним раньше, в последнем заходе нет <dark_gray>(мог удалить)");
            lore.add("<gray>Впервые: <white>" + fmt.format(new Date(m.first)));
            lore.add("<gray>Последний раз: <white>" + fmt.format(new Date(m.last)));
            if (watched) lore.add("<gold>⭐ Отмечен вниманием <gray>(у всех, у кого он есть)");
            lore.add("");
            lore.add("<green>ЛКМ <gray>- " + (watched ? "снять отметку" : "отметить вниманием"));
            if (status != null) {
                lore.add("<yellow>Shift+ПКМ <gray>- изменить статус: <white>" + status.display());
                lore.add("<gray>Статусы: <red>Запрещён <gray>→ <yellow>Требует проверки <gray>→ <green>Разрешён <gray>→ <gray>Старая версия");
            } else {
                lore.add("<yellow>Shift+ПКМ <gray>- добавить в «Запрошенные моды» как: <red>Запрещён");
                lore.add("<gray>После этого Shift+ПКМ переключает статус");
            }

            String color = present ? "<white>" : "<gray>";
            // Если мод есть в «Запрошенных модах», его статус имеет приоритет над цветом присутствия.
            // Поэтому запрещённый/требующий проверки/разрешённый мод больше не становится всегда лаймовым.
            String name = (watched ? "<gold>⭐ " : "") + (status != null ? statusColor(status) : (m.cheat ? "<red>☠ " : color)) + Gui.esc(m.display);
            Material mat = materialFor(m, present, status);
            inv.setItem(i, build(mat, name, lore, watched));
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, backToPlayers ? "<red>К списку игроков" : "<red>К списку срабатываний"));

        putInfoHead(viewer, fmt);

        inv.setItem(RECHECK, build(Material.CLOCK, "<aqua>Перепроверить этого игрока",
                List.of("<gray>Запустить новую проверку только для",
                        "<gray>этого игрока прямо сейчас.",
                        "<gray>Другие игроки не проверяются.", "",
                        online ? "<green>ЛКМ <gray>- начать перепроверку"
                               : "<dark_gray>Игрок сейчас не в сети"), false));

        inv.setItem(HISTORY, build(Material.BOOK, "<aqua>История заходов",
                List.of("<gray>Записано заходов: <white>" + profile.history.size(), "",
                        "<gray>Каждый заход - отдельный предмет,", "<gray>моды по 5 в строке.", "",
                        "<green>ЛКМ <gray>- открыть"), false));

        viewer.openInventory(inv);
        startProgressRefresh(viewer);
    }

    private ModProject.Classification classification(ProfileManager.PMod m) {
        String id = canonicalModId(m.id);
        return plugin.watch().classify(id, m.display, m.sha512, m.sha1);
    }

    private static String canonicalModId(String id) {
        if (id == null) return "";
        String value = id.trim();
        if (value.startsWith("cw:")) return value.substring(3);
        if (value.startsWith("fp:")) return value.substring(3);
        if (value.startsWith("ch:")) return value.substring(3);
        return value;
    }

    /**
     * Старые способы обнаружения и Companion могут записать один и тот же мод дважды:
     * например fp:sodium и cw:sodium. В GUI показываем только одну запись и предпочитаем
     * данные Companion, потому что только они содержат SHA-512/SHA-1.
     */
    private static List<ProfileManager.PMod> collapseDuplicates(Iterable<ProfileManager.PMod> source) {
        java.util.LinkedHashMap<String, ProfileManager.PMod> merged = new java.util.LinkedHashMap<>();
        for (ProfileManager.PMod m : source) {
            String key = canonicalModId(m.id).toLowerCase(java.util.Locale.ROOT);
            if (key.isBlank()) key = m.display == null ? m.id : m.display.toLowerCase(java.util.Locale.ROOT);
            ProfileManager.PMod old = merged.get(key);
            if (old == null || sourcePriority(m) > sourcePriority(old)
                    || (sourcePriority(m) == sourcePriority(old) && m.last > old.last)) {
                merged.put(key, m);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static int sourcePriority(ProfileManager.PMod m) {
        if (m.isCompanion()) return 3;
        if (m.isChannel()) return 1;
        if (m.id.startsWith("fp:")) return 2;
        return 0;
    }

    private static String statusText(WatchStatus s) {
        return s == null ? "<dark_purple>не в «Запрошенных модах» / не подтверждён" : WatchGui.statusColor(s) + s.display();
    }

    private static String humanHashExplanation(WatchStatus status, ModProject.Classification classification, String sha512, String sha1) {
        if (classification == null) return "Проверка файла ещё не выполнена.";
        String reason = classification.reason() == null ? "" : classification.reason().toLowerCase();
        if (classification.exactHash()) {
            if (reason.contains("sha-1")) return "SHA-1 совпадает с опубликованным оригиналом; SHA-512 отдельно не подтверждён.";
            return "SHA-512 совпадает с опубликованным оригиналом.";
        }
        if (status == WatchStatus.SUSPICIOUS) {
            if (sha512 != null && !sha512.isBlank()) return "SHA-512 НЕ совпадает с опубликованным оригиналом.";
            if (sha1 != null && !sha1.isBlank()) return "SHA-1 не совпал с опубликованным оригиналом.";
        }
        if (status == null) return "Для этого файла нет подтверждённого оригинала в списке.";
        if (sha512 != null && !sha512.isBlank()) return "SHA-512 получен и готов к сравнению.";
        return "Проверочные коды файла не получены.";
    }

    private static String statusColor(WatchStatus s) {
        return switch (s) {
            case FORBIDDEN -> "<red>☠ ";
            case SUSPICIOUS -> "<yellow>⚠ ";
            case ALLOWED -> "<green>✓ ";
            case LEGACY -> "<gray>▣ ";
        };
    }

    private static Material materialFor(ProfileManager.PMod m, boolean present, WatchStatus status) {
        // Статус из watchlist относится к самому моду, поэтому не зависит от источника
        // обнаружения (Companion, таблица клавиш или сетевой канал).
        if (status != null) return status.material();
        // Нет записи в «Запрошенных модах» — показываем именно фиолетовый статус.
        // Белый/зелёный по факту наличия файла здесь больше не используется, иначе
        // незапрошенные companion/fp/ch записи выглядели как «Разрешён».
        return Material.PURPLE_DYE;
    }

    private static List<String> wrapHash(String hash) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < hash.length(); i += 48) out.add("<white>" + hash.substring(i, Math.min(hash.length(), i + 48)));
        return out;
    }

    private void putInfoHead(Player viewer, SimpleDateFormat fmt) {
        ProfileManager pm = plugin.profiles();
        boolean online = pm.isOnline(profile.uuid);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(profile.uuid));
        sm.displayName(Gui.c(profile.companion ? "<gold><bold>◆ " + Gui.esc(profile.name) + "</bold>" : "<yellow>" + Gui.esc(profile.name)));
        List<Component> hl = new ArrayList<>();
        hl.add(Gui.c("<gray>Статус: " + (online ? "<green>онлайн" : "<red>оффлайн")));
        hl.add(Gui.c("<gray>Клиент: " + (profile.brand.isEmpty() ? "<dark_gray>не передан" : "<white>" + Gui.esc(profile.brand))));
        if (!profile.launcher.isEmpty()) hl.add(Gui.c("<gray>Похоже на: <aqua>" + Gui.esc(profile.launcher)));
        hl.add(Gui.c("<gray>Версия игры: " + (profile.version.isEmpty() ? "<dark_gray>неизвестна" : "<white>" + Gui.esc(profile.version))));
        if (profile.companion) {
            if (!profile.companionLauncher.isBlank()) {
                String confidence = profile.companionLauncherConfidence.isBlank() ? "" : " <dark_gray>(" + Gui.esc(profile.companionLauncherConfidence) + ")";
                hl.add(Gui.c("<gray>Лаунчер по данным мода: <aqua>" + Gui.esc(profile.companionLauncher) + confidence));
            }
            if (!profile.companionMinecraftVersion.isBlank()) hl.add(Gui.c("<gray>Версия игры от мода: <white>" + Gui.esc(profile.companionMinecraftVersion)));
            if (!profile.companionFabricLoaderVersion.isBlank()) hl.add(Gui.c("<gray>Fabric Loader от мода: <white>" + Gui.esc(profile.companionFabricLoaderVersion)));
            hl.add(Gui.c("<gold><bold>◆ Установлен мод полной проверки</bold>"));
            hl.add(Gui.c("<gold>Полный список модов получен от клиента"));
            if (!profile.companionVersion.isBlank()) hl.add(Gui.c("<gray>Версия мода полной проверки: <white>" + Gui.esc(profile.companionVersion)));
        }
        DetectionManager.Progress prog = plugin.detection().progress(profile.uuid);
        boolean checking = plugin.detection().isChecking(profile.uuid);
        String check = ProfileManager.checkText(profile.checkState);
        if (prog != null) {
            hl.add(Gui.c("<yellow>⏳ Проверка ещё идёт: <white>" + prog.done() + "<yellow>/<white>" + prog.total()));
            hl.add(Gui.c("<dark_gray>Прогресс обновляется автоматически"));
        } else if (checking) {
            hl.add(Gui.c("<yellow>⏳ Проверка ещё идёт..."));
            hl.add(Gui.c("<dark_gray>Ожидание ответа клиента"));
        } else {
            hl.add(Gui.c("<gray>Проверка: " + (check != null ? check : "<dark_gray>нет данных")));
        }
        if (profile.checkState.equals("timeout")) hl.add(Gui.c("<dark_gray>(клиент не ответил вовремя)"));
        List<String> cheats = new ArrayList<>();
        for (ProfileManager.PMod m : mods) if (m.cheat && profile.present(m)) cheats.add(m.display);
        if (!cheats.isEmpty()) hl.add(Gui.c("<red>Читерский клиент: " + Gui.esc(String.join(", ", cheats))));
        hl.add(Gui.c("<gray>Модов записано: <white>" + mods.size()));
        if (profile.lastJoin > 0) hl.add(Gui.c("<gray>Последний заход: <white>" + fmt.format(new Date(profile.lastJoin))));
        hl.add(Gui.c(""));
        hl.add(Gui.c("<white>Белые <gray>- стоят в последнем заходе"));
        hl.add(Gui.c("<gray>Серые - были раньше, сейчас нет"));
        hl.add(Gui.c("<red>Красные <gray>- запрещённый мод"));
        hl.add(Gui.c("<yellow>Жёлтые <gray>- требуют проверки"));
        hl.add(Gui.c("<green>Зелёные <gray>- разрешённые"));
        hl.add(Gui.c("<gray>Серые <gray>- старая разрешённая версия"));
        hl.add(Gui.c("<dark_purple>Фиолетовые <gray>- мод не добавлен в «Запрошенные моды»"));
        hl.add(Gui.c("<yellow>Shift+ПКМ по моду <gray>- изменить его статус"));
        if (plugin.access().canPumpkin(viewer)) {
            hl.add(Gui.c(""));
            hl.add(Gui.c(plugin.pumpkin().has(profile.uuid)
                    ? "<gold>Тыква надета <gray>- клик по голове снимет её"
                    : "<gold>Клик по голове <gray>- надеть тыкву «Удали читы»"));
        }
        sm.lore(hl);
        head.setItemMeta(sm);
        inv.setItem(INFO, head);
    }

    /** Отменяет фоновое обновление перед обработкой клика, чтобы таймер не заменил GUI во время действия. */
    public void stopProgressRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void startProgressRefresh(Player viewer) {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = viewer.getScheduler().runAtFixedRate(plugin, task -> {
            if (!viewer.isOnline() || viewer.getOpenInventory().getTopInventory().getHolder() != this) {
                task.cancel();
                return;
            }
            DetectionManager.Progress prog = plugin.detection().progress(profile.uuid);
            if (prog == null && !plugin.detection().isChecking(profile.uuid)) {
                task.cancel();
                // Не переоткрываем меню прямо внутри обновляющего таймера:
                // это могло перехватить ЛКМ/ПКМ ровно в момент завершения проверки.
                SchedulerUtil.onEntityLater(plugin, viewer, 1L, () -> {
                    if (!viewer.isOnline()) return;
                    if (viewer.getOpenInventory().getTopInventory().getHolder() != this) return;
                    new ModsGui(plugin, profile, page, backToPlayers).open(viewer);
                });
                return;
            }
            putInfoHead(viewer, new SimpleDateFormat("dd.MM HH:mm"));
        }, null, 10L, 10L);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
