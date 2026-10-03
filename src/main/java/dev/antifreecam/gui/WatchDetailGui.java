package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.util.SchedulerUtil;
import dev.antifreecam.watch.ModProject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Карточка найденного проекта: файлы 26.2, SHA-512, SHA-1/fingerprint и зависимости. */
public final class WatchDetailGui implements InventoryHolder {
    public static final int PER_PAGE = 45, PREV = 45, PROJECT = 47, BACK = 49, STATUS = 50, REMOVE = 51, ALL_VERSIONS = 52, NEXT = 53;

    private final AntiFreecam plugin;
    private final ModProject.SearchResult project;
    private final String query;
    private final int page;
    private final boolean backToSearch;
    private final ModProject.Entry preview;
    private Inventory inv;

    public WatchDetailGui(AntiFreecam plugin, ModProject.SearchResult project, String query, int page, boolean backToSearch) {
        this(plugin, project, query, page, backToSearch, null);
    }

    private WatchDetailGui(AntiFreecam plugin, ModProject.SearchResult project, String query, int page,
                           boolean backToSearch, ModProject.Entry preview) {
        this.plugin = plugin;
        this.project = project;
        this.query = query == null ? "" : query;
        this.page = Math.max(0, page);
        this.backToSearch = backToSearch;
        this.preview = preview;
    }

    public ModProject.SearchResult project() { return project; }
    public String query() { return query; }
    public int page() { return page; }
    public boolean backToSearch() { return backToSearch; }

    public void open(Player viewer) {
        ModProject.Entry stored = plugin.watch().get(project.key());
        if (stored == null && preview == null) {
            openLoading(viewer);
            plugin.watch().inspect(project, query).whenComplete((loaded, error) -> SchedulerUtil.onEntity(plugin, viewer, () -> {
                if (!viewer.isOnline()) return;
                // Если игрок успел уйти из окна загрузки, результат не должен
                // открыть его заново и перехватить следующий клик.
                if (viewer.getOpenInventory().getTopInventory().getHolder() != this) return;
                if (error != null) {
                    viewer.sendMessage("§c[Проверка] Не удалось получить данные о модификации: " + WatchSearchGui.rootMessage(error));
                    new WatchDetailGui(plugin, project, query, page, backToSearch, null).openStatic(viewer);
                    return;
                }
                new WatchDetailGui(plugin, project, query, page, backToSearch, loaded).openStatic(viewer);
            }));
            return;
        }
        openStatic(viewer);
    }

    private void openLoading(Player viewer) {
        inv = Bukkit.createInventory(this, 54, Gui.c("<aqua>Загрузка: <white>" + Gui.esc(project.title())));
        Gui.fill(inv);
        inv.setItem(22, Gui.item(Material.CLOCK, "<yellow>Получаю данные Modrinth/CurseForge",
                "<gray>Файлы 26.2, хэши и зависимости...") );
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>Назад"));
        viewer.openInventory(inv);
    }

    private void openStatic(Player viewer) {
        ModProject.Entry entry = plugin.watch().get(project.key());
        if (entry == null) entry = preview;
        List<ModProject.FileInfo> files = entry == null ? List.of() : entry.files();
        int pages = Math.max(1, (int) Math.ceil(files.size() / (double) PER_PAGE));
        int safePage = Math.min(page, pages - 1);
        if (safePage != page) {
            new WatchDetailGui(plugin, project, query, safePage, backToSearch, entry).openStatic(viewer);
            return;
        }

        inv = Bukkit.createInventory(this, 54, Gui.c("<dark_red>Мод: <white>" + Gui.esc(project.title())
                + " <gray>(" + (page + 1) + "/" + pages + ")"));

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= files.size()) break;
            ModProject.FileInfo f = files.get(index);
            Material mat = entry == null ? Material.WHITE_DYE : entry.status().material();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Версия игры: <white>26.2");
            lore.add("<gray>Версия модификации: <white>" + Gui.esc(f.version().isBlank() ? "не указана" : f.version()));
            lore.add("<gray>Файл: <white>" + Gui.esc(f.filename().isBlank() ? "не указан" : f.filename()));
            if (entry != null) lore.add("<gray>Состояние: " + WatchGui.statusColor(entry.status()) + entry.status().display());
            lore.add("<gray>Все версии проекта: " + (entry.allVersions() ? "<green>учитываются" : "<yellow>только известные файлы"));
            if (!f.sha512().isBlank()) {
                lore.add("<blue>SHA-512 файла (основной проверочный код):");
                lore.add("<gray>Это именно SHA-512 конкретного JAR-файла.");
                lore.addAll(wrapHash(f.sha512()));
            } else {
                lore.add("<blue>SHA-512 файла (основной проверочный код): <dark_gray>не получен");
                lore.add("<gray>Для этого файла нельзя выполнить точное сравнение по SHA-512.");
            }
            if (!f.sha1().isBlank()) {
                lore.add("<blue>SHA-1 файла (дополнительный проверочный код):");
                lore.add("<gray>Это SHA-1 того же конкретного JAR-файла.");
                lore.add("<white>" + Gui.esc(f.sha1()));
            }
            if (!f.fingerprint().isBlank()) {
                lore.add("<blue>CurseForge fingerprint (отдельный отпечаток):");
                lore.add("<white>" + Gui.esc(f.fingerprint()));
            }
            if (!f.dependencies().isEmpty()) {
                lore.add("");
                lore.add("<gray>Зависимости:");
                for (ModProject.Dependency d : f.dependencies()) {
                    String relation = dependencyRelation(d.relation());
                    lore.add("<gray>• <white>" + Gui.esc(d.name()) + " <dark_gray>(" + Gui.esc(relation) + ")");
                }
            }
            inv.setItem(i, Gui.item(mat, "<white>" + Gui.esc(f.filename().isBlank() ? project.title() : f.filename()),
                    lore.toArray(String[]::new)));
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(PROJECT, Gui.item(Material.BOOK, "<aqua>Карточка проекта",
                "<gray>Каталог: <white>" + project.platform().name(),
                "<gray>Автор: <white>" + Gui.esc(project.author().isBlank() ? "не указан" : project.author()),
                "<gray>Название в каталоге: <white>" + Gui.esc(project.slug().isBlank() ? project.projectId() : project.slug()),
                entry == null ? "<gray>Статус: <dark_gray>не добавлен" : "<gray>Статус: " + WatchGui.statusColor(entry.status()) + entry.status().display()));

        if (entry == null || plugin.watch().get(project.key()) == null) {
            inv.setItem(STATUS, Gui.item(Material.RED_DYE, "<red>Добавить в «Запрошенные моды»",
                    "<gray>После добавления он появится в разделе",
                    "<aqua>«Запрошенные моды»</aqua>.",
                    "<gray>Статус после добавления: <red>Запрещён",
                    "<gray>После этого статус можно изменить.",
                    "", "<green>ЛКМ <gray>- добавить"));
        } else {
            inv.setItem(STATUS, Gui.item(entry.status().material(), WatchGui.statusColor(entry.status()) + entry.status().display(),
                    "<gray>Следующий статус: <white>" + entry.status().next().display(),
                    "", "<yellow>ПКМ <gray>- сменить статус",
                    "<gray>Порядок: запрещён → требует проверки → разрешён → старая разрешённая версия"));
            inv.setItem(REMOVE, Gui.item(Material.BARRIER, "<red>Удалить из списка",
                    "<gray>Удалит модификацию из списка проверяемых", "", "<red>ЛКМ <gray>- удалить"));
            inv.setItem(ALL_VERSIONS, Gui.item(entry.allVersions() ? Material.LIME_DYE : Material.GRAY_DYE,
                    entry.allVersions() ? "<green>Все версии: УЧИТЫВАЮТСЯ" : "<yellow>Только известные файлы",
                    "<gray>При включении статус этой записи",
                    "<gray>будет применяться к любой версии этого проекта.",
                    "<gray>Например: Freecam 1.0, 1.5, 2.0 и т.д.",
                    "", "<green>ЛКМ <gray>- переключить"));
        }
        inv.setItem(BACK, Gui.item(Material.BARRIER, backToSearch ? "<red>Назад к результатам" : "<red>Назад к списку"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        viewer.openInventory(inv);
    }

    private static String dependencyRelation(String relation) {
        if (relation == null || relation.isBlank()) return "обязательная";
        return switch (relation.toLowerCase(java.util.Locale.ROOT)) {
            case "required", "mandatory" -> "обязательная";
            case "optional" -> "необязательная";
            case "incompatible" -> "несовместимая";
            case "embedded" -> "встроенная";
            default -> relation;
        };
    }

    private static List<String> wrapHash(String hash) {
        String h = hash.toLowerCase();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < h.length(); i += 48) out.add("<white>" + h.substring(i, Math.min(h.length(), i + 48)));
        return out;
    }

    @Override public @NotNull Inventory getInventory() { return inv; }
}
