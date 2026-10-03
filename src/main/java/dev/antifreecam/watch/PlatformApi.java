package dev.antifreecam.watch;

import org.bukkit.configuration.ConfigurationSection;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Small REST client for the official Modrinth v2 API and CurseForge v1 API.
 *
 * Modrinth exposes SHA-512 directly. CurseForge exposes SHA-1/MD5/fingerprint,
 * so when enabled this class computes SHA-512 from the published download itself.
 */
public final class PlatformApi {
    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final String CURSEFORGE = "https://api.curseforge.com/v1";
    private static final String CF_GAME = "432"; // Minecraft Java
    private static final String CF_LOADER = "4"; // Fabric

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final java.util.function.Supplier<org.bukkit.configuration.file.FileConfiguration> config;

    public PlatformApi(java.util.function.Supplier<org.bukkit.configuration.file.FileConfiguration> config) {
        this.config = config;
    }

    public CompletableFuture<List<ModProject.SearchResult>> search(String query) {
        String q = query == null ? "" : query.strip();
        if (q.isBlank()) return CompletableFuture.completedFuture(List.of());

        List<CompletableFuture<List<ModProject.SearchResult>>> futures = new ArrayList<>();
        if (enabled("platforms.modrinth.enabled")) futures.add(CompletableFuture.supplyAsync(() -> safeSearchModrinth(q)));
        if (curseKey().isBlank() == false && enabled("platforms.curseforge.enabled")) {
            futures.add(CompletableFuture.supplyAsync(() -> safeSearchCurseForge(q)));
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(List.of());

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> {
                    Map<String, ModProject.SearchResult> unique = new LinkedHashMap<>();
                    for (CompletableFuture<List<ModProject.SearchResult>> f : futures) {
                        try {
                            for (ModProject.SearchResult r : f.join()) unique.putIfAbsent(r.key(), r);
                        } catch (CompletionException ignored) {}
                    }
                    return unique.values().stream()
                            .sorted(Comparator.comparing(ModProject.SearchResult::title, String.CASE_INSENSITIVE_ORDER)
                                    .thenComparing(r -> r.platform().name()))
                            .limit(Math.max(1, config.get().getInt("platforms.search-limit", 50)))
                            .toList();
                });
    }

    public CompletableFuture<ModProject.Entry> loadEntry(ModProject.SearchResult project, String query) {
        return CompletableFuture.supplyAsync(() -> {
            return switch (project.platform()) {
                case MODRINTH -> loadModrinth(project, query);
                case CURSEFORGE -> loadCurseForge(project, query);
            };
        });
    }

    private List<ModProject.SearchResult> safeSearchModrinth(String query) {
        try { return searchModrinth(query); }
        catch (Exception e) { return List.of(); }
    }

    private List<ModProject.SearchResult> safeSearchCurseForge(String query) {
        try { return searchCurseForge(query); }
        catch (Exception e) { return List.of(); }
    }

    private List<ModProject.SearchResult> searchModrinth(String query) throws Exception {
        String facets = URLEncoder.encode("[[\"project_type:mod\"],[\"versions:26.2\"]]",
                StandardCharsets.UTF_8);
        URI uri = URI.create(MODRINTH + "/search?query=" + enc(query)
                + "&limit=" + Math.min(100, Math.max(1, config.get().getInt("platforms.search-limit", 50)))
                + "&facets=" + facets);
        Map<String, Object> root = MiniJson.object(get(uri, Map.of(
                "User-Agent", "ClientWatch/2.2 (server plugin)"
        )));
        List<ModProject.SearchResult> out = new ArrayList<>();
        for (Object raw : list(root.get("hits"))) {
            Map<String, Object> h = map(raw);
            String id = str(h.get("project_id"));
            String title = str(h.get("title"));
            if (id.isBlank() || title.isBlank()) continue;
            String slug = str(h.get("slug"));
            String url = "https://modrinth.com/mod/" + (slug.isBlank() ? id : slug);
            out.add(new ModProject.SearchResult(
                    "modrinth:" + id,
                    ModProject.Platform.MODRINTH, id, title, slug,
                    str(h.get("author")), str(h.get("description")), url));
        }
        return out;
    }

    private ModProject.Entry loadModrinth(ModProject.SearchResult p, String query) {
        try {
            String gv = URLEncoder.encode("[\"26.2\"]", StandardCharsets.UTF_8);
            String loaders = URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8);
            URI uri = URI.create(MODRINTH + "/project/" + encPath(p.projectId())
                    + "/version?loaders=" + loaders + "&game_versions=" + gv + "&include_changelog=false");
            List<Object> versions = MiniJson.array(get(uri, Map.of(
                    "User-Agent", "ClientWatch/2.2 (server plugin)"
            )));
            List<ModProject.FileInfo> files = new ArrayList<>();
            Set<String> depIds = new LinkedHashSet<>();

            for (Object raw : versions) {
                Map<String, Object> v = map(raw);
                String version = str(v.get("version_number"));
                List<ModProject.Dependency> deps = new ArrayList<>();
                for (Object depRaw : list(v.get("dependencies"))) {
                    Map<String, Object> d = map(depRaw);
                    String type = str(d.get("dependency_type"));
                    String depId = str(d.get("project_id"));
                    if (!depId.isBlank()) depIds.add(depId);
                    deps.add(new ModProject.Dependency(
                            !depId.isBlank() ? depId : str(d.get("file_name")),
                            type.isBlank() ? "required" : type));
                }

                List<Object> vf = list(v.get("files"));
                if (vf.isEmpty()) continue;
                Map<String, Object> chosen = null;
                for (Object rawFile : vf) {
                    Map<String, Object> f = map(rawFile);
                    if (bool(f.get("primary"))) { chosen = f; break; }
                }
                if (chosen == null) chosen = map(vf.get(0));
                Map<String, Object> hashes = map(chosen.get("hashes"));
                files.add(new ModProject.FileInfo(
                        version,
                        str(chosen.get("filename")),
                        str(hashes.get("sha512")),
                        str(hashes.get("sha1")),
                        "",
                        deps));
                if (files.size() >= Math.max(1, config.get().getInt("platforms.max-files-per-project", 24))) break;
            }

            Map<String, String> depNames = new LinkedHashMap<>();
            int maxDeps = Math.max(0, config.get().getInt("platforms.max-dependencies-per-project", 12));
            int n = 0;
            for (String dep : depIds) {
                if (n++ >= maxDeps) break;
                try {
                    Map<String, Object> d = MiniJson.object(get(
                            URI.create(MODRINTH + "/project/" + encPath(dep)),
                            Map.of("User-Agent", "ClientWatch/2.2 (server plugin)")));
                    depNames.put(dep, str(d.get("title"), dep));
                } catch (Exception ignored) {}
            }
            List<ModProject.FileInfo> resolved = new ArrayList<>();
            for (ModProject.FileInfo f : files) {
                List<ModProject.Dependency> deps = f.dependencies().stream()
                        .map(d -> new ModProject.Dependency(depNames.getOrDefault(d.name(), d.name()), d.relation()))
                        .toList();
                resolved.add(new ModProject.FileInfo(f.version(), f.filename(), f.sha512(), f.sha1(), f.fingerprint(), deps));
            }
            return new ModProject.Entry(p.key(), query, p, WatchStatus.FORBIDDEN, resolved, System.currentTimeMillis());
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private List<ModProject.SearchResult> searchCurseForge(String query) throws Exception {
        String key = curseKey();
        URI uri = URI.create(CURSEFORGE + "/mods/search?gameId=" + CF_GAME
                + "&classId=6&searchFilter=" + enc(query)
                + "&gameVersion=26.2&modLoaderType=" + CF_LOADER
                + "&pageSize=" + Math.min(50, Math.max(1, config.get().getInt("platforms.search-limit", 50)))
                + "&sortField=2&sortOrder=desc");
        Map<String, Object> root = MiniJson.object(get(uri, Map.of(
                "x-api-key", key,
                "User-Agent", "ClientWatch/2.2"
        )));
        List<ModProject.SearchResult> out = new ArrayList<>();
        for (Object raw : list(root.get("data"))) {
            Map<String, Object> h = map(raw);
            String id = str(h.get("id"));
            String title = str(h.get("name"));
            if (id.isBlank() || title.isBlank()) continue;
            String slug = str(h.get("slug"));
            String url = str(map(h.get("links")).get("websiteUrl"));
            if (url.isBlank()) url = "https://www.curseforge.com/minecraft/mc-mods/" + slug;
            String author = "";
            List<Object> authors = list(h.get("authors"));
            if (!authors.isEmpty()) author = str(map(authors.get(0)).get("name"));
            out.add(new ModProject.SearchResult(
                    "curseforge:" + id, ModProject.Platform.CURSEFORGE, id, title, slug,
                    author, str(h.get("summary")), url));
        }
        return out;
    }

    private ModProject.Entry loadCurseForge(ModProject.SearchResult p, String query) {
        try {
            URI uri = URI.create(CURSEFORGE + "/mods/" + encPath(p.projectId()) + "/files?gameVersion=26.2"
                    + "&modLoaderType=" + CF_LOADER
                    + "&pageSize=" + Math.min(50, Math.max(1, config.get().getInt("platforms.max-files-per-project", 24)))
                    + "&index=0");
            Map<String, Object> root = MiniJson.object(get(uri, Map.of(
                    "x-api-key", curseKey(), "User-Agent", "ClientWatch/2.2")));
            List<ModProject.FileInfo> files = new ArrayList<>();
            Set<String> depIds = new LinkedHashSet<>();

            for (Object raw : list(root.get("data"))) {
                Map<String, Object> f = map(raw);
                String filename = str(f.get("fileName"));
                String version = str(f.get("displayName"));
                String sha1 = "";
                String md5 = "";
                for (Object hashRaw : list(f.get("hashes"))) {
                    Map<String, Object> hash = map(hashRaw);
                    String v = str(hash.get("value"));
                    String algo = str(hash.get("algo"));
                    if ("1".equals(algo)) sha1 = v;
                    else if ("2".equals(algo)) md5 = v;
                }
                List<ModProject.Dependency> deps = new ArrayList<>();
                for (Object depRaw : list(f.get("dependencies"))) {
                    Map<String, Object> d = map(depRaw);
                    String depId = str(d.get("modId"));
                    String relation = relation(str(d.get("relationType")));
                    if (!depId.isBlank()) depIds.add(depId);
                    if (!depId.isBlank()) deps.add(new ModProject.Dependency(depId, relation));
                }

                String downloadUrl = str(f.get("downloadUrl"));
                String sha512 = "";
                if (config.get().getBoolean("platforms.curseforge.compute-sha512", true)
                        && !downloadUrl.isBlank()
                        && files.size() < Math.max(1, config.get().getInt("platforms.max-sha512-files-per-project", 10))) {
                    sha512 = downloadSha512(downloadUrl);
                }

                files.add(new ModProject.FileInfo(
                        version.isBlank() ? filename : version,
                        filename, sha512, sha1, str(f.get("fileFingerprint")), deps));
            }

            Map<String, String> depNames = new LinkedHashMap<>();
            int maxDeps = Math.max(0, config.get().getInt("platforms.max-dependencies-per-project", 12));
            int n = 0;
            for (String dep : depIds) {
                if (n++ >= maxDeps) break;
                try {
                    Map<String, Object> d = MiniJson.object(get(
                            URI.create(CURSEFORGE + "/mods/" + encPath(dep)),
                            Map.of("x-api-key", curseKey(), "User-Agent", "ClientWatch/2.2")));
                    depNames.put(dep, str(d.get("name"), dep));
                } catch (Exception ignored) {}
            }
            List<ModProject.FileInfo> resolved = new ArrayList<>();
            for (ModProject.FileInfo f : files) {
                List<ModProject.Dependency> deps = f.dependencies().stream()
                        .map(d -> new ModProject.Dependency(depNames.getOrDefault(d.name(), d.name()), d.relation()))
                        .toList();
                resolved.add(new ModProject.FileInfo(f.version(), f.filename(), f.sha512(), f.sha1(), f.fingerprint(), deps));
            }

            return new ModProject.Entry(p.key(), query, p, WatchStatus.FORBIDDEN, resolved, System.currentTimeMillis());
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private String downloadSha512(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "ClientWatch/2.2")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            long max = Math.max(1, config.get().getLong("platforms.max-sha512-bytes", 200L * 1024L * 1024L));
            try (InputStream in = response.body()) {
                int read;
                while ((read = in.read(buf)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > max) return "";
                    digest.update(buf, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private String get(URI uri, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET();
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " " + uri);
        }
        return r.body();
    }

    private boolean enabled(String path) {
        return config.get().getBoolean(path, true);
    }

    private String curseKey() {
        return config.get().getString("platforms.curseforge.api-key", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encPath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String relation(String n) {
        return switch (n) {
            case "1" -> "embedded";
            case "2" -> "optional";
            case "3" -> "required";
            case "4" -> "tool";
            case "5" -> "incompatible";
            case "6" -> "included";
            default -> "unknown";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw) {
        return raw instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String str(Object raw, String fallback) {
        String v = str(raw);
        return v.isBlank() ? fallback : v;
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean b && b;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
