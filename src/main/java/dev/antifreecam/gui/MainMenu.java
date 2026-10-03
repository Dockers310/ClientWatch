package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.manager.AccessManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MainMenu implements InventoryHolder {

    public static final int CONFIDENCE = 8, FLAGS = 10, NOTIFY = 12, ACCESS = 14, CHECK_ALL = 16, WATCH = 20, HELP = 22, PLAYERS = 24, PUMPKIN = 26, RESET_STATS = 18;

    private final AntiFreecam plugin;
    private Inventory inv;

    public MainMenu(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        AccessManager acc = plugin.access();
        inv = Bukkit.createInventory(this, 27, Gui.c("<dark_red>ClientWatch"));
        Gui.fill(inv);

        int count = plugin.detection().flags().size();
        inv.setItem(CONFIDENCE, Gui.item(Material.CLOCK, "<aqua>Что именно подтверждает ClientWatch",
                "<gray>Обычная проверка: <yellow>не 100%",
                "<gray>она видит только проверяемые признаки",
                "<gray>и отсутствие срабатывания ничего не доказывает.",
                "",
                "<gold>Мод полной проверки: <green>полный список модов",
                "<gray>+ проверочные коды файлов, когда файлы доступны.",
                "<dark_gray>Это всё равно не криптографические 100%:",
                "<dark_gray>модифицированный клиент может лгать серверу.",
                "",
                "<gold>Золотой жирный игрок <gray>- установлен мод полной проверки",
                "<green>Зелёный <gray>- разрешённый",
                "<red>Красный <gray>- запрещённый",
                "<yellow>Жёлтый <gray>- требует проверки",
                "<gray>Серый <gray>- старая разрешённая версия",
                "<dark_purple>Фиолетовый <gray>- файл не подтверждён или отсутствует в списке."));

        inv.setItem(FLAGS, Gui.item(Material.SPYGLASS, "<yellow>Срабатывания",
                "<gray>Игроков с Freecam: <white>" + count,
                "", "<green>Нажми, чтобы открыть"));

        if (acc.hasNotifyRight(p)) {
            boolean on = acc.isNotifyOn(p);
            inv.setItem(NOTIFY, Gui.item(on ? Material.LIME_DYE : Material.RED_DYE,
                    "<yellow>Уведомления в чат: " + (on ? "<green>ВКЛ" : "<red>ВЫКЛ"),
                    "<gray>Срабатывания приходят вам в чат", "<gray>Команда: <white>/afc not",
                    "", "<green>Нажми, чтобы переключить"));
        } else {
            inv.setItem(NOTIFY, Gui.item(Material.BARRIER, "<red>Уведомления недоступны",
                    "<gray>Доступ выдаёт администратор"));
        }

        if (acc.isManager(p)) {
            inv.setItem(ACCESS, Gui.item(Material.PLAYER_HEAD, "<gold>Доступ",
                    "<gray>Кому разрешено меню, уведомления и тыква", "", "<green>Нажми, чтобы открыть"));
            inv.setItem(RESET_STATS, Gui.item(Material.LAVA_BUCKET, "<red>Сбросить статистику",
                    "<gray>Удалит срабатывания (<white>" + count + "<gray>) и профили (<white>" + plugin.profiles().all().size() + "<gray>):",
                    "<gray>моды, клиенты, историю заходов", "<dark_gray>Доступы, исключения и тыква останутся", "",
                    "<red>Shift+ЛКМ <gray>- подтвердить"));
            inv.setItem(CHECK_ALL, Gui.item(Material.COMPASS, "<aqua>Правило проверки",
                    "<gray>Проверка выполняется только при входе.",
                    "<gray>Во время игры перепроверка отключена."));
        }
        inv.setItem(WATCH, Gui.item(Material.NETHER_STAR, "<gold>Запрошенные модификации",
                "<gray>Записей: <white>" + plugin.watch().size(),
                "<gray>Поиск по каталогам Modrinth и CurseForge",
                "<gray>Запрещённые, требующие проверки, разрешённые и старые версии", "", "<green>Нажми, чтобы открыть"));
        inv.setItem(PLAYERS, Gui.item(Material.PLAYER_HEAD, "<aqua>Все игроки",
                "<gray>Профили: <white>" + plugin.profiles().all().size(),
                "<gray>Клиент, проверка, моды и история заходов", "", "<green>Нажми, чтобы открыть"));
        if (acc.canPumpkin(p)) {
            inv.setItem(PUMPKIN, Gui.item(Material.CARVED_PUMPKIN, "<gold>Тыква «Удали читы»",
                    "<gray>Игроков с тыквой: <white>" + plugin.pumpkin().count(),
                    "<gray>Список, выдача, название и описание", "", "<green>Нажми, чтобы открыть"));
        }
        inv.setItem(HELP, Gui.item(Material.BOOK, "<light_purple>Справка по командам",
                "<gray>Все команды и как читать сработки", "", "<green>Нажми, чтобы открыть"));
        p.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
