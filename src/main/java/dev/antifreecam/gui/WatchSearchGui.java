package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.util.SchedulerUtil;
import dev.antifreecam.watch.ModProject;
import dev.antifreecam.watch.WatchStatus;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WatchSearchGui implements InventoryHolder {
    public static final int PER_PAGE = 45, PREV = 45, QUERY = 46, BACK = 49, NEXT = 53;

    private final AntiFreecam plugin;
    private final String query;
    private final List<ModProject.SearchResult> results;
    private final int page;
    private Inventory inv;

    public WatchSearchGui(AntiFreecam plugin, String query, int page) {
        this(plugin, query, page, List.of());
    }

    private WatchSearchGui(AntiFreecam plugin, String query, int page, List<ModProject.SearchResult> results) {
        this.plugin = plugin;
        this.query = query;
        this.results = results == null ? List.of() : List.copyOf(results);
        int max = Math.max(0, (this.results.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(0, page), max);
    }

    public int page() { return page; }
    public String query() { return query; }

    public ModProject.SearchResult resultAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return slot >= 0 && slot < PER_PAGE && idx < results.size() ? results.get(idx) : null;
    }

    public void start(Player viewer) {
        inv = Bukkit.createInventory(this, 54, Gui.c("<aqua>Поиск: <white>" + Gui.esc(query)));
        Gui.fill(inv);
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>Назад к запросам"));
        inv.setItem(QUERY, Gui.item(Material.NAME_TAG, "<yellow>" + Gui.esc(query)));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        viewer.openInventory(inv);

        plugin.watch().search(query).whenComplete((found, error) -> {
            SchedulerUtil.onEntity(plugin, viewer, () -> {
                if (!viewer.isOnline()) return;
                // Не крадём у игрока открытое меню, если он успел перейти в другой GUI,
                // пока шёл сетевой поиск.
                if (viewer.getOpenInventory().getTopInventory().getHolder() != this) return;
                if (error != null) {
                    viewer.sendMessage("§c[Проверка] Ошибка поиска модов: " + rootMessage(error));
                    new WatchSearchGui(plugin, query, page, List.of()).open(viewer);
                    return;
                }
                new WatchSearchGui(plugin, query, page, found).open(viewer);
            });
        });
    }

    public void open(Player viewer) {
        int pages = Math.max(1, (int) Math.ceil(results.size() / (double) PER_PAGE));
        inv = Bukkit.createInventory(this, 54, Gui.c("<aqua>Результаты: <white>" + Gui.esc(query)
                + " <gray>(" + (page + 1) + "/" + pages + ")"));

        for (int i = 0; i < PER_PAGE; i++) {
            ModProject.SearchResult r = resultAt(i);
            if (r == null) break;
            ModProject.Entry e = plugin.watch().get(r.key());
            Material mat = e == null ? Material.WHITE_DYE : e.status().material();
            String status = e == null
                    ? "<dark_gray>ещё не добавлен"
                    : WatchGui.statusColor(e.status()) + e.status().display();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Каталог: <white>" + r.platform().name());
            lore.add("<gray>Автор: <white>" + Gui.esc(r.author().isBlank() ? "не указан" : r.author()));
            lore.add("<gray>Состояние: " + status);
            if (!r.description().isBlank()) {
                String d = r.description().replace('\n', ' ');
                if (d.length() > 120) d = d.substring(0, 117) + "...";
                lore.add("<gray>" + Gui.esc(d));
            }
            lore.add("");
            lore.add("<green>ЛКМ <gray>- открыть карточку");
            inv.setItem(i, Gui.item(mat, "<white>" + Gui.esc(r.title()), lore.toArray(String[]::new)));
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>Назад к запросам"));
        inv.setItem(QUERY, Gui.item(Material.NAME_TAG, "<yellow>Запрос: <white>" + Gui.esc(query),
                "<gray>Найдено: <white>" + results.size(),
                "<gray>Поиск по каталогам Modrinth и CurseForge",
                "", "<green>ЛКМ <gray>- повторить поиск"));
        viewer.openInventory(inv);
    }

    static String rootMessage(Throwable e) {
        Throwable x = e;
        while (x.getCause() != null) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }

    @Override public @NotNull Inventory getInventory() { return inv; }
}
