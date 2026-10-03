package dev.antifreecam.manager;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.gui.Gui;
import dev.antifreecam.util.SchedulerUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Тыква позора»: вырезанная тыква на голове с проклятиями несъёмности и утраты.
 *
 * <ul>
 *   <li>Тыква помечена тегом в NBT (PersistentDataContainer, ключ clientwatch:pumpkin) - по нему плагин её узнаёт.</li>
 *   <li>Прежний шлем игрока уходит в свободный слот инвентаря.</li>
 *   <li>Помеченная тыква нигде, кроме слота шлема у игрока из списка, не живёт: в сундуке, на земле,
 *       в руке, у игрока не из списка - исчезает.</li>
 *   <li>После смерти тыква возвращается сама, пока игрок в списке. Когда игрока убирают из списка,
 *       тыква пропадает именно у него.</li>
 *   <li>Название и описание - общий шаблон для всех. Меняется командой или меню, применяется сразу у всех.</li>
 * </ul>
 */
public final class PumpkinManager implements Listener {

    public static final String DEFAULT_NAME = "<bold><gradient:#ff3b3b:#ff9a00:#ffe600>Удали читы</gradient></bold>";
    public static final List<String> DEFAULT_LORE = List.of(
            "<gradient:#ff9a00:#ff3b3b>администрация снимет с тебя</gradient>",
            "<gradient:#ff9a00:#ff3b3b>когда удалишь софт</gradient>");

    /** Максимум строк описания - чтобы подсказка не вылезала за экран. */
    public static final int MAX_LORE = 12;

    private static final Pattern LEGACY = Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");

    private final AntiFreecam plugin;
    private final NamespacedKey key;
    private final File file;

    /** игрок из списка (uuid -> последний известный ник). Безопасно для разных регионов Folia. */
    private final Map<UUID, String> players = new ConcurrentHashMap<>();
    private volatile String name = DEFAULT_NAME;
    private final List<String> lore = new CopyOnWriteArrayList<>(DEFAULT_LORE);

    public PumpkinManager(AntiFreecam plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "pumpkin");
        this.file = new File(plugin.getDataFolder(), "pumpkin.yml");
        load();
    }

    /** Запускает проверку раз в секунду: возвращает тыкву тем, у кого её нет, и чистит чужие копии. */
    public void start() {
        for (Player player : plugin.profiles().onlinePlayers()) {
            schedulePlayerTick(player);
        }
    }

    private void schedulePlayerTick(Player player) {
        SchedulerUtil.onEntityTimer(plugin, player, 20L, 20L, ignored -> tickPlayer(player));
    }

    // ------------------------------------------------------------------ хранение

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String n = y.getString("name");
        if (n != null && !n.isBlank()) name = n;
        if (y.contains("lore")) {
            lore.clear();
            lore.addAll(y.getStringList("lore"));
        }
        var sec = y.getConfigurationSection("players");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                try {
                    players.put(UUID.fromString(id), sec.getString(id, "?"));
                } catch (IllegalArgumentException ignored) {
                    // битая запись - пропускаем
                }
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", name);
        y.set("lore", new ArrayList<>(lore));
        players.forEach((id, n) -> y.set("players." + id, n));
        String data = y.saveToString();
        if (plugin.isEnabled() && !plugin.isStopping()) SchedulerUtil.async(plugin, () -> write(data));
        else write(data);
    }

    private synchronized void write(String data) {
        try {
            plugin.getDataFolder().mkdirs();
            java.nio.file.Files.writeString(file.toPath(), data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить pumpkin.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ шаблон (название и описание)

    /** Превращает &-коды (&c, &l, &#ff8800) в теги MiniMessage, чтобы можно было писать и так, и так. */
    static String legacyToMini(String s) {
        Matcher m = LEGACY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1).toLowerCase(Locale.ROOT);
            String tag = code.startsWith("#") ? "<" + code + ">" : switch (code.charAt(0)) {
                case '0' -> "<black>";
                case '1' -> "<dark_blue>";
                case '2' -> "<dark_green>";
                case '3' -> "<dark_aqua>";
                case '4' -> "<dark_red>";
                case '5' -> "<dark_purple>";
                case '6' -> "<gold>";
                case '7' -> "<gray>";
                case '8' -> "<dark_gray>";
                case '9' -> "<blue>";
                case 'a' -> "<green>";
                case 'b' -> "<aqua>";
                case 'c' -> "<red>";
                case 'd' -> "<light_purple>";
                case 'e' -> "<yellow>";
                case 'f' -> "<white>";
                case 'k' -> "<obfuscated>";
                case 'l' -> "<bold>";
                case 'm' -> "<strikethrough>";
                case 'n' -> "<underlined>";
                case 'o' -> "<italic>";
                default -> "<reset>";
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Текст шаблона (MiniMessage или &-коды) в компонент без курсива. */
    public static Component parse(String s) {
        return Gui.MM.deserialize("<!italic>" + legacyToMini(s));
    }

    public String templateName() { return name; }

    public List<String> templateLore() { return new ArrayList<>(lore); }

    public void setName(String text) {
        name = text;
        templateChanged();
    }

    /** @return false, если достигнут лимит строк */
    public boolean addLore(String text) {
        if (lore.size() >= MAX_LORE) return false;
        lore.add(text);
        templateChanged();
        return true;
    }

    /** @param index номер строки с нуля */
    public boolean setLore(int index, String text) {
        if (index < 0 || index >= lore.size()) return false;
        lore.set(index, text);
        templateChanged();
        return true;
    }

    public boolean removeLore(int index) {
        if (index < 0 || index >= lore.size()) return false;
        lore.remove(index);
        templateChanged();
        return true;
    }

    public void clearLore() {
        lore.clear();
        templateChanged();
    }

    public void resetTemplate() {
        name = DEFAULT_NAME;
        lore.clear();
        lore.addAll(DEFAULT_LORE);
        templateChanged();
    }

    /**
     * Берёт название и описание с любого предмета («положить свой предмет»).
     * Форматирование сохраняется (цвета, градиенты).
     *
     * @return true, если у предмета было хоть название, хоть описание
     */
    public boolean copyFrom(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        boolean got = false;
        if (meta.hasDisplayName() && meta.displayName() != null) {
            name = Gui.MM.serialize(meta.displayName());
            got = true;
        }
        if (meta.hasLore() && meta.lore() != null) {
            lore.clear();
            for (Component c : meta.lore()) {
                if (lore.size() >= MAX_LORE) break;
                lore.add(Gui.MM.serialize(c));
            }
            got = true;
        }
        if (got) templateChanged();
        return got;
    }

    private void templateChanged() {
        save();
        refreshOnline();
    }

    // ------------------------------------------------------------------ предмет

    /**
     * Собирает тыкву по текущему шаблону.
     *
     * @param marked true - с тегом в NBT (настоящая тыква); false - только для показа в меню
     */
    public ItemStack build(boolean marked) {
        ItemStack it = new ItemStack(Material.CARVED_PUMPKIN);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(parse(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(parse(s));
        meta.lore(l);
        meta.addEnchant(Enchantment.BINDING_CURSE, 1, true);
        meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
        if (marked) meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    /** Это настоящая тыква плагина (есть тег в NBT)? */
    public boolean isMarked(ItemStack it) {
        if (it == null || it.getType() != Material.CARVED_PUMPKIN || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ список игроков

    public boolean has(UUID id) { return players.containsKey(id); }

    public int count() { return players.size(); }

    /** Список для меню: сначала те, кто в сети, потом по нику. */
    public List<UUID> uuids() {
        List<UUID> out = new ArrayList<>(players.keySet());
        out.sort(Comparator
                .comparing((UUID u) -> !plugin.profiles().isOnline(u))
                .thenComparing(u -> nameOf(u).toLowerCase(Locale.ROOT)));
        return out;
    }

    public String nameOf(UUID id) {
        String onlineName = plugin.profiles().onlineName(id);
        if (onlineName != null) return onlineName;
        String n = players.get(id);
        return n == null ? "?" : n;
    }

    /** Включает тыкву игроку. Если он в сети - надевается сразу, иначе при заходе. */
    public boolean enable(UUID id, String nick) {
        if (players.containsKey(id)) return false;
        players.put(id, nick);
        save();
        Player p = plugin.profiles().onlinePlayer(id);
        if (p != null) SchedulerUtil.onEntity(plugin, p, () -> apply(p));
        return true;
    }

    /** Выключает тыкву: она пропадает у этого игрока (если он в сети - сразу, иначе при заходе). */
    public boolean disable(UUID id) {
        if (players.remove(id) == null) return false;
        save();
        Player p = plugin.profiles().onlinePlayer(id);
        if (p != null) SchedulerUtil.onEntity(plugin, p, () -> strip(p, true));
        return true;
    }

    public boolean toggle(UUID id, String nick) {
        if (has(id)) {
            disable(id);
            return false;
        }
        enable(id, nick);
        return true;
    }

    public void clearAll() {
        List<UUID> ids = new ArrayList<>(players.keySet());
        players.clear();
        save();
        for (UUID id : ids) {
            Player p = plugin.profiles().onlinePlayer(id);
            if (p != null) SchedulerUtil.onEntity(plugin, p, () -> strip(p, true));
        }
    }

    private record Target(UUID id, String name) {}

    /** Ищет игрока по нику: сначала в сети, потом среди профилей ClientWatch, потом среди уже добавленных. */
    private Target resolve(String nick) {
        UUID onlineId = plugin.profiles().onlineUuidExact(nick);
        if (onlineId != null) {
            String onlineName = plugin.profiles().onlineName(onlineId);
            return new Target(onlineId, onlineName == null ? nick : onlineName);
        }
        ProfileManager.Profile pr = plugin.profiles().find(nick);
        if (pr != null) return new Target(pr.uuid, pr.name);
        for (Map.Entry<UUID, String> e : players.entrySet()) {
            if (e.getValue().equalsIgnoreCase(nick)) return new Target(e.getKey(), e.getValue());
        }
        return null;
    }

    public void enableByName(CommandSender s, String nick) {
        Target t = resolve(nick);
        if (t == null) {
            s.sendMessage("§cИгрок §f" + nick + " §cне найден (нужен онлайн или профиль в ClientWatch).");
            return;
        }
        if (enable(t.id(), t.name())) {
            s.sendMessage("§a[Проверка] Тыква включена: §f" + t.name()
                    + (plugin.profiles().isOnline(t.id()) ? "" : " §7(игрок не в сети, наденется при заходе)"));
        } else {
            s.sendMessage("§7[Проверка] У §f" + t.name() + " §7тыква уже включена.");
        }
    }

    public void disableByName(CommandSender s, String nick) {
        Target t = resolve(nick);
        if (t != null && disable(t.id())) {
            s.sendMessage("§e[Проверка] Тыква выключена: §f" + t.name());
        } else {
            s.sendMessage("§7[Проверка] У §f" + nick + " §7тыквы нет.");
        }
    }

    // ------------------------------------------------------------------ надеть / снять

    private static boolean isEmpty(ItemStack it) { return it == null || it.getType().isAir(); }

    /** Надевает (или обновляет) тыкву. Прежний шлем уходит в свободный слот. */
    public void apply(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack cur = inv.getHelmet();
        if (!isMarked(cur) && !isEmpty(cur)) moveAway(p, cur);
        inv.setHelmet(build(true));
    }

    /** Шлем -> свободный слот в инвентаре; нет места - вторая рука; нет и её - на землю, подобрать может только владелец. */
    private void moveAway(Player p, ItemStack helmet) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (isEmpty(inv.getItem(i))) {
                inv.setItem(i, helmet);
                return;
            }
        }
        if (isEmpty(inv.getItemInOffHand())) {
            inv.setItemInOffHand(helmet);
            return;
        }
        Item drop = p.getWorld().dropItem(p.getLocation(), helmet);
        drop.setOwner(p.getUniqueId());
        p.sendMessage("§c[Проверка] Инвентарь полон - твой шлем выпал на землю рядом, подбери его.");
    }

    /** Убирает помеченные тыквы у игрока; includeHelmet=false - ту, что на голове, не трогать. @return убрали ли что-то */
    private boolean strip(Player p, boolean includeHelmet) {
        PlayerInventory inv = p.getInventory();
        boolean changed = false;
        for (int i = 0; i < 36; i++) {
            if (isMarked(inv.getItem(i))) {
                inv.setItem(i, null);
                changed = true;
            }
        }
        if (isMarked(inv.getItemInOffHand())) { inv.setItemInOffHand(null); changed = true; }
        if (isMarked(inv.getChestplate())) { inv.setChestplate(null); changed = true; }
        if (isMarked(inv.getLeggings())) { inv.setLeggings(null); changed = true; }
        if (isMarked(inv.getBoots())) { inv.setBoots(null); changed = true; }
        if (includeHelmet && isMarked(inv.getHelmet())) { inv.setHelmet(null); changed = true; }
        if (isMarked(p.getItemOnCursor())) { p.setItemOnCursor(null); changed = true; }
        return changed;
    }

    /** Обновляет тыквы у тех, кто сейчас в сети (после смены названия/описания). */
    private void refreshOnline() {
        for (Player p : plugin.profiles().onlinePlayers()) {
            SchedulerUtil.onEntity(plugin, p, () -> {
                if (has(p.getUniqueId())) apply(p);
            });
        }
    }

    private void tickPlayer(Player p) {
        if (!p.isOnline() || p.isDead()) return;
        if (has(p.getUniqueId())) {
            if (!isMarked(p.getInventory().getHelmet())) apply(p); // сняли (креатив, /clear...) - вернуть
            strip(p, false);                                        // копии в других слотах - убрать
        } else {
            strip(p, true);                                         // тыква не у своего владельца - пропадает
        }
    }

    // ------------------------------------------------------------------ события

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        schedulePlayerTick(p);
        SchedulerUtil.onEntityLater(plugin, p, 2L, () -> {
            if (!p.isOnline()) return;
            if (has(p.getUniqueId())) {
                if (!p.getName().equals(players.get(p.getUniqueId()))) {
                    players.put(p.getUniqueId(), p.getName());
                    save();
                }
                apply(p);
            } else {
                strip(p, true);
            }
        });
    }

    /** Умер - тыква не выпадает, а после возрождения надевается снова. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isMarked);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!has(p.getUniqueId())) return;
        SchedulerUtil.onEntityLater(plugin, p, 2L, () -> {
            if (p.isOnline() && has(p.getUniqueId())) apply(p);
        });
    }

    /** Любая тыква, которая появляется на земле, исчезает. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isMarked(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    /** С тыквой нельзя ничего делать в инвентаре: ни двигать, ни копировать (креатив), ни менять с хотбаром. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (isMarked(e.getCurrentItem()) || isMarked(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
                && e.getWhoClicked() instanceof Player p
                && isMarked(p.getInventory().getItem(e.getHotbarButton()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (isMarked(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent e) {
        if (isMarked(e.getItem())) e.setCancelled(true);
    }

    /** Если тыква всё-таки оказалась в чужом хранилище (сундук и т.п.) - при закрытии она пропадает. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getInventory();
        if (top.getType() == InventoryType.PLAYER || top.getType() == InventoryType.CRAFTING) return;
        ItemStack[] c = top.getContents();
        for (int i = 0; i < c.length; i++) {
            if (isMarked(c[i])) top.setItem(i, null);
        }
    }
}
