package dev.antifreecam.command;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.gui.FlagGui;
import dev.antifreecam.gui.HelpGui;
import dev.antifreecam.gui.HistoryGui;
import dev.antifreecam.gui.MainMenu;
import dev.antifreecam.gui.ModsGui;
import dev.antifreecam.gui.PlayersGui;
import dev.antifreecam.gui.WatchGui;
import dev.antifreecam.gui.PumpkinEditGui;
import dev.antifreecam.gui.PumpkinGui;
import dev.antifreecam.manager.AccessManager;
import dev.antifreecam.manager.ProfileManager;
import dev.antifreecam.manager.PumpkinManager;
import dev.antifreecam.util.SchedulerUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public final class AfcCommand implements TabExecutor {

    private final AntiFreecam plugin;

    public AfcCommand(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        AccessManager acc = plugin.access();
        boolean pumpkinCommand = a.length > 0 && (a[0].equalsIgnoreCase("pumpkin") || a[0].equalsIgnoreCase("тыква") || a[0].equalsIgnoreCase("p"));
        if (pumpkinCommand) {
            if (!acc.canPumpkin(s)) {
                s.sendMessage("§cНет доступа к меню тыквы.");
                return true;
            }
        } else if (!acc.canView(s)) {
            s.sendMessage("§cУ вас нет доступа к меню ClientWatch.");
            return true;
        }
        boolean manager = acc.isManager(s);

        if (a.length == 0) {
            if (s instanceof Player p) new MainMenu(plugin).open(p);
            else s.sendMessage("/afc <help|list|search|players|mods|history|watch|not|info|check|test|probe|access|exempt|pumpkin|resetstats|resetmods|reload>");
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "list", "gui" -> {
                if (s instanceof Player p) new FlagGui(plugin, 0).open(p);
                else s.sendMessage("Эта команда доступна только игроку.");
            }
            case "not", "notify" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return true; }
                if (!acc.hasNotifyRight(p)) { s.sendMessage("§cУ вас нет доступа к уведомлениям."); return true; }
                boolean on = acc.toggleNotifyOn(p);
                s.sendMessage(on ? "§a[Проверка] Уведомления включены." : "§7[Проверка] Уведомления выключены.");
            }
            case "check", "recheck" -> {
                if (a.length < 2) {
                    s.sendMessage("§e/afc check <ник> §7— перепроверить одного игрока сейчас.");
                    return true;
                }
                Player target = plugin.profiles().onlinePlayerExact(a[1]);
                if (target == null) {
                    s.sendMessage("§cИгрок §f" + a[1] + " §cдолжен находиться онлайн.");
                    return true;
                }
                plugin.detection().recheck(target);
                s.sendMessage("§aПерепроверка запущена только для игрока §f" + target.getName() + "§a.");
            }
            case "help", "?" -> {
                if (s instanceof Player p) new HelpGui(plugin).open(p);
                else s.sendMessage("/afc <list|search|players|mods|history|watch|not|info|check|test|probe|access|exempt|pumpkin|resetmods|reload>");
            }
            case "search", "find" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return true; }
                String q = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null;
                new FlagGui(plugin, 0, q).open(p);
            }
            case "watch", "requested", "запрошенные" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return true; }
                new WatchGui(plugin, 0).open(p);
            }
            case "players" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return true; }
                new PlayersGui(plugin, 0, a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null).open(p);
            }
            case "history" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return true; }
                if (a.length < 2) { s.sendMessage("§c/afc history <ник>"); return true; }
                ProfileManager.Profile pr = plugin.profiles().find(a[1]);
                if (pr == null) { s.sendMessage("§7Нет данных об игроке §f" + a[1] + "§7."); return true; }
                new HistoryGui(plugin, pr, 0, false).open(p);
            }
            case "mods" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return true; }
                if (a.length < 2) { s.sendMessage("§c/afc mods <ник>"); return true; }
                ProfileManager.Profile pr = plugin.profiles().find(a[1]);
                if (pr == null) {
                    DetectionManager.Flag f = plugin.detection().findFlag(a[1]);
                    if (f != null) pr = plugin.profiles().ensure(f);
                }
                if (pr == null) { s.sendMessage("§7Нет данных о модах игрока §f" + a[1] + "§7."); return true; }
                new ModsGui(plugin, pr, 0).open(p);
            }
            case "info" -> {
                if (a.length < 2) { s.sendMessage("§c/afc info <ник>"); return true; }
                info(s, a[1]);
            }
            case "test" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                s.sendMessage("§eПроверка выполняется только при входе игрока. Во время игры диагностика отключена.");
            }
            case "probe" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                if (a.length < 3) { s.sendMessage("§c/afc probe <игрок> <ключ> [ключ...]"); return true; }
                Player t = plugin.profiles().onlinePlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cИгрок не найден онлайн."); return true; }
                plugin.detection().probe(t, Arrays.asList(a).subList(2, Math.min(a.length, 6)), s);
                s.sendMessage("§eЗапрос отправлен клиенту...");
            }
            case "access" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                access(s, a);
            }
            case "exempt" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                exempt(s, a);
            }
            case "pumpkin", "тыква", "p" -> {
                if (!acc.canPumpkin(s)) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                pumpkin(s, a);
            }
            case "resetstats", "сброс" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                if (a.length >= 2 && a[1].equalsIgnoreCase("confirm")) {
                    int[] r = plugin.resetStats();
                    s.sendMessage("§a[Проверка] Статистика сброшена: срабатываний " + r[0] + ", профилей игроков " + r[1] + ".");
                    s.sendMessage("§7Игроки в сети получили чистый профиль. Доступы, исключения, тыква и настройки не тронуты.");
                } else {
                    s.sendMessage("§e[Проверка] Будет удалено: срабатываний §f" + plugin.detection().flags().size()
                            + "§e, профилей игроков §f" + plugin.profiles().all().size() + "§e (моды, клиенты, история заходов).");
                    s.sendMessage("§eПодтверди: §f/afc resetstats confirm");
                }
            }
            case "resetmods" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                SchedulerUtil.globalOnce(plugin, () -> {
                    plugin.resetMods();
                    plugin.reloadConfig();
                    sendAfterGlobal(s, "§aСписок модов в config.yml возвращён к стандартному.");
                });
            }
            case "reload" -> {
                if (!manager) { s.sendMessage("§cУ вас нет доступа к меню ClientWatch."); return true; }
                SchedulerUtil.globalOnce(plugin, () -> {
                    plugin.reloadConfig();
                    plugin.watch().reload();
                    plugin.refreshWatchClassifications();
                    sendAfterGlobal(s, "§aКонфиг и список запрошенных модификаций перезагружены. Онлайн-игроки классифицированы заново.");
                });
            }
            default -> s.sendMessage("§c/afc <help|list|search|players|mods|history|watch|not|info|check|test|probe|access|exempt|pumpkin|resetstats|resetmods|reload>");
        }
        return true;
    }

    private void sendAfterGlobal(CommandSender sender, String message) {
        if (sender instanceof Player p) {
            SchedulerUtil.onEntity(plugin, p, () -> p.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    private void pumpkinUsage(CommandSender s) {
        s.sendMessage("§e/afc p §7- меню тыквы (короткая команда)");
        s.sendMessage("§e/afc p on|off <ник> §7- надеть / снять тыкву");
        s.sendMessage("§e/afc pumpkin list §7- у кого сейчас тыква");
        s.sendMessage("§e/afc pumpkin clear §7- снять тыкву у всех");
        s.sendMessage("§e/afc pumpkin name <текст> §7- название (MiniMessage или &-коды)");
        s.sendMessage("§e/afc pumpkin lore add|set <n>|remove <n>|clear|list §7- описание");
        s.sendMessage("§e/afc pumpkin item §7- взять название и описание с предмета в руке");
        s.sendMessage("§e/afc pumpkin reset §7- вернуть стандартные название и описание");
        s.sendMessage("§e/afc pumpkin edit §7- меню редактора");
    }

    private void pumpkin(CommandSender s, String[] a) {
        PumpkinManager pk = plugin.pumpkin();
        if (a.length < 2) {
            if (s instanceof Player p) new PumpkinGui(plugin, 0).open(p);
            else pumpkinList(s);
            return;
        }
        switch (a[1].toLowerCase()) {
            case "on", "add", "give" -> {
                if (a.length < 3) { s.sendMessage("§c/afc pumpkin on <ник>"); return; }
                pk.enableByName(s, a[2]);
            }
            case "off", "remove", "take" -> {
                if (a.length < 3) { s.sendMessage("§c/afc pumpkin off <ник>"); return; }
                pk.disableByName(s, a[2]);
            }
            case "list" -> pumpkinList(s);
            case "clear" -> {
                int n = pk.count();
                pk.clearAll();
                s.sendMessage("§e[Проверка] Тыква снята у всех (" + n + ").");
            }
            case "edit" -> {
                if (s instanceof Player p) new PumpkinEditGui(plugin).open(p);
                else s.sendMessage("Эта команда доступна только игроку.");
            }
            case "name" -> {
                if (a.length < 3) {
                    s.sendMessage("§7Название сейчас: §f" + pk.templateName());
                    s.sendMessage(PumpkinManager.parse(pk.templateName()));
                    return;
                }
                pk.setName(String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                s.sendMessage("§a[Проверка] Название обновлено у всех:");
                s.sendMessage(PumpkinManager.parse(pk.templateName()));
            }
            case "lore" -> pumpkinLore(s, a);
            case "item" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Эта команда доступна только игроку."); return; }
                if (pk.copyFrom(p.getInventory().getItemInMainHand())) {
                    s.sendMessage("§a[Проверка] Название и описание взяты с предмета в руке, у всех обновлено.");
                } else {
                    s.sendMessage("§c[Проверка] Возьми в руку предмет со своим названием или описанием.");
                }
            }
            case "reset" -> {
                pk.resetTemplate();
                s.sendMessage("§e[Проверка] Название и описание сброшены на стандартные.");
            }
            default -> pumpkinUsage(s);
        }
    }

    private void pumpkinList(CommandSender s) {
        PumpkinManager pk = plugin.pumpkin();
        if (pk.count() == 0) {
            s.sendMessage("§7[Проверка] Тыква сейчас ни у кого.");
            return;
        }
        s.sendMessage("§6[Проверка] Игроки с тыквой (" + pk.count() + "):");
        for (java.util.UUID id : pk.uuids()) {
            s.sendMessage((plugin.profiles().isOnline(id) ? "§a● §f" : "§8● §7") + pk.nameOf(id));
        }
    }

    private void pumpkinLore(CommandSender s, String[] a) {
        PumpkinManager pk = plugin.pumpkin();
        String what = a.length > 2 ? a[2].toLowerCase() : "list";
        switch (what) {
            case "add" -> {
                if (a.length < 4) { s.sendMessage("§c/afc pumpkin lore add <текст>"); return; }
                if (!pk.addLore(String.join(" ", Arrays.copyOfRange(a, 3, a.length)))) {
                    s.sendMessage("§c[Проверка] Максимум строк описания: " + PumpkinManager.MAX_LORE);
                    return;
                }
                s.sendMessage("§a[Проверка] Строка добавлена, у всех обновлено.");
            }
            case "set" -> {
                Integer n = a.length > 3 ? parseInt(a[3]) : null;
                if (n == null || a.length < 5) { s.sendMessage("§c/afc pumpkin lore set <номер> <текст>"); return; }
                if (!pk.setLore(n - 1, String.join(" ", Arrays.copyOfRange(a, 4, a.length)))) {
                    s.sendMessage("§c[Проверка] Нет строки с номером " + n + ". Всего строк: " + pk.templateLore().size());
                    return;
                }
                s.sendMessage("§a[Проверка] Строка " + n + " изменена, у всех обновлено.");
            }
            case "remove" -> {
                Integer n = a.length > 3 ? parseInt(a[3]) : null;
                if (n == null) { s.sendMessage("§c/afc pumpkin lore remove <номер>"); return; }
                if (!pk.removeLore(n - 1)) {
                    s.sendMessage("§c[Проверка] Нет строки с номером " + n + ". Всего строк: " + pk.templateLore().size());
                    return;
                }
                s.sendMessage("§e[Проверка] Строка " + n + " удалена, у всех обновлено.");
            }
            case "clear" -> {
                pk.clearLore();
                s.sendMessage("§e[Проверка] Описание очищено.");
            }
            default -> {
                List<String> lines = pk.templateLore();
                if (lines.isEmpty()) { s.sendMessage("§7[Проверка] Описание пустое."); return; }
                s.sendMessage("§6[Проверка] Описание тыквы:");
                for (int i = 0; i < lines.size(); i++) {
                    s.sendMessage("§8" + (i + 1) + ". §7" + lines.get(i));
                    s.sendMessage(PumpkinManager.parse(lines.get(i)));
                }
            }
        }
    }

    private static Integer parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void info(CommandSender s, String name) {
        DetectionManager.Flag f = plugin.detection().findFlag(name);
        if (f == null) {
            s.sendMessage("§7У §f" + name + " §7нет срабатываний (или его ещё не проверяли).");
            return;
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd.MM HH:mm:ss");
        boolean online = plugin.profiles().isOnline(f.uuid);
        s.sendMessage("§e" + f.name + " §7- " + (online ? "§aонлайн"
                : "§cоффлайн" + (f.left > 0 ? " §8(вышел " + fmt.format(new java.util.Date(f.left)) + ")" : "")));
        s.sendMessage("§7Найден: §f" + fmt.format(new java.util.Date(f.firstSeen))
                + " §7| проверен: §f" + fmt.format(new java.util.Date(f.lastSeen)));
        f.mods.forEach((mod, key) -> s.sendMessage("§c• §f" + mod + (key.isEmpty() ? "" : " §8[" + key + "]")));
    }

    private void access(CommandSender s, String[] a) {
        AccessManager acc = plugin.access();
        if (a.length < 2 || a[1].equalsIgnoreCase("list")) {
            s.sendMessage("§7Доступ к меню: §f" + String.join(", ", acc.listable().stream().filter(acc::hasMenu).toList()));
            s.sendMessage("§7Доступ к уведомлениям: §f" + String.join(", ", acc.listable().stream().filter(acc::hasNotify).toList()));
            s.sendMessage("§7Доступ к тыкве: §f" + String.join(", ", acc.listable().stream().filter(acc::hasPumpkin).toList()));
            return;
        }
        if (a.length < 3) { s.sendMessage("§c/afc access <add|remove> <ник> [menu|notify|pumpkin|all]"); return; }
        String name = a[2];
        if (acc.isOwner(name)) {
            s.sendMessage("§cДоступ Dok_Si нельзя изменить: это встроенный владелец ClientWatch.");
            return;
        }
        switch (a[1].toLowerCase()) {
            case "add" -> {
                String what = a.length > 3 ? a[3].toLowerCase() : "all";
                if (what.equals("menu") || what.equals("all")) acc.setMenu(name, true);
                if (what.equals("notify") || what.equals("all")) acc.setNotify(name, true);
                if (what.equals("pumpkin") || what.equals("all")) acc.setPumpkin(name, true);
                s.sendMessage("§aДоступ выдан: " + name + " (" + what + ")");
            }
            case "remove" -> {
                String what = a.length > 3 ? a[3].toLowerCase() : "all";
                if (what.equals("all")) acc.revoke(name);
                else if (what.equals("menu")) acc.setMenu(name, false);
                else if (what.equals("notify")) acc.setNotify(name, false);
                else if (what.equals("pumpkin")) acc.setPumpkin(name, false);
                s.sendMessage("§eДоступ убран: " + name + " (" + what + ")");
            }
            default -> s.sendMessage("§c/afc access <add|remove> <ник> [menu|notify|pumpkin|all]");
        }
    }

    private void exempt(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§c/afc exempt <add|remove|list> [ник]"); return; }
        switch (a[1].toLowerCase()) {
            case "add" -> {
                if (a.length < 3) return;
                plugin.addExempt(a[2]);
                s.sendMessage("§a" + a[2] + " добавлен в исключения.");
            }
            case "remove" -> {
                if (a.length < 3) return;
                plugin.removeExempt(a[2]);
                s.sendMessage("§e" + a[2] + " убран из исключений.");
            }
            default -> s.sendMessage("§7Исключения: §f" + String.join(", ", plugin.getConfig().getStringList("exempt")));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        boolean pk = plugin.access().canPumpkin(s);
        if (!plugin.access().canView(s) && !(a.length > 0 && (a[0].equalsIgnoreCase("pumpkin") || a[0].equalsIgnoreCase("p") || a[0].equalsIgnoreCase("тыква")) && pk)) return List.of();
        boolean m = plugin.access().isManager(s);
        if (a.length >= 2 && (a[0].equalsIgnoreCase("pumpkin") || a[0].equalsIgnoreCase("p") || a[0].equalsIgnoreCase("тыква")) && pk) return tabPumpkin(a);
        if (a.length == 1) {
            List<String> base = new java.util.ArrayList<>(m
                    ? List.of("help", "list", "search", "players", "mods", "history", "watch", "not", "info", "check", "test", "probe", "access", "exempt", "resetstats", "resetmods", "reload")
                    : List.of("help", "list", "search", "players", "mods", "history", "watch", "not", "info", "check"));
            if (pk) { base.add("pumpkin"); base.add("p"); }
            return base;
        }
        if (!m && !a[0].equalsIgnoreCase("check") && !a[0].equalsIgnoreCase("info") && !a[0].equalsIgnoreCase("mods") && !a[0].equalsIgnoreCase("history")) return List.of();
        if (a.length == 2 && (a[0].equalsIgnoreCase("info") || a[0].equalsIgnoreCase("mods") || a[0].equalsIgnoreCase("history"))) {
            java.util.Set<String> names = new java.util.TreeSet<>(plugin.profiles().names());
            plugin.detection().flags().forEach(f -> names.add(f.name));
            return new java.util.ArrayList<>(names);
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("resetstats")) return List.of("confirm");
        if (a.length == 2 && a[0].equalsIgnoreCase("exempt")) return List.of("add", "remove", "list");
        if (a.length == 2 && a[0].equalsIgnoreCase("access")) return List.of("add", "remove", "list");
        if (a.length == 4 && a[0].equalsIgnoreCase("access")) return List.of("menu", "notify", "pumpkin", "all");
        if ((a.length == 2 && (a[0].equalsIgnoreCase("check") || a[0].equalsIgnoreCase("recheck") || a[0].equalsIgnoreCase("probe") || a[0].equalsIgnoreCase("test")))
                || (a.length == 3 && (a[0].equalsIgnoreCase("exempt") || a[0].equalsIgnoreCase("access")))) {
            return plugin.profiles().onlineNames();
        }
        return List.of();
    }
    private List<String> tabPumpkin(String[] a) {
        if (a.length == 2) return List.of("on", "off", "list", "clear", "name", "lore", "item", "reset", "edit");
        String sub = a[1].toLowerCase();
        if (a.length == 3 && (sub.equals("on") || sub.equals("add") || sub.equals("give"))) {
            return plugin.profiles().onlineEntries().entrySet().stream()
                    .filter(e -> !plugin.pumpkin().has(e.getKey()))
                    .map(java.util.Map.Entry::getValue).toList();
        }
        if (a.length == 3 && (sub.equals("off") || sub.equals("remove") || sub.equals("take"))) {
            return plugin.pumpkin().uuids().stream().map(id -> plugin.pumpkin().nameOf(id)).toList();
        }
        if (a.length == 3 && sub.equals("lore")) return List.of("add", "set", "remove", "clear", "list");
        return List.of();
    }
}
