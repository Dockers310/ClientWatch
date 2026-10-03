package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.manager.ProfileManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** История заходов игрока: каждый заход - отдельный предмет, моды в описании по 5 в строке. */
public final class HistoryGui implements InventoryHolder {

    public static final int PER_PAGE = 45, PREV = 45, INFO = 47, BACK = 49, NEXT = 53;
    private static final int MODS_PER_LINE = 5;

    private final AntiFreecam plugin;
    private final ProfileManager.Profile profile;
    private final boolean backToPlayers;
    private final List<ProfileManager.SessionRec> recs = new ArrayList<>(); // от нового к старому
    private final int page;
    private Inventory inv;

    public HistoryGui(AntiFreecam plugin, ProfileManager.Profile profile, int page, boolean backToPlayers) {
        this.plugin = plugin;
        this.profile = profile;
        this.backToPlayers = backToPlayers;
        for (int i = profile.history.size() - 1; i >= 0; i--) recs.add(profile.history.get(i));
        int maxPage = Math.max(0, (recs.size() - 1) / PER_PAGE);
        this.page = Math.min(Math.max(page, 0), maxPage);
    }

    public int page() { return page; }

    public ProfileManager.Profile profile() { return profile; }

    public boolean backToPlayers() { return backToPlayers; }

    private static String shorten(String s) {
        return s.length() > 22 ? s.substring(0, 21) + "…" : s;
    }

    public void open(Player viewer) {
        ProfileManager pm = plugin.profiles();
        boolean online = plugin.profiles().isOnline(profile.uuid);
        int pages = Math.max(1, (int) Math.ceil(recs.size() / (double) PER_PAGE));
        inv = Bukkit.createInventory(this, 54, Gui.c("<dark_red>История: <yellow>" + Gui.esc(profile.name)
                + " <gray>(" + (page + 1) + "/" + pages + ")"));
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm");

        Map<String, ProfileManager.PMod> byDisplay = new HashMap<>();
        for (ProfileManager.PMod m : profile.mods.values()) byDisplay.put(m.display, m);

        for (int slot = 0; slot < PER_PAGE; slot++) {
            int idx = page * PER_PAGE + slot;
            if (idx >= recs.size()) break;
            ProfileManager.SessionRec r = recs.get(idx);
            boolean current = idx == 0 && online;
            int no = Math.max(1, profile.session - idx);

            boolean cheat = false;
            List<String> tokens = new ArrayList<>();
            for (String name : r.mods) {
                ProfileManager.PMod pmod = byDisplay.get(name);
                boolean isCheat = pmod != null && pmod.cheat;
                boolean watched = pmod != null && pm.isWatched(pmod.id);
                cheat |= isCheat;
                tokens.add((isCheat ? "<red>☠ " : watched ? "<gold>⭐ " : "<white>") + Gui.esc(shorten(name)));
            }

            List<String> lore = new ArrayList<>();
            if (current) lore.add("<green>● Сейчас в игре");
            lore.add("<gray>Клиент: " + (r.brand.isEmpty() ? "<dark_gray>не передан" : "<white>" + Gui.esc(r.brand))
                    + (r.launcher.isEmpty() ? "" : " <dark_gray>(" + Gui.esc(r.launcher) + ")"));
            if (!r.companionLauncher.isEmpty()) lore.add("<gray>Лаунчер от мода: <aqua>" + Gui.esc(r.companionLauncher)
                    + (r.companionLauncherConfidence.isEmpty() ? "" : " <dark_gray>(" + Gui.esc(r.companionLauncherConfidence) + ")"));
            if (!r.companionMinecraftVersion.isEmpty()) lore.add("<gray>Версия игры от мода: <white>" + Gui.esc(r.companionMinecraftVersion));
            if (!r.companionFabricLoaderVersion.isEmpty()) lore.add("<gray>Fabric Loader: <white>" + Gui.esc(r.companionFabricLoaderVersion));
            lore.add("<gray>Версия: " + (r.version.isEmpty() ? "<dark_gray>неизвестна" : "<white>" + Gui.esc(r.version)));
            String check = ProfileManager.checkText(r.check);
            lore.add("<gray>Проверка: " + (check != null ? check : current ? "<yellow>идёт..." : "<dark_gray>нет данных"));
            lore.add("<gray>Моды (" + r.mods.size() + "):");
            if (tokens.isEmpty()) {
                lore.add("<dark_gray>  без известных модов");
            } else {
                for (int i = 0; i < tokens.size(); i += MODS_PER_LINE) {
                    lore.add("  " + String.join("<gray>, ", tokens.subList(i, Math.min(i + MODS_PER_LINE, tokens.size()))));
                }
            }

            Material mat = cheat ? Material.TNT : r.mods.isEmpty() ? Material.PAPER : Material.BOOK;
            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Gui.c((cheat ? "<red>" : "<white>") + "#" + no + " <gray>" + fmt.format(new Date(r.time))
                    + (r.brand.isEmpty() ? "" : " <dark_gray>[" + Gui.esc(r.brand) + "]")));
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(Gui.c(line));
            meta.lore(l);
            if (current) meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
        }

        for (int i = 45; i < 54; i++) inv.setItem(i, Gui.item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(PREV, Gui.item(Material.ARROW, "<yellow>← Назад"));
        inv.setItem(NEXT, Gui.item(Material.ARROW, "<yellow>Вперёд →"));
        inv.setItem(BACK, Gui.item(Material.BARRIER, "<red>К списку модов"));

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(profile.uuid));
        sm.displayName(Gui.c("<yellow>" + Gui.esc(profile.name)));
        sm.lore(List.of(
                Gui.c("<gray>Всего заходов: <white>" + profile.session),
                Gui.c("<gray>В истории: <white>" + recs.size()),
                Gui.c(""),
                Gui.c("<gray>Каждый предмет - один заход."),
                Gui.c("<gray>Красным ☠ - читы, золотым ⭐ - отмеченные.")));
        head.setItemMeta(sm);
        inv.setItem(INFO, head);
        viewer.openInventory(inv);
    }

    @Override
    public @NotNull Inventory getInventory() { return inv; }
}
