package dev.antifreecam.watch;

import org.bukkit.Material;

public enum WatchStatus {
    FORBIDDEN("Запрещён", Material.RED_DYE),
    SUSPICIOUS("Требует проверки", Material.YELLOW_DYE),
    ALLOWED("Разрешён", Material.LIME_DYE),
    LEGACY("Старая разрешённая версия", Material.GRAY_DYE);

    private final String display;
    private final Material material;

    WatchStatus(String display, Material material) {
        this.display = display;
        this.material = material;
    }

    public String display() { return display; }
    public Material material() { return material; }

    public WatchStatus next() {
        return switch (this) {
            case FORBIDDEN -> SUSPICIOUS;
            case SUSPICIOUS -> ALLOWED;
            case ALLOWED -> LEGACY;
            case LEGACY -> FORBIDDEN;
        };
    }

    public static WatchStatus parse(String value) {
        if (value == null) return FORBIDDEN;
        try { return valueOf(value.toUpperCase()); }
        catch (IllegalArgumentException ignored) { return FORBIDDEN; }
    }
}
