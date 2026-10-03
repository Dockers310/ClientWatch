package dev.antifreecam.watch;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ModProject {
    public enum Platform { MODRINTH, CURSEFORGE }

    public record SearchResult(
            String key,
            Platform platform,
            String projectId,
            String title,
            String slug,
            String author,
            String description,
            String url
    ) {
        public String normalizedTitle() {
            return title == null ? "" : title.toLowerCase(Locale.ROOT);
        }
    }

    public record Dependency(String name, String relation) {}

    public record FileInfo(
            String version,
            String filename,
            String sha512,
            String sha1,
            String fingerprint,
            List<Dependency> dependencies
    ) {
        public FileInfo {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    public static final class Entry {
        private final String key;
        private final String query;
        private volatile SearchResult project;
        private volatile WatchStatus status;
        private volatile boolean allVersions;
        private final List<FileInfo> files;
        private volatile long updated;

        public Entry(String key, String query, SearchResult project, WatchStatus status,
                     List<FileInfo> files, long updated) {
            this(key, query, project, status, files, updated, false);
        }

        public Entry(String key, String query, SearchResult project, WatchStatus status,
                     List<FileInfo> files, long updated, boolean allVersions) {
            this.key = Objects.requireNonNull(key);
            this.query = query == null ? project.title() : query;
            this.project = Objects.requireNonNull(project);
            this.status = status == null ? WatchStatus.FORBIDDEN : status;
            this.allVersions = allVersions;
            this.files = files == null ? List.of() : List.copyOf(files);
            this.updated = updated;
        }

        public String key() { return key; }
        public String query() { return query; }
        public SearchResult project() { return project; }

        /**
         * Обновляет только отображаемое название проекта, не меняя его ID/slug и другие данные.
         * Используется, чтобы после старых/повреждённых записей вида modrinth:...
         * GUI мог восстановить нормальное название из фактически обнаруженного мода.
         */
        public void title(String title) {
            if (title == null || title.isBlank() || title.equals(project.title())) return;
            SearchResult p = project;
            project = new SearchResult(p.key(), p.platform(), p.projectId(), title, p.slug(),
                    p.author(), p.description(), p.url());
        }
        public WatchStatus status() { return status; }
        public boolean allVersions() { return allVersions; }
        public void allVersions(boolean value) { this.allVersions = value; this.updated = System.currentTimeMillis(); }
        public List<FileInfo> files() { return files; }
        public long updated() { return updated; }

        public void status(WatchStatus status) {
            this.status = Objects.requireNonNull(status);
            this.updated = System.currentTimeMillis();
        }

        public boolean hasSha512(String sha512) {
            if (sha512 == null || sha512.isBlank()) return false;
            for (FileInfo f : files) if (sha512.equalsIgnoreCase(f.sha512())) return true;
            return false;
        }

        public boolean hasSha1(String sha1) {
            if (sha1 == null || sha1.isBlank()) return false;
            for (FileInfo f : files) if (sha1.equalsIgnoreCase(f.sha1())) return true;
            return false;
        }

        /**
         * Проверяет, относится ли установленный мод именно к этому проекту.
         * Поисковый query здесь намеренно не используется: он нужен только для поиска
         * в Modrinth/CurseForge и может быть слишком общим (например, "a").
         */
        public boolean matchesInstalledMod(String modId, String display) {
            String id = normalizeId(modId);
            String name = normalize(display);

            // У одного и того же проекта могут использоваться разные идентификаторы:
            // Fabric mod id, slug Modrinth/CurseForge, project-id или название.
            // Сопоставляем только точные значения из этого конкретного проекта.
            List<String> idAliases = List.of(
                    normalizeId(project.projectId()),
                    normalizeId(project.slug()),
                    normalizeId(project.key()),
                    normalizeId(project.key().replaceFirst("^(modrinth|curseforge):", ""))
            );
            List<String> nameAliases = List.of(
                    normalize(project.title()),
                    normalize(project.slug()),
                    normalize(project.key()),
                    normalize(project.key().replaceFirst("^(modrinth|curseforge):", ""))
            );

            if (!id.isEmpty() && idAliases.stream().anyMatch(a -> !a.isEmpty() && id.equals(a))) return true;
            if (!name.isEmpty() && nameAliases.stream().anyMatch(a -> !a.isEmpty() && name.equals(a))) return true;

            // Некоторые Fabric-моды используют id вроде "sodium-fabric", тогда как
            // каталог содержит slug "sodium". Разрешаем только безопасное сравнение
            // через суффикс, если после удаления стандартного "-fabric" остаётся точное имя.
            if (!id.isEmpty()) {
                String stripped = id.endsWith("_fabric") ? id.substring(0, id.length() - 7)
                        : id.endsWith("-fabric") ? id.substring(0, id.length() - 7) : id;
                for (String a : idAliases) {
                    if (!a.isEmpty() && stripped.equals(a)) return true;
                }
            }
            return false;
        }

        /** Сохраняем старый метод для совместимости внутренних вызовов. */
        public boolean relatedTo(String modId, String display) {
            return matchesInstalledMod(modId, display);
        }

        private static String normalize(String value) {
            if (value == null) return "";
            return value.trim().toLowerCase(Locale.ROOT)
                    .replace(' ', '_')
                    .replace('-', '_');
        }

        private static String normalizeId(String value) {
            if (value == null) return "";
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record Classification(WatchStatus status, Entry entry, boolean exactHash, String reason) {
        public boolean known() { return entry != null; }
    }

    private ModProject() {}
}
