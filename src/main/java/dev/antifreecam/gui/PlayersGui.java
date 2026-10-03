package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.manager.ProfileManager;
import dev.antifreecam.watch.ModProject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Все игроки, у которых есть профиль (в том числе «чистые»): клиент, проверка, моды. */
public final class PlayersGui implements InventoryHolder {

    public static final int PER_PAGE = 45, PREV = 45, SEARCH = 47, BACK = 49, NEXT = 53;

    private final AntiFreecam plugin;
    private final List<ProfileManager.Profile> list;
    private final int total;
    private final int page;
    private final String filter;
    private Inventory inv;

    public PlayersGui(AntiFreecam plugin, int page, String filter) {
        this.plugin = plugin;
        this.filter = (filter == null || filter.isBlank()) ? null : filter.strip();
        List<ProfileManager.Profile> all = plugin.profiles().all();
        this.total = all.size();
        List<ProfileManager.Profile> res = new ArrayList<>();
        String q = this.filter == null ? null : this.filter.toLowerCase(Locale.ROOT);
        for (ProfileManager.Profile p : all) {
            if (q == null || p.name.toLowerCase(Locale.ROOT).contains(q) || plugin.profiles().matches(p.uuid, q)
                    || ((q.equals("чит") || q.equals("cheat")) && ProfileManager.hasCheatNow(p))) res.add(p);
        }
        res.sort(Comparator
                .comparing((ProfileManager.Profile p) -> !plugin.profiles().isOnline(p.uuid))
                .thenComparing(p -> !p.companion)
                .thenComparing(p -> !ProfileManager.hasCheatNow(p))
                .thenComparing(p -> -p.lastJoin));
        this.list = res;
        int maxPage = Math.max(0, (list.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(page, 0), maxPage);
    }

    public int page() { return page; }

    public String filter() { return filter; }

    public ProfileManager.Profile profileAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return (slot >= 0 && slot < PER_PAGE && idx < list.size()) ? list.get(idx) : null;
    }

    public void open(Player viewer) {
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        inv = Bukkit.createInventory(this, 54, Gui.c("<dark_red>Игроки <gray>(" + (page + 1) + "/" + pages + ")"
                + (filter != null ? " <yellow>«" + Gui.esc(filter) + "»" : "")));
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm");

        for (int i = 0; i < PER_PAGE; i++) {
            ProfileManager.Profile p = profileAt(i);
            if (p == null) break;
            boolean online = plugin.profiles().isOnline(p.uuid);
            boolean cheat = ProfileManager.hasCheatNow(p);
            DetectionManager.Progress prog = plugin.detection().progress(p.uuid);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(p.uuid));
            meta.displayName(Gui.c(p.companion
                    ? "<aqua><bold>◆ [CW] </bold><gold><bold>" + Gui.esc(p.name) + "</bold>" + (prog != null ? " <yellow>⏳" : "")
                    : (cheat ? "<red>☠ " : online ? "<green>" : "<gray>") + Gui.esc(p.name)
                    + (prog != null ? " <yellow>⏳" : "")));

            List<Component> lore = new ArrayList<>();
            lore.add(Gui.c("<gray>Статус: " + (online ? "<green>онлайн" : "<red>оффлайн")));
            lore.add(Gui.c("<gray>Клиент: " + (p.brand.isEmpty() ? "<dark_gray>не передан" : "<white>" + Gui.esc(p.brand))
                    + (p.launcher.isEmpty() ? "" : " <dark_gray>(" + Gui.esc(p.launcher) + ")")));
            lore.add(Gui.c("<gray>Версия: " + (p.version.isEmpty() ? "<dark_gray>неизвестна" : "<white>" + Gui.esc(p.version))));
            if (p.companion) {
                lore.add(Gui.c("<aqua><bold>◆ [CW] ClientWatch Companion установлен</bold>"));
                lore.add(Gui.c("<aqua>Полный список модов и проверочные коды получены"));
                if (!p.companionVersion.isBlank()) lore.add(Gui.c("<gray>Версия мода проверки: <white>" + Gui.esc(p.companionVersion)));
            }
            if (prog != null) {
                lore.add(Gui.c("<yellow>⏳ Проверка ещё идёт: <white>" + prog.done() + "<yellow>/<white>" + prog.total()
                        + " <dark_gray>(моды ниже пока не окончательные)"));
            } else {
                String check = ProfileManager.checkText(p.checkState);
                lore.add(Gui.c("<gray>Проверка: " + (check != null ? check : "<dark_gray>нет данных")));
            }
            if (p.lastJoin > 0) lore.add(Gui.c("<gray>Последний заход: <white>" + fmt.format(new Date(p.lastJoin))));
            lore.add(Gui.c("<gray>Заходов: <white>" + p.session));

            List<String> now = new ArrayList<>();
            java.util.LinkedHashMap<String, ProfileManager.PMod> uniqueMods = new java.util.LinkedHashMap<>();
            for (ProfileManager.PMod m : p.mods.values()) {
                if (!p.present(m)) continue;
                String key = canonicalModId(m.id).toLowerCase(Locale.ROOT);
                if (key.isBlank()) key = m.display.toLowerCase(Locale.ROOT);
                ProfileManager.PMod old = uniqueMods.get(key);
                if (old == null || sourcePriority(m) > sourcePriority(old) ||
                        (sourcePriority(m) == sourcePriority(old) && m.last > old.last)) {
                    uniqueMods.put(key, m);
                }
            }
            for (ProfileManager.PMod m : uniqueMods.values()) {
                ModProject.Classification c = plugin.watch().classify(canonicalModId(m.id), m.display, m.sha512, m.sha1);
                String color = c.status() == null ? "<dark_purple>" : switch (c.status()) {
                    case FORBIDDEN -> "<red>";
                    case SUSPICIOUS -> "<yellow>";
                    case ALLOWED -> "<green>";
                    case LEGACY -> "<gray>";
                };
                now.add(color + Gui.esc(m.display));
            }
            lore.add(Component.empty());
            if (now.isEmpty()) {
                lore.add(Gui.c("<gray>Моды в последнем заходе: <dark_gray>не обнаружены"));
            } else {
                lore.add(Gui.c("<gray>Моды в последнем заходе (" + now.size() + "):"));
                for (int k = 0; k < now.size() && k < 10; k += 5) {
                    lore.add(Gui.c("  " + String.join("<gray>, ", now.subList(k, Math.min(k + 5, now.size())))));
                }
                if (now.size() > 10) lore.add(Gui.c("<dark_gray>  ...и ещё " + (now.size() - 10)));
                if (p.companion) {
                    lore.add(Gui.c("<dark_gray>Цвета модов: <red>■ запрещён <yellow>■ проверка <green>■ разрешён <gray>■ старая версия <dark_purple>■ не запрошен"));
                }
            }
            lore.add(Component.empty());
            if (p.companion) lore.add(Gui.c("<gold>◆ <gray>Мод полной проверки сообщает полный список модов"));
            lore.add(Gui.c("<green>ЛКМ <gray>- моды и история заходов"));
            if (plugin.access().canPumpkin(viewer)) {
                lore.add(Gui.c(plugin.pumpkin().has(p.uuid)
                        ? "<gold>ПКМ <gray>- снять тыкву <gold>(сейчас надета)"
                        : "<gold>ПКМ <gray>- надеть тыкву «Удали читы»"));
            }
            meta.lore(lore);
            if (p.companion) meta.setEnchantmentGlintOverride(true);
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        inv.setItem(SEARCH, Gui.item(Material.NAME_TAG, "<aqua>Поиск",
                filter == null ? "<gray>Фильтр: <white>нет" : "<gray>Фильтр: <yellow>" + Gui.esc(filter),
                "<gray>По нику, клиенту или названию мода", "<gray>Слово «чит» - только читерские клиенты", "",
                "<green>ЛКМ <gray>- искать", "<green>ПКМ <gray>- сбросить поиск"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>В главное меню",
                "<gray>Показано: <white>" + list.size() + " <gray>из <white>" + total));
        viewer.openInventory(inv);
    }


    private static String canonicalModId(String id) {
        if (id == null) return "";
        String value = id.trim();
        if (value.startsWith("cw:")) return value.substring(3);
        if (value.startsWith("fp:")) return value.substring(3);
        if (value.startsWith("ch:")) return value.substring(3);
        return value;
    }

    private static int sourcePriority(ProfileManager.PMod m) {
        if (m.isCompanion()) return 3;
        if (m.id.startsWith("fp:")) return 2;
        if (m.isChannel()) return 1;
        return 0;
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
