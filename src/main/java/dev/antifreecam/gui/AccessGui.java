package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.manager.AccessManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class AccessGui implements InventoryHolder {

    public static final int PER_PAGE = 45, PREV = 45, BACK = 49, NEXT = 53;

    private final AntiFreecam plugin;
    private final List<String> names;
    private final int page;
    private Inventory inv;

    public AccessGui(AntiFreecam plugin, int page) {
        this.plugin = plugin;
        this.names = plugin.access().listable();
        int maxPage = Math.max(0, (names.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(page, 0), maxPage);
    }

    public int page() { return page; }

    public String nameAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return (slot >= 0 && slot < PER_PAGE && idx < names.size()) ? names.get(idx) : null;
    }

    public void open(Player viewer) {
        AccessManager acc = plugin.access();
        int pages = Math.max(1, (int) Math.ceil(names.size() / (double) PER_PAGE));
        inv = Bukkit.createInventory(this, 54, Gui.c("<gold>Доступ <gray>(" + (page + 1) + "/" + pages + ")"));

        for (int i = 0; i < PER_PAGE; i++) {
            String name = nameAt(i);
            if (name == null) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            java.util.UUID onlineId = plugin.profiles().onlineUuidExact(name);
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
            if (op != null) meta.setOwningPlayer(op);
            meta.displayName(Gui.c("<yellow>" + Gui.esc(name) + (onlineId != null ? " <green>●" : " <gray>●")));
            meta.lore(List.of(
                    Gui.c("<gray>Меню: " + (acc.hasMenu(name) ? "<green>✔ разрешено" : "<red>✘ нет")),
                    Gui.c("<gray>Уведомления: " + (acc.hasNotify(name) ? "<green>✔ разрешено" : "<red>✘ нет")),
                    Gui.c("<gray>Тыква: " + (acc.hasPumpkin(name) ? "<green>✔ разрешено" : "<red>✘ нет")),
                    Gui.c(""),
                    Gui.c("<green>ЛКМ <gray>- вкл/выкл меню"),
                    Gui.c("<green>ПКМ <gray>- вкл/выкл уведомления"),
                    Gui.c("<green>Shift+ПКМ <gray>- выдать/снять доступ к тыкве"),
                    Gui.c("<green>Shift+ЛКМ <gray>- убрать доступ к тыкве")));
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>В главное меню",
                "<gray>OP и владельцы имеют полный доступ", "<gray>и здесь не отображаются"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        viewer.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
