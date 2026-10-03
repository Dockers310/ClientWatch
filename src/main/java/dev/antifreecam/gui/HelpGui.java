package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class HelpGui implements InventoryHolder {

    public static final int BACK = 40;
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final AntiFreecam plugin;
    private Inventory inv;

    public HelpGui(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        boolean manager = plugin.access().isManager(p);
        inv = Bukkit.createInventory(this, 45, Gui.c("<dark_red>ClientWatch <gray>- справка"));
        Gui.fill(inv);

        List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
        items.add(Gui.item(Material.BOOK, "<gold>Как читать сработку",
                "<white>[Проверка] Ник → Мод [F4]",
                "<gray>Мод - название из config.yml,", "<gray>в скобках - клавиша мода у игрока.",
                "<gray>Клик по сообщению открывает список."));
        items.add(Gui.item(Material.BARRIER, "<red>Важно про определение",
                "<gray>Плагин не видит список модов клиента", "<gray>напрямую - только то, что клиент сам",
                "<gray>выдаёт (клавиши, переводы, сетевые", "<gray>каналы). Моды без сети и не из базы",
                "<gray>плагина останутся незамеченными."));
        items.add(cmd("/afc", "Открыть главное меню."));
        items.add(cmd("/afc list", "Список срабатываний.", "Игроки не в сети тоже видны."));
        items.add(cmd("/afc search <текст>", "Поиск в списке по нику или названию мода."));
        items.add(cmd("/afc mods <ник>", "Список модов игрока: белые - сейчас,", "серые - были раньше. ЛКМ - отметить."));
        items.add(cmd("/afc players", "Все игроки: клиент, проверка, моды.", "Есть поиск (слово «чит» - только читы)."));
        items.add(cmd("/afc history <ник>", "История заходов игрока: каждый заход", "отдельным предметом, моды по 5 в строке."));
        items.add(cmd("/afc not", "Включить/выключить уведомления", "в чат. По умолчанию выключены."));
        items.add(cmd("/afc info <ник>", "Какой Freecam у игрока и когда найден.", "Работает и для игроков не в сети."));
        items.add(cmd("/afc check <ник>", "Перепроверить игрока (он должен быть онлайн)."));
        if (manager) {
            items.add(cmd("/afc test <ник>", "Диагностика: результат по каждому", "ключу из конфига. Работает даже для", "игроков из исключений."));
            items.add(cmd("/afc probe <ник> <ключ>", "Проверить любой keybind-ключ", "(до 4 ключей). Для поиска ключей модов."));
            items.add(cmd("/afc access add|remove|list", "Доступ к меню, уведомлениям и тыкве:", "/afc access add Ник [menu|notify|pumpkin|all]"));
            items.add(cmd("/afc exempt add|remove|list", "Исключения: этих игроков не проверяем.", "/afc exempt add Ник"));
            items.add(cmd("/afc resetstats", "Сбросить всю статистику: срабатывания,", "профили, моды, историю заходов.", "Нужно подтверждение: ... confirm"));
            items.add(cmd("/afc resetmods", "Вернуть список модов в config.yml", "к стандартному (после обновления плагина)."));
        items.add(cmd("/afc reload", "Перезагрузить config.yml."));
        }
        if (plugin.access().canPumpkin(p)) {
            items.add(cmd("/afc p", "Короткая команда тыквы: открывает меню.",
                    "/afc p on|off <ник> — надеть / снять тыкву.", "Полная команда /afc pumpkin ... тоже остаётся доступна.", "Клик ПКМ по голове в «Все игроки»."));
        }
        for (int i = 0; i < items.size() && i < SLOTS.length; i++) inv.setItem(SLOTS[i], items.get(i));

        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>В главное меню"));
        p.openInventory(inv);
    }

    private org.bukkit.inventory.ItemStack cmd(String command, String... desc) {
        String[] lore = new String[desc.length];
        for (int i = 0; i < desc.length; i++) lore[i] = "<gray>" + Gui.esc(desc[i]);
        return Gui.item(Material.PAPER, "<yellow>" + Gui.esc(command), lore);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
