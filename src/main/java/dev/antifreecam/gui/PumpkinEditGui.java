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
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Редактор названия и описания тыквы. Шаблон общий: изменения сразу видны у всех, у кого тыква надета. */
public final class PumpkinEditGui implements InventoryHolder {

    public static final int NAME = 10, LORE = 11, PREVIEW = 13, TAKE = 15, RESET = 16, BACK = 22;

    private final AntiFreecam plugin;
    private Inventory inv;

    public PumpkinEditGui(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer) {
        inv = Bukkit.createInventory(this, 27, Gui.c("<gold>Тыква: название и описание"));
        render();
        viewer.openInventory(inv);
    }

    /** Перерисовывает уже открытое меню (без закрытия - чтобы не терять предмет на курсоре). */
    public void render() {
        PumpkinManager pk = plugin.pumpkin();
        Gui.fill(inv);

        inv.setItem(NAME, Gui.item(Material.NAME_TAG, "<yellow>Название",
                "<gray>Сейчас: <white>" + Gui.esc(pk.templateName()), "",
                "<gray>Можно цвета и градиент:",
                "<white>" + Gui.esc("<gradient:#ff0000:#ffff00>Текст</gradient>"),
                "<white>" + Gui.esc("&c &l &#ff8800"), "",
                "<green>Нажми, потом напиши в чат"));

        List<String> loreLines = new ArrayList<>();
        loreLines.add("<gray>Строк сейчас: <white>" + pk.templateLore().size() + "<gray>/" + PumpkinManager.MAX_LORE);
        int n = 1;
        for (String s : pk.templateLore()) loreLines.add("<dark_gray>" + n++ + ". <white>" + Gui.esc(s));
        loreLines.add("");
        loreLines.add("<green>ЛКМ <gray>- добавить строку");
        loreLines.add("<green>ПКМ <gray>- удалить последнюю");
        loreLines.add("<green>Shift+ПКМ <gray>- очистить всё");
        loreLines.add("<dark_gray>" + Gui.esc("Точечно: /afc pumpkin lore set <n> <текст>"));
        inv.setItem(LORE, Gui.item(Material.WRITABLE_BOOK, "<yellow>Описание", loreLines.toArray(new String[0])));

        ItemStack preview = pk.build(false); // без метки: это только показ
        ItemMeta pm = preview.getItemMeta();
        List<Component> pl = new ArrayList<>();
        if (pm.lore() != null) pl.addAll(pm.lore());
        pl.add(Component.empty());
        pl.add(Gui.c("<dark_gray>Так тыква выглядит у игроков"));
        pm.lore(pl);
        preview.setItemMeta(pm);
        inv.setItem(PREVIEW, preview);

        inv.setItem(TAKE, Gui.item(Material.ITEM_FRAME, "<aqua>Взять с предмета",
                "<gray>Название и описание копируются", "<gray>с предмета - он остаётся у тебя", "",
                "<green>Кликни на этот слот предметом",
                "<gray>или держи его в руке и просто нажми"));
        inv.setItem(RESET, Gui.item(Material.REDSTONE, "<red>Сбросить",
                "<gray>Вернуть стандартные название", "<gray>и описание", "",
                "<red>Shift+ЛКМ <gray>- подтвердить"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>Назад"));
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
