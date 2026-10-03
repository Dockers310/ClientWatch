package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.manager.ProfileManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class FlagGui implements InventoryHolder {

    public static final int PER_PAGE = 45, PREV = 45, SEARCH = 47, BACK = 49, REFRESH = 51, NEXT = 53;

    private final AntiFreecam plugin;
    private final List<DetectionManager.Flag> flags;
    private final int total;
    private final int page;
    private final String filter;
    private Inventory inv;

    public FlagGui(AntiFreecam plugin, int page) {
        this(plugin, page, null);
    }

    public FlagGui(AntiFreecam plugin, int page, String filter) {
        this.plugin = plugin;
        this.filter = (filter == null || filter.isBlank()) ? null : filter.strip();
        List<DetectionManager.Flag> all = plugin.detection().flags();
        this.total = all.size();
        if (this.filter == null) {
            this.flags = all;
        } else {
            String q = this.filter.toLowerCase(Locale.ROOT);
            this.flags = all.stream()
                    .filter(f -> f.name.toLowerCase(Locale.ROOT).contains(q)
                            || f.mods.keySet().stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains(q))
                            || plugin.profiles().matches(f.uuid, q)
                            || ((q.equals("чит") || q.equals("cheat")) && plugin.detection().hasCheat(f)))
                    .toList();
        }
        int maxPage = Math.max(0, (flags.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(page, 0), maxPage);
    }

    public int page() { return page; }

    public String filter() { return filter; }

    public DetectionManager.Flag flagAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return (slot >= 0 && slot < PER_PAGE && idx < flags.size()) ? flags.get(idx) : null;
    }

    public void open(Player viewer) {
        boolean manager = plugin.access().isManager(viewer);
        int pages = Math.max(1, (int) Math.ceil(flags.size() / (double) PER_PAGE));
        String title = "<dark_red>Freecam <gray>(" + (page + 1) + "/" + pages + ")"
                + (filter != null ? " <yellow>«" + Gui.esc(filter) + "»" : "");
        inv = Bukkit.createInventory(this, 54, Gui.c(title));

        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm:ss");
        for (int i = 0; i < PER_PAGE; i++) {
            DetectionManager.Flag f = flagAt(i);
            if (f == null) break;
            boolean online = plugin.profiles().isOnline(f.uuid);
            boolean exempt = plugin.isExemptFlag(f);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(f.uuid));
            boolean cheat = plugin.detection().hasCheat(f);
            meta.displayName(Gui.c((cheat ? "<red>" : exempt ? "<green>" : "<yellow>") + Gui.esc(f.name)
                    + (cheat ? " <dark_red>☠ ЧИТ" : exempt ? " <dark_green>✔ исключение" : "")));
            List<Component> lore = new ArrayList<>();
            lore.add(Gui.c("<gray>Статус: " + (online ? "<green>онлайн"
                    : "<red>оффлайн" + (f.left > 0 ? " <dark_gray>(вышел " + fmt.format(new Date(f.left)) + ")" : ""))));
            lore.add(Gui.c("<gray>Найден: <white>" + fmt.format(new Date(f.firstSeen))));
            lore.add(Gui.c("<gray>Проверен: <white>" + fmt.format(new Date(f.lastSeen))));
            ProfileManager.Profile pr = plugin.profiles().get(f.uuid);
            lore.add(Gui.c("<gray>Клиент: " + (pr == null || pr.brand.isEmpty() ? "<dark_gray>не передан" : "<white>" + Gui.esc(pr.brand))
                    + (pr != null && !pr.launcher.isEmpty() ? " <dark_gray>(" + Gui.esc(pr.launcher) + ")" : "")));
            lore.add(Gui.c("<gray>Версия: " + (pr == null || pr.version.isEmpty() ? "<dark_gray>неизвестна" : "<white>" + Gui.esc(pr.version))));
            if (exempt && !cheat) lore.add(Gui.c("<gray>Уведомления по нему <green>не приходят"));
            if (exempt && cheat) lore.add(Gui.c("<gray>В исключениях, но <red>читерский клиент - уведомления приходят"));
            lore.add(Component.empty());
            lore.add(Gui.c("<gray>Freecam:"));
            f.mods.forEach((mod, key) -> lore.add(Gui.c(plugin.detection().isCheat(mod)
                    ? "<dark_red>☠ <red>" + Gui.esc(mod) + (key.isEmpty() ? "" : " <dark_gray>[" + Gui.esc(key) + "]") + " <dark_red>читерский клиент"
                    : "<red>• <white>" + Gui.esc(mod) + (key.isEmpty() ? "" : " <dark_gray>[" + Gui.esc(key) + "]"))));
            if (pr != null) {
                List<String> marked = new ArrayList<>();
                for (ProfileManager.PMod pm : pr.mods.values()) {
                    if (plugin.profiles().isWatched(pm.id)) {
                        marked.add((pr.present(pm) ? "<white>" : "<gray>") + Gui.esc(pm.display) + (pr.present(pm) ? "" : " <dark_gray>(раньше)"));
                    }
                }
                if (!marked.isEmpty()) {
                    lore.add(Component.empty());
                    lore.add(Gui.c("<gold>⭐ Отмеченные моды у него:"));
                    for (String line : marked) lore.add(Gui.c("<gold>• " + line));
                }
            }
            lore.add(Component.empty());
            lore.add(Gui.c("<green>ЛКМ <gray>- список его модов"));
            if (manager) {
                lore.add(Gui.c("<green>Shift+ЛКМ <gray>- " + (plugin.isExemptName(f.name) ? "убрать из исключений" : "в исключения")));
                lore.add(Gui.c("<green>ПКМ <gray>- убрать из списка"));
            }
            if (plugin.access().canPumpkin(viewer)) {
                lore.add(Gui.c(plugin.pumpkin().has(f.uuid)
                        ? "<gold>Shift+ПКМ <gray>- снять тыкву <gold>(сейчас надета)"
                        : "<gold>Shift+ПКМ <gray>- надеть тыкву «Удали читы»"));
            }
            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(SEARCH, Gui.item(Material.NAME_TAG, "<aqua>Поиск",
                filter == null ? "<gray>Фильтр: <white>нет" : "<gray>Фильтр: <yellow>" + Gui.esc(filter),
                "<gray>По нику или названию мода", "",
                "<green>ЛКМ <gray>- искать", "<green>ПКМ <gray>- сбросить поиск"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>В главное меню",
                "<gray>Показано: <white>" + flags.size() + " <gray>из <white>" + total));
        inv.setItem(REFRESH, Gui.item(Material.CLOCK, "<aqua>Проверка при входе",
                "<gray>Новые проверки выполняются только при входе.",
                "<gray>Во время игры перепроверки отключены."));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        viewer.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
