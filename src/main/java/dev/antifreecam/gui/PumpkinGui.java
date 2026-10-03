package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.manager.PumpkinManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Игроки, у которых включена тыква «Удали читы». Клик по голове - снять тыкву. */
public final class PumpkinGui implements InventoryHolder {

    public static final int PER_PAGE = 45, PREV = 45, EDIT = 47, BACK = 49, ADD = 51, NEXT = 53;

    private final AntiFreecam plugin;
    private final List<UUID> list;
    private final int page;
    private Inventory inv;

    public PumpkinGui(AntiFreecam plugin, int page) {
        this.plugin = plugin;
        this.list = plugin.pumpkin().uuids();
        int maxPage = Math.max(0, (list.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(page, 0), maxPage);
    }

    public int page() { return page; }

    public UUID uuidAt(int slot) {
        int idx = page * PER_PAGE + slot;
        return (slot >= 0 && slot < PER_PAGE && idx < list.size()) ? list.get(idx) : null;
    }

    public void open(Player viewer) {
        PumpkinManager pk = plugin.pumpkin();
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        inv = Bukkit.createInventory(this, 54, Gui.c("<gold>Тыква <gray>(" + (page + 1) + "/" + pages + ")"));

        for (int i = 0; i < PER_PAGE; i++) {
            UUID id = uuidAt(i);
            if (id == null) break;
            boolean online = plugin.profiles().isOnline(id);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            meta.displayName(Gui.c((online ? "<green>" : "<gray>") + Gui.esc(pk.nameOf(id))));
            List<Component> lore = new ArrayList<>();
            lore.add(Gui.c("<gray>Статус: " + (online ? "<green>онлайн, тыква на голове" : "<red>оффлайн <dark_gray>(наденется при заходе)")));
            lore.add(Component.empty());
            lore.add(Gui.c("<green>ЛКМ / ПКМ <gray>- снять тыкву"));
            lore.add(Gui.c("<dark_gray>Она пропадёт именно у этого игрока"));
            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        inv.setItem(EDIT, Gui.item(Material.ANVIL, "<gold>Название и описание",
                "<gray>Одинаково для всех тыкв", "<gray>Меняется сразу у всех игроков", "",
                "<green>Нажми, чтобы изменить"));
        inv.setItem(ADD, Gui.item(Material.CARVED_PUMPKIN, "<gold>Выдать тыкву по нику",
                "<gray>Игрок в сети - наденется сразу,", "<gray>не в сети - при заходе", "",
                "<green>ЛКМ <gray>- ввести ник в чате",
                "<gray>Или: <white>ПКМ по голове в списке игроков"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>В главное меню",
                "<gray>Игроков с тыквой: <white>" + list.size()));
        viewer.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
