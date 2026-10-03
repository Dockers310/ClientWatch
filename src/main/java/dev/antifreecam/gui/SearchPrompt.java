package dev.antifreecam.gui;

import dev.antifreecam.AntiFreecam;
import dev.antifreecam.util.SchedulerUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Поиск в списке срабатываний: следующее сообщение игрока в чате считается запросом. */
public final class SearchPrompt implements Listener {

    private record Waiting(long until, String previous, boolean players) {}

    /** Произвольный запрос текста из чата (название/описание тыквы, ник и т.п.). */
    private record Ask(long until, Consumer<String> onText, Runnable onCancel) {}

    private final AntiFreecam plugin;
    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Ask> asks = new ConcurrentHashMap<>();

    public SearchPrompt(AntiFreecam plugin) {
        this.plugin = plugin;
    }

    public void begin(Player p, String previousFilter) {
        begin(p, previousFilter, false);
    }

    /** players = true: искать в списке всех игроков, иначе - в списке срабатываний. */
    public void begin(Player p, String previousFilter, boolean players) {
        waiting.put(p.getUniqueId(), new Waiting(System.currentTimeMillis() + 30_000, previousFilter, players));
        p.closeInventory();
        p.sendMessage("§e[Проверка] Напиши в чат ник или название мода. §7«отмена» - назад, «*» - сбросить поиск. §8(30 сек)");
    }

    /**
     * Просит игрока написать текст в чат. Следующее его сообщение уходит в onText (не в общий чат).
     * «отмена» - вызывается onCancel. Ждём 60 секунд.
     */
    public void ask(Player p, String message, Consumer<String> onText, Runnable onCancel) {
        asks.put(p.getUniqueId(), new Ask(System.currentTimeMillis() + 60_000, onText, onCancel));
        p.closeInventory();
        p.sendMessage(message);
        p.sendMessage("§7«отмена» - вернуться назад. §8(60 сек)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Ask ask = asks.remove(id);
        if (ask != null && System.currentTimeMillis() <= ask.until()) {
            e.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(e.message()).strip();
            Player p = e.getPlayer();
            SchedulerUtil.onEntity(plugin, p, () -> {
                if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) ask.onCancel().run();
                else ask.onText().accept(text);
            });
            return;
        }
        Waiting w = waiting.get(id);
        if (w == null) return;
        if (System.currentTimeMillis() > w.until()) {
            waiting.remove(id);
            return;
        }
        waiting.remove(id);
        e.setCancelled(true); // сообщение не уходит в чат/Discord
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).strip();
        Player p = e.getPlayer();
        SchedulerUtil.onEntity(plugin, p, () -> {
            String filter;
            if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) filter = w.previous();
            else if (text.equals("*") || text.equals("-")) filter = null;
            else filter = text;
            if (w.players()) new PlayersGui(plugin, 0, filter).open(p);
            else new FlagGui(plugin, 0, filter).open(p);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
        asks.remove(e.getPlayer().getUniqueId());
    }
}
