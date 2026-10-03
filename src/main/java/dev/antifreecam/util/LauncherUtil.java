package dev.antifreecam.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Превращает сырой client brand (канал "minecraft:brand", уже приходит в
 * {@code player.getClientBrandName()}) в человекочитаемое название лаунчера/клиента.
 *
 * Это НЕ надёжное определение: читерские клиенты и просто заботящиеся о приватности
 * лаунчеры могут присылать любую строку (чаще всего "vanilla" или "fabric"), чтобы
 * не выделяться. Точных гарантий тут нет - см. также lunarclient.dev/client-brand.
 * Однако большинство обычных лаунчеров (Lunar, Badlion, Feather/Dawn, LabyMod и т.д.)
 * этого не делают, поэтому в подавляющем большинстве случаев строка честная и по ней
 * удобно ориентироваться в профиле игрока.
 */
public final class LauncherUtil {

    private LauncherUtil() {}

    /** порядок важен: более специфичные подстроки проверяются раньше общих (fabric/forge). */
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    static {
        PREFIXES.put("lunarclient", "Lunar Client");
        PREFIXES.put("lunar-client", "Lunar Client");
        PREFIXES.put("badlion", "Badlion Client");
        PREFIXES.put("feather", "Feather Client / Dawn");
        PREFIXES.put("dawn", "Dawn (бывший Feather)");
        PREFIXES.put("labymod", "LabyMod");
        PREFIXES.put("laby-mod", "LabyMod");
        PREFIXES.put("polar", "Polar Client");
        PREFIXES.put("pvplounge", "PvPLounge Client");
        PREFIXES.put("hyperium", "Hyperium");
        PREFIXES.put("vivecraft", "Vivecraft (VR)");
        PREFIXES.put("liteloader", "LiteLoader");
        PREFIXES.put("axolotlclient", "Axolotl Client");
        PREFIXES.put("axolotl-client", "Axolotl Client");
        PREFIXES.put("cheatbreaker", "CheatBreaker");
        PREFIXES.put("salwyrr", "Salwyrr Client");
        PREFIXES.put("meteor", "Meteor Client");
        PREFIXES.put("wurst", "Wurst Client");
        PREFIXES.put("liquidbounce", "LiquidBounce");
        PREFIXES.put("combatant", "Combatant Client");
        PREFIXES.put("heatseeker", "HeatSeeker");
        PREFIXES.put("playeralert", "HeatSeeker (playeralert)");
        PREFIXES.put("neoforge", "NeoForge");
        PREFIXES.put("forge", "Forge");
        PREFIXES.put("fabric", "Fabric");
        PREFIXES.put("quilt", "Quilt");
        PREFIXES.put("optifine", "OptiFine");
        PREFIXES.put("vanilla", "Ванильный клиент");
        PREFIXES.put("geyser", "Geyser (Bedrock)");
    }

    /**
     * @param brand сырое содержимое channel-а minecraft:brand (может быть пустым/null)
     * @return человекочитаемое название или null, если ничего не распознано (покажем сырую строку как есть)
     */
    public static String identify(String brand) {
        if (brand == null || brand.isBlank()) return null;
        String low = brand.toLowerCase(Locale.ROOT);
        for (var e : PREFIXES.entrySet()) {
            if (low.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}
