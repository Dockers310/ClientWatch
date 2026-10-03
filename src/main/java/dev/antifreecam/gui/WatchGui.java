package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.watch.ModProject;
import dev.antifreecam.watch.WatchRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Список модификаций, добавленных в watchlist.yml. */
public final class WatchGui implements InventoryHolder {
    public static final int PER_PAGE = 45, PREV = 45, ADD = 46, SEARCH = 47, BACK = 49, INFO = 51, NEXT = 53;

    private final AntiFreecam plugin;
    private final List<ModProject.Entry> entries;
    private final int page;
    private final String query;
    private Inventory inv;

    public WatchGui(AntiFreecam plugin, int page) {
        this(plugin, page, null);
    }

    public WatchGui(AntiFreecam plugin, int page, String query) {
        this.plugin = plugin;
        this.query = query == null || query.isBlank() ? null : query.strip();
        this.entries = query == null ? plugin.watch().all() : plugin.watch().related(query);
        int max = Math.max(0, (entries.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(0, page), max);
    }

    public int page() { return page; }
    public String query() { return query; }

    public ModProject.Entry entryAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return slot >= 0 && slot < PER_PAGE && idx < entries.size() ? entries.get(idx) : null;
    }

    public void open(Player viewer) {
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) PER_PAGE));
        inv = Bukkit.createInventory(this, 54, Gui.c("<dark_red>Запрошенные моды <gray>("
                + (page + 1) + "/" + pages + ")" + (query == null ? "" : " <yellow>«" + Gui.esc(query) + "»")));
        for (int i = 0; i < PER_PAGE; i++) {
            ModProject.Entry e = entryAt(i);
            if (e == null) break;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Статус: " + statusColor(e.status()) + e.status().display());
            lore.add("<gray>Каталог: <white>" + e.project().platform().name());
            lore.add("<gray>Автор: <white>" + Gui.esc(e.project().author().isBlank() ? "не указан" : e.project().author()));
            lore.add("<gray>Файлов 26.2: <white>" + e.files().size());
            lore.add("<gray>Применение ко всем версиям: " + (e.allVersions() ? "<green>ДА" : "<yellow>НЕТ"));
            String hash = firstHash(e);
            lore.add("<blue>SHA-512 файла (проверочный код): " + (hash.isBlank() ? "<dark_gray>не получен" : "<white>" + shortHash(hash)));
            if (e.files().stream().anyMatch(f -> !f.dependencies().isEmpty())) lore.add("<gray>Есть зависимости");
            lore.add("");
            lore.add("<green>ЛКМ <gray>- открыть подробности");
            lore.add("<yellow>ПКМ <gray>- сменить статус");
            lore.add("<red>Shift+ПКМ <gray>- удалить из списка");
            inv.setItem(i, Gui.item(e.status().material(),
                    statusColor(e.status()) + Gui.esc(e.project().title()), lore.toArray(String[]::new)));
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(ADD, Gui.item(Material.ANVIL, "<gold>Добавить модификацию",
                "<gray>Новая запись попадёт в раздел <aqua>«Запрошенные моды»</aqua>.",
                "<gray>Введи название или часть названия:",
                "<gray>например: <white>autototem</white>, <white>freecam</white>",
                "", "<green>ЛКМ <gray>- поиск по каталогам Modrinth и CurseForge"));
        inv.setItem(SEARCH, Gui.item(Material.NAME_TAG, "<aqua>Фильтр списка",
                query == null ? "<gray>Фильтр не установлен" : "<gray>Фильтр: <yellow>" + Gui.esc(query),
                "", "<green>ЛКМ <gray>- искать", "<green>ПКМ <gray>- сбросить"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>В главное меню"));
        inv.setItem(INFO, Gui.item(Material.BOOK, "<light_purple>Как читать цвета и коды",
                "<red>Красный <gray>- запрещённый",
                "<yellow>Жёлтый <gray>- требует проверки",
                "<green>Зелёный <gray>- разрешённый",
                "<gray>Серый <gray>- старая разрешённая версия",
                "<dark_purple>Фиолетовый <gray>- файл не подтверждён или отсутствует",
                "",
                "<aqua>SHA-512 <gray>- основной полный отпечаток JAR-файла.",
                "<aqua>SHA-1 <gray>- дополнительный отпечаток того же файла.",
                "<aqua>CurseForge fingerprint <gray>- отдельный отпечаток CurseForge.",
                "<gray>Коды относятся к конкретному файлу и версии,",
                "<gray>а не ко всем файлам мода сразу."));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        viewer.openInventory(inv);
    }

    static String statusColor(dev.antifreecam.watch.WatchStatus s) {
        return switch (s) {
            case FORBIDDEN -> "<red>☠ ";
            case SUSPICIOUS -> "<yellow>⚠ ";
            case ALLOWED -> "<green>✓ ";
            case LEGACY -> "<gray>▣ ";
        };
    }

    static String firstHash(ModProject.Entry e) {
        for (ModProject.FileInfo f : e.files()) if (!f.sha512().isBlank()) return f.sha512();
        return "";
    }

    static String shortHash(String hash) {
        return hash.length() <= 16 ? hash : hash.substring(0, 16) + "...";
    }

    @Override public @NotNull Inventory getInventory() { return inv; }
}
