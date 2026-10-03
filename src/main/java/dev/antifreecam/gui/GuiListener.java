package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.util.SchedulerUtil;
import dev.antifreecam.detection.DetectionManager;
import dev.antifreecam.manager.AccessManager;
import dev.antifreecam.manager.ProfileManager;
import dev.antifreecam.manager.PumpkinManager;
import dev.antifreecam.watch.ModProject;
import dev.antifreecam.watch.WatchStatus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class GuiListener implements Listener {

    private final AntiFreecam plugin;

    public GuiListener(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    private static boolean ours(InventoryHolder h) {
        return h instanceof MainMenu || h instanceof FlagGui || h instanceof AccessGui || h instanceof HelpGui
                || h instanceof ModsGui || h instanceof WatchGui || h instanceof WatchSearchGui || h instanceof WatchDetailGui
                || h instanceof HistoryGui || h instanceof PlayersGui
                || h instanceof PumpkinGui || h instanceof PumpkinEditGui;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (ours(e.getInventory().getHolder())) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (!ours(h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        // Проверяем rawSlot, а не ссылочное равенство Inventory. Это стабильнее
        // при кликах по верхнему инвентарю и не теряет обычные ЛКМ/ПКМ на Paper/Folia.
        int rawSlot = e.getRawSlot();
        if (rawSlot < 0 || rawSlot >= e.getView().getTopInventory().getSize()) return;

        AccessManager acc = plugin.access();
        if (!acc.canView(viewer)) { viewer.closeInventory(); return; }
        int slot = e.getSlot();
        ClickType click = e.getClick();

        if (h instanceof MainMenu) {
            switch (slot) {
                case MainMenu.FLAGS -> new FlagGui(plugin, 0, null).open(viewer);
                case MainMenu.NOTIFY -> {
                    if (!acc.hasNotifyRight(viewer)) return;
                    acc.toggleNotifyOn(viewer);
                    new MainMenu(plugin).open(viewer);
                }
                case MainMenu.HELP -> new HelpGui(plugin).open(viewer);
                case MainMenu.WATCH -> new WatchGui(plugin, 0).open(viewer);
                case MainMenu.PLAYERS -> new PlayersGui(plugin, 0, null).open(viewer);
                case MainMenu.RESET_STATS -> {
                    if (!acc.isManager(viewer)) return;
                    if (click != ClickType.SHIFT_LEFT) {
                        viewer.sendMessage("§e[Проверка] Сброс статистики: нажми Shift+ЛКМ, чтобы подтвердить.");
                        return;
                    }
                    int[] r = plugin.resetStats();
                    viewer.sendMessage("§a[Проверка] Статистика сброшена: срабатываний " + r[0] + ", профилей игроков " + r[1] + ".");
                    new MainMenu(plugin).open(viewer);
                }
                case MainMenu.PUMPKIN -> { if (acc.canPumpkin(viewer)) new PumpkinGui(plugin, 0).open(viewer); }
                case MainMenu.ACCESS -> { if (acc.isManager(viewer)) new AccessGui(plugin, 0).open(viewer); }
                case MainMenu.CHECK_ALL -> {
                    if (!acc.isManager(viewer)) return;
                    viewer.sendMessage("§eПроверка выполняется только при входе игрока. Во время игры автоматических перепроверок нет.");
                }
                default -> {}
            }
            return;
        }

        if (h instanceof HelpGui) {
            if (slot == HelpGui.BACK) new MainMenu(plugin).open(viewer);
            return;
        }

        if (h instanceof FlagGui gui) {
            String filter = gui.filter();
            if (slot == FlagGui.PREV) { new FlagGui(plugin, gui.page() - 1, filter).open(viewer); return; }
            if (slot == FlagGui.NEXT) { new FlagGui(plugin, gui.page() + 1, filter).open(viewer); return; }
            if (slot == FlagGui.BACK) { new MainMenu(plugin).open(viewer); return; }
            if (slot == FlagGui.SEARCH) {
                if (click == ClickType.RIGHT) new FlagGui(plugin, 0, null).open(viewer);
                else plugin.search().begin(viewer, filter);
                return;
            }
            if (slot == FlagGui.REFRESH) {
                viewer.sendMessage("§e[Проверка] Онлайн-игроки уже проверяются только при входе. Перепроверка во время игры отключена.");
                return;
            }

            DetectionManager.Flag f = gui.flagAt(slot);
            if (f == null) return;
            boolean manager = acc.isManager(viewer);

            if (click == ClickType.SHIFT_RIGHT && acc.canPumpkin(viewer)) {
                togglePumpkin(viewer, f.uuid, f.name);
                reopenNextTick(viewer, () -> new FlagGui(plugin, gui.page(), filter).open(viewer));
                return;
            }
            if (click == ClickType.SHIFT_LEFT && manager) {
                if (plugin.isExemptName(f.name)) {
                    plugin.removeExempt(f.name);
                    viewer.sendMessage("§e" + f.name + " убран из исключений.");
                } else if (plugin.isExemptFlag(f)) {
                    viewer.sendMessage("§eУ " + f.name + " право antifreecam.bypass - убери его в LuckPerms.");
                } else {
                    plugin.addExempt(f.name);
                    viewer.sendMessage("§a" + f.name + " добавлен в исключения (остаётся в списке, уведомления не приходят).");
                }
                reopenNextTick(viewer, () -> new FlagGui(plugin, gui.page(), filter).open(viewer));
            } else if (click == ClickType.RIGHT && manager) {
                plugin.detection().unflag(f.uuid);
                reopenNextTick(viewer, () -> new FlagGui(plugin, gui.page(), filter).open(viewer));
            } else if (click == ClickType.LEFT) {
                ProfileManager.Profile pr = plugin.profiles().ensure(f);
                reopenNextTick(viewer, () -> new ModsGui(plugin, pr, 0).open(viewer));
            }
            return;
        }

        if (h instanceof ModsGui gui) {
            // Останавливаем автообновление именно этого экземпляра GUI на время клика.
            // Иначе завершение проверки могло заменить инвентарь в тот же момент.
            gui.stopProgressRefresh();
            boolean bp = gui.backToPlayers();
            if (slot == ModsGui.PREV) { new ModsGui(plugin, gui.profile(), gui.page() - 1, bp).open(viewer); return; }
            if (slot == ModsGui.NEXT) { new ModsGui(plugin, gui.profile(), gui.page() + 1, bp).open(viewer); return; }
            if (slot == ModsGui.BACK) {
                if (bp) new PlayersGui(plugin, 0, null).open(viewer);
                else new FlagGui(plugin, 0, null).open(viewer);
                return;
            }
            if (slot == ModsGui.HISTORY) { new HistoryGui(plugin, gui.profile(), 0, bp).open(viewer); return; }
            if (slot == ModsGui.RECHECK) {
                Player target = plugin.profiles().onlinePlayer(gui.profile().uuid);
                if (target == null) {
                    viewer.sendMessage("§cИгрок сейчас не в сети — перепроверить его можно только во время подключения.");
                    return;
                }
                plugin.detection().recheck(target);
                viewer.sendMessage("§a[Проверка] Перепроверка запущена только для §f" + target.getName() + "§a.");
                reopenNextTick(viewer, () -> new ModsGui(plugin, gui.profile(), gui.page(), gui.backToPlayers()).open(viewer));
                return;
            }
            if (slot == ModsGui.INFO) {
                if (acc.canPumpkin(viewer)) {
                    togglePumpkin(viewer, gui.profile().uuid, gui.profile().name);
                    reopenNextTick(viewer, () -> new ModsGui(plugin, gui.profile(), gui.page(), bp).open(viewer));
                }
                return;
            }
            ProfileManager.PMod m = gui.modAt(slot);
            if (m == null) return;

            // Shift+ПКМ — смена статуса именно этой записи в «Запрошенных модах».
            // Проверяем этот клик ДО isRightClick(), потому что SHIFT_RIGHT входит в группу правых кликов.
            if (click == ClickType.SHIFT_RIGHT) {
                if (!acc.isManager(viewer)) {
                    viewer.sendMessage("§cНедостаточно прав для изменения статуса модификации.");
                    return;
                }
                String canonical = m.id;
                if (canonical.startsWith("cw:") || canonical.startsWith("fp:") || canonical.startsWith("ch:")) {
                    canonical = canonical.substring(3);
                }
                ModProject.Entry existing = plugin.watch().findMatchingEntry(canonical, m.display);
                if (existing == null) {
                    ModProject.Entry added = plugin.watch().ensureInstalled(canonical, m.display);
                    viewer.sendMessage("§c" + m.display + " §7→ добавлен в «Запрошенные моды» со статусом: §f"
                            + added.status().display());
                } else {
                    WatchStatus next = plugin.watch().cycle(existing.key());
                    viewer.sendMessage("§e" + m.display + " §7→ статус: §f"
                            + (next == null ? "неизвестно" : next.display()));
                }
                reopenNextTick(viewer, () -> new ModsGui(plugin, gui.profile(), gui.page(), bp).open(viewer));
                return;
            }

            if (!click.isLeftClick()) return;
            boolean now = plugin.profiles().toggleWatch(m.id);
            viewer.sendMessage(now ? "§6⭐ Отмечен вниманием: §f" + m.display + " §7(будет видно у всех, у кого он есть)"
                    : "§7Отметка снята: §f" + m.display);
            reopenNextTick(viewer, () -> new ModsGui(plugin, gui.profile(), gui.page(), bp).open(viewer));
            return;
        }

        if (h instanceof HistoryGui gui) {
            boolean bp = gui.backToPlayers();
            if (slot == HistoryGui.PREV) { new HistoryGui(plugin, gui.profile(), gui.page() - 1, bp).open(viewer); return; }
            if (slot == HistoryGui.NEXT) { new HistoryGui(plugin, gui.profile(), gui.page() + 1, bp).open(viewer); return; }
            if (slot == HistoryGui.BACK) new ModsGui(plugin, gui.profile(), 0, bp).open(viewer);
            return;
        }

        if (h instanceof PlayersGui gui) {
            String filter = gui.filter();
            if (slot == PlayersGui.PREV) { new PlayersGui(plugin, gui.page() - 1, filter).open(viewer); return; }
            if (slot == PlayersGui.NEXT) { new PlayersGui(plugin, gui.page() + 1, filter).open(viewer); return; }
            if (slot == PlayersGui.BACK) { new MainMenu(plugin).open(viewer); return; }
            if (slot == PlayersGui.SEARCH) {
                if (click == ClickType.RIGHT) new PlayersGui(plugin, 0, null).open(viewer);
                else plugin.search().begin(viewer, filter, true);
                return;
            }
            ProfileManager.Profile pr = gui.profileAt(slot);
            if (pr == null) return;
            if (click.isLeftClick()) {
                reopenNextTick(viewer, () -> new ModsGui(plugin, pr, 0, true).open(viewer));
            } else if (click == ClickType.RIGHT && acc.canPumpkin(viewer)) {
                togglePumpkin(viewer, pr.uuid, pr.name);
                reopenNextTick(viewer, () -> new PlayersGui(plugin, gui.page(), filter).open(viewer));
            }
            return;
        }

        if (h instanceof WatchGui gui) {
            if (slot == WatchGui.PREV) { reopenNextTick(viewer, () -> new WatchGui(plugin, gui.page(), gui.query()).open(viewer)); return; }
            if (slot == WatchGui.NEXT) { reopenNextTick(viewer, () -> new WatchGui(plugin, gui.page(), gui.query()).open(viewer)); return; }
            if (slot == WatchGui.BACK) { new MainMenu(plugin).open(viewer); return; }
            if (slot == WatchGui.ADD) {
                plugin.search().ask(viewer, "§e[Проверка] Напиши название или часть названия модификации (например autototem, freecam).",
                        text -> new WatchSearchGui(plugin, text.strip(), 0).start(viewer),
                        () -> new WatchGui(plugin, gui.page(), gui.query()).open(viewer));
                return;
            }
            if (slot == WatchGui.SEARCH) {
                if (click == ClickType.RIGHT) { new WatchGui(plugin, 0).open(viewer); return; }
                plugin.search().ask(viewer, "§e[Проверка] Напиши фильтр по уже добавленным модам.",
                        text -> new WatchGui(plugin, 0, text.strip()).open(viewer),
                        () -> new WatchGui(plugin, gui.page(), gui.query()).open(viewer));
                return;
            }
            ModProject.Entry entry = gui.entryAt(slot);
            if (entry == null) return;
            if (click == ClickType.LEFT) {
                new WatchDetailGui(plugin, entry.project(), gui.query() == null ? "" : gui.query(), 0, false).open(viewer);
            } else if (click == ClickType.SHIFT_RIGHT) {
                plugin.watch().remove(entry.key());
                reopenNextTick(viewer, () -> new WatchGui(plugin, gui.page(), gui.query()).open(viewer));
            } else if (click == ClickType.RIGHT) {
                plugin.watch().cycle(entry.key());
                reopenNextTick(viewer, () -> new WatchGui(plugin, gui.page(), gui.query()).open(viewer));
            }
            return;
        }

        if (h instanceof WatchSearchGui gui) {
            if (slot == WatchSearchGui.PREV) {
                if (gui.page() <= 0) return;
                // page navigation requires retaining fetched results; rerunning the search keeps the view current.
                new WatchSearchGui(plugin, gui.query(), gui.page() - 1).start(viewer);
                return;
            }
            if (slot == WatchSearchGui.NEXT) {
                new WatchSearchGui(plugin, gui.query(), gui.page() + 1).start(viewer);
                return;
            }
            if (slot == WatchSearchGui.BACK) { new WatchGui(plugin, 0).open(viewer); return; }
            if (slot == WatchSearchGui.QUERY) {
                if (click == ClickType.RIGHT) { new WatchGui(plugin, 0).open(viewer); return; }
                plugin.search().ask(viewer, "§e[Проверка] Новый запрос модификации:",
                        text -> new WatchSearchGui(plugin, text.strip(), 0).start(viewer),
                        () -> new WatchSearchGui(plugin, gui.query(), gui.page()).start(viewer));
                return;
            }
            ModProject.SearchResult result = gui.resultAt(slot);
            if (result != null && click == ClickType.LEFT) {
                new WatchDetailGui(plugin, result, gui.query(), 0, true).open(viewer);
            }
            return;
        }

        if (h instanceof WatchDetailGui gui) {
            ModProject.Entry entry = plugin.watch().get(gui.project().key());
            int filePages = entry == null ? 1 : Math.max(1, (int) Math.ceil(entry.files().size() / 45.0));
            if (slot == WatchDetailGui.PREV) {
                if (gui.page() > 0) new WatchDetailGui(plugin, gui.project(), gui.query(), gui.page() - 1, gui.backToSearch()).open(viewer);
                else if (gui.backToSearch()) new WatchSearchGui(plugin, gui.query(), 0).start(viewer);
                else new WatchGui(plugin, 0).open(viewer);
                return;
            }
            if (slot == WatchDetailGui.NEXT) {
                if (gui.page() + 1 < filePages) new WatchDetailGui(plugin, gui.project(), gui.query(), gui.page() + 1, gui.backToSearch()).open(viewer);
                return;
            }
            if (slot == WatchDetailGui.BACK) {
                if (gui.backToSearch()) new WatchSearchGui(plugin, gui.query(), 0).start(viewer);
                else new WatchGui(plugin, 0).open(viewer);
                return;
            }
            if (slot == WatchDetailGui.STATUS) {
                if (entry == null) {
                    plugin.watch().add(gui.project(), gui.query()).whenComplete((added, error) -> SchedulerUtil.onEntity(plugin, viewer, () -> {
                        if (!viewer.isOnline()) return;
                        if (viewer.getOpenInventory().getTopInventory().getHolder() != gui) return;
                        if (error != null) viewer.sendMessage("§c[Проверка] Не удалось добавить мод: " + WatchSearchGui.rootMessage(error));
                        else viewer.sendMessage("§a[Проверка] Добавлен как запрещённый: §f" + added.project().title());
                        new WatchDetailGui(plugin, gui.project(), gui.query(), gui.page(), gui.backToSearch()).open(viewer);
                    }));
                } else if (click.isRightClick() || click.isLeftClick()) {
                    plugin.watch().cycle(entry.key());
                    reopenNextTick(viewer, () -> new WatchDetailGui(plugin, gui.project(), gui.query(), gui.page(), gui.backToSearch()).open(viewer));
                }
                return;
            }
            if (slot == WatchDetailGui.ALL_VERSIONS && entry != null) {
                boolean on = plugin.watch().toggleAllVersions(entry.key());
                viewer.sendMessage(on
                        ? "§a[Проверка] Теперь любая версия этого проекта учитывается по выбранному статусу."
                        : "§e[Проверка] Теперь учитываются только известные файлы проекта.");
                reopenNextTick(viewer, () -> new WatchDetailGui(plugin, gui.project(), gui.query(), gui.page(), gui.backToSearch()).open(viewer));
                return;
            }
            if (slot == WatchDetailGui.REMOVE && entry != null) {
                plugin.watch().remove(entry.key());
                if (gui.backToSearch()) new WatchSearchGui(plugin, gui.query(), 0).start(viewer);
                else new WatchGui(plugin, 0).open(viewer);
                return;
            }
            return;
        }

        if (h instanceof PumpkinGui gui) {
            if (!acc.canPumpkin(viewer)) { viewer.closeInventory(); return; }
            if (slot == PumpkinGui.PREV) { new PumpkinGui(plugin, gui.page() - 1).open(viewer); return; }
            if (slot == PumpkinGui.NEXT) { new PumpkinGui(plugin, gui.page() + 1).open(viewer); return; }
            if (slot == PumpkinGui.BACK) { new MainMenu(plugin).open(viewer); return; }
            if (slot == PumpkinGui.EDIT) { new PumpkinEditGui(plugin).open(viewer); return; }
            if (slot == PumpkinGui.ADD) {
                plugin.search().ask(viewer, "§e[Проверка] Напиши в чат ник игрока, которому выдать тыкву.",
                        text -> {
                            plugin.pumpkin().enableByName(viewer, text.split("\\s+")[0]);
                            new PumpkinGui(plugin, 0).open(viewer);
                        },
                        () -> new PumpkinGui(plugin, 0).open(viewer));
                return;
            }
            UUID id = gui.uuidAt(slot);
            if (id == null) return;
            String nick = plugin.pumpkin().nameOf(id);
            plugin.pumpkin().disable(id);
            viewer.sendMessage("§e[Проверка] Тыква снята: §f" + nick);
            new PumpkinGui(plugin, gui.page()).open(viewer);
            return;
        }

        if (h instanceof PumpkinEditGui gui) {
            if (!acc.canPumpkin(viewer)) { viewer.closeInventory(); return; }
            PumpkinManager pk = plugin.pumpkin();
            switch (slot) {
                case PumpkinEditGui.NAME -> plugin.search().ask(viewer,
                        "§e[Проверка] Напиши новое название тыквы. Можно MiniMessage (<gradient:#ff0000:#ffff00>текст</gradient>) или &-коды.",
                        text -> {
                            pk.setName(text);
                            viewer.sendMessage("§a[Проверка] Название обновлено у всех.");
                            new PumpkinEditGui(plugin).open(viewer);
                        },
                        () -> new PumpkinEditGui(plugin).open(viewer));
                case PumpkinEditGui.LORE -> {
                    if (click == ClickType.SHIFT_RIGHT) {
                        pk.clearLore();
                        gui.render();
                    } else if (click == ClickType.RIGHT) {
                        pk.removeLore(pk.templateLore().size() - 1);
                        gui.render();
                    } else if (click == ClickType.LEFT) {
                        if (pk.templateLore().size() >= PumpkinManager.MAX_LORE) {
                            viewer.sendMessage("§c[Проверка] Максимум строк описания: " + PumpkinManager.MAX_LORE);
                            return;
                        }
                        plugin.search().ask(viewer, "§e[Проверка] Напиши новую строку описания (MiniMessage или &-коды).",
                                text -> {
                                    pk.addLore(text);
                                    viewer.sendMessage("§a[Проверка] Строка добавлена, у всех обновлено.");
                                    new PumpkinEditGui(plugin).open(viewer);
                                },
                                () -> new PumpkinEditGui(plugin).open(viewer));
                    }
                }
                case PumpkinEditGui.TAKE -> {
                    org.bukkit.inventory.ItemStack src = e.getCursor();
                    if (src == null || src.getType().isAir()) src = viewer.getInventory().getItemInMainHand();
                    if (pk.copyFrom(src)) {
                        viewer.sendMessage("§a[Проверка] Название и описание взяты с предмета, у всех обновлено.");
                        gui.render();
                    } else {
                        viewer.sendMessage("§c[Проверка] У предмета нет своего названия или описания. Возьми предмет в руку или кликни им по слоту.");
                    }
                }
                case PumpkinEditGui.RESET -> {
                    if (click == ClickType.SHIFT_LEFT) {
                        pk.resetTemplate();
                        viewer.sendMessage("§e[Проверка] Название и описание сброшены на стандартные.");
                        gui.render();
                    }
                }
                case PumpkinEditGui.BACK -> new PumpkinGui(plugin, 0).open(viewer);
                default -> {}
            }
            return;
        }

        if (h instanceof AccessGui gui) {
            if (!acc.isManager(viewer)) return;
            if (slot == AccessGui.PREV) { new AccessGui(plugin, gui.page() - 1).open(viewer); return; }
            if (slot == AccessGui.NEXT) { new AccessGui(plugin, gui.page() + 1).open(viewer); return; }
            if (slot == AccessGui.BACK) { new MainMenu(plugin).open(viewer); return; }

            String name = gui.nameAt(slot);
            if (name == null) return;
            if (click == ClickType.SHIFT_RIGHT) acc.setPumpkin(name, !acc.hasPumpkin(name));
            else if (click == ClickType.SHIFT_LEFT) acc.setPumpkin(name, false);
            else if (click == ClickType.LEFT) acc.setMenu(name, !acc.hasMenu(name));
            else if (click == ClickType.RIGHT) acc.setNotify(name, !acc.hasNotify(name));
            else return;
            new AccessGui(plugin, gui.page()).open(viewer);
        }
    }

    /**
     * Обновляет GUI через 1 тик. Немедленное close/open внутри InventoryClickEvent
     * иногда заставляет клиент пропускать следующий клик или отправлять его в старый инвентарь.
     */
    private void reopenNextTick(Player viewer, Runnable openTask) {
        SchedulerUtil.onEntityLater(plugin, viewer, 1L, () -> {
            if (viewer.isOnline()) openTask.run();
        });
    }

    private void togglePumpkin(Player viewer, UUID uuid, String nick) {
        boolean on = plugin.pumpkin().toggle(uuid, nick);
        viewer.sendMessage(on ? "§a[Проверка] Тыква включена: §f" + nick : "§e[Проверка] Тыква снята: §f" + nick);
    }

    /** Перепроверяет всех, кто сейчас в сети, и через пару секунд обновляет открытый список. */
    private void recheckAll(Player viewer, FlagGui gui) {
        plugin.detection().checkAll(true);
        viewer.sendMessage("§eПроверка всех онлайн запущена, список обновится сам.");
        int page = gui.page();
        String filter = gui.filter();
        long delay = 60L + plugin.profiles().onlinePlayers().size() * 4L;
        SchedulerUtil.onEntityLater(plugin, viewer, delay, () -> {
            if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder() instanceof FlagGui) {
                new FlagGui(plugin, page, filter).open(viewer);
            }
        });
    }
}
