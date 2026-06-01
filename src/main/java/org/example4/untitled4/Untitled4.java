
package org.example4.untitled4;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Untitled4 implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mymoddownloader");
    private static final String COMMAND_NAME = "acceptmods";
    private static final Path CONSENT_FILE = FabricLoader.getInstance().getConfigDir().resolve("mod_downloader_consent.txt");

    private static String GAME_VERSION;
    private static String MOD_LOADER;
    private static final int DELAY_SECONDS = 60;
    private static boolean consentGiven = false;
    private static boolean threadStarted = false;

    private static final List<Genre> GENRES = List.of(
            new Genre("Adventureя", List.of("adventure", "worldgen"), 10),
            new Genre("Technology", List.of("technology", "storage"), 8),
            new Genre("Magic", List.of("magic"), 7),
            new Genre("Decoration", List.of("decoration", "food"), 5),
            new Genre("Optimization", List.of("optimization"), 2)
    );

    private static final String USER_AGENT = "MyModDownloader/1.5 (your@email.com)";
    private static final String API_BASE = "https://api.modrinth.com/v2";

    private final Set<String> downloadedModIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> localModIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> incompatibleModIds = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, List<JSONObject>> genreCaches = new HashMap<>();
    private final Map<String, Integer> genreOffsets = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onInitialize() {
        GAME_VERSION = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("1.20.1");
        MOD_LOADER = "fabric";

        // 1. Проверяем, было ли уже дано согласие (из файла)
        loadConsentStatus();

        // 2. Регистрация команды
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal(COMMAND_NAME)
                    .executes(context -> {
                        if (!consentGiven) {
                            giveConsent();
                            context.getSource().sendFeedback(Text.literal("§a[Downloader] Consent accepted. Mod download will start in the background."));
                        } else {
                            context.getSource().sendFeedback(Text.literal("§e[Downloader] You have already confirmed consent previously."));
                        }
                        return 1;
                    }));
        });

        // 3. Сообщение при входе в мир
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!consentGiven) {
                // Отложенный запуск, чтобы чат успел прогрузиться
                client.execute(() -> {
                    if (client.player != null) {
                        String warning = "§6§lWARNING! §rTo start downloading mods, enter the command §b/" + COMMAND_NAME + "§r. " +
                                "By entering it, you confirm the download of files from Modrinth.com. " +
                                "The mod author is not responsible for the safety, stability, and content of third-party files. " +
                                "You assume all risks for any damage to your saves or the game.";
                        client.player.sendMessage(Text.literal(warning), false);
                    }
                });
            }
        });

        // 4. Если согласие уже есть, запускаем поток сразу
        if (consentGiven) {
            startDownloaderThread();
        }
    }

    private void loadConsentStatus() {
        if (Files.exists(CONSENT_FILE)) {
            try {
                String content = Files.readString(CONSENT_FILE).trim();
                consentGiven = "accepted".equals(content);
            } catch (IOException ignored) {}
        }
    }

    private void giveConsent() {
        consentGiven = true;
        try {
            Files.writeString(CONSENT_FILE, "accepted");
        } catch (IOException e) {
            LOGGER.error("Failed to save consent file: {}", e.getMessage());
        }
        startDownloaderThread();
    }

    private synchronized void startDownloaderThread() {
        if (threadStarted) return;
        threadStarted = true;

        LOGGER.info("=== Mod downloader activated ===");
        cleanTempFiles();

        Thread loaderThread = new Thread(() -> {
            scanExistingMods();
            startModDownloadLoop();
        }, "Modrinth-Downloader");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    // --- ОСТАЛЬНАЯ ЧАСТЬ ВАШЕГО КОДА БЕЗ ИЗМЕНЕНИЙ (scanExistingMods, startModDownloadLoop и т.д.) ---
    // (Просто вставьте сюда все методы из вашего исходного кода, начиная с cleanTempFiles() до конца класса)


    private void cleanTempFiles() {
        Path modsFolder = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.exists(modsFolder)) return;

        try (var stream = Files.list(modsFolder)) {
            stream.filter(path -> path.toString().endsWith(".tmp"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOGGER.info("[Protection] Removed incomplete file: {}", path.getFileName());
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete temporary file {}: {}", path.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException ignored) {}
    }

    private void scanExistingMods() {
        LOGGER.info("Scanning mods folder...");
        Path modsFolder = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.exists(modsFolder)) return;

        List<Path> jarFiles;
        try (var stream = Files.list(modsFolder)) {
            jarFiles = stream
                    .filter(path -> path.toString().endsWith(".jar"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to read mods folder: {}", e.getMessage());
            return;
        }

        List<String> hashes = new ArrayList<>();
        for (Path jar : jarFiles) {
            String localId = getModIdFromJar(jar);
            if (localId != null) {
                localModIds.add(localId);
            }
            parseConflictsFromJar(jar);
            try {
                hashes.add(getFileSHA1(jar));
            } catch (Exception e) {
                LOGGER.warn("Failed to calculate hash for file {}: {}", jar.getFileName(), e.getMessage());
            }
        }

        if (!hashes.isEmpty()) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("hashes", new JSONArray(hashes));
                payload.put("algorithm", "sha1");

                String response = sendPostRequestWithRetry(API_BASE + "/version_files", payload.toString());
                JSONObject result = new JSONObject(response);

                for (String hashKey : result.keySet()) {
                    JSONObject versionObj = result.getJSONObject(hashKey);
                    String projectId = versionObj.getString("project_id");
                    downloadedModIds.add(projectId);
                }
                LOGGER.info("Modrinth recognized {} mods in the folder.", downloadedModIds.size());
            } catch (Exception e) {
                LOGGER.error("Failed to synchronize existing mods with Modrinth API: {}", e.getMessage());
            }
        }
    }

    private void startModDownloadLoop() {
        while (true) {
            try {
                JSONObject modToAttempt = findRandomMod();
                if (modToAttempt == null) {
                    Thread.sleep(10000);
                    continue;
                }

                String modId = modToAttempt.getString("project_id");
                String slug = modToAttempt.getString("slug");

                if (downloadedModIds.contains(modId) || localModIds.contains(slug) || incompatibleModIds.contains(modId)) {
                    downloadedModIds.add(modId);
                    continue;
                }

                if (processModDownload(modId)) {
                    if (DELAY_SECONDS > 0) {
                        Thread.sleep(DELAY_SECONDS * 1000L);
                    }
                } else {
                    downloadedModIds.add(modId);
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                LOGGER.error("Loop error: {}", e.getMessage());;
                try { Thread.sleep(10000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private JSONObject findRandomMod() throws Exception {
        Genre selectedGenre = selectWeightedGenre(GENRES);
        genreCaches.putIfAbsent(selectedGenre.name, new ArrayList<>());
        genreOffsets.putIfAbsent(selectedGenre.name, 0);

        List<JSONObject> cache = genreCaches.get(selectedGenre.name);
        List<JSONObject> freshMods = cache.stream()
                .filter(m -> !downloadedModIds.contains(m.getString("project_id"))
                        && !localModIds.contains(m.getString("slug"))
                        && !incompatibleModIds.contains(m.getString("project_id")))
                .collect(Collectors.toList());

        if (freshMods.isEmpty()) {
            fetchMoreMods(selectedGenre);
            return null;
        }

        return freshMods.get(random.nextInt(freshMods.size()));
    }

    private void fetchMoreMods(Genre genre) throws Exception {
        int offset = genreOffsets.get(genre.name);
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:mod"));
        facets.put(new JSONArray().put("versions:" + GAME_VERSION));
        facets.put(new JSONArray().put("categories:" + MOD_LOADER));

        JSONArray catFacets = new JSONArray();
        genre.tags.forEach(t -> catFacets.put("categories:" + t));
        facets.put(catFacets);

        String url = API_BASE + "/search?limit=20&offset=" + offset + "&facets=" + URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8);
        String response = sendGetRequestWithRetry(url);

        JSONObject json = new JSONObject(response);
        JSONArray hits = json.getJSONArray("hits");

        for (int i = 0; i < hits.length(); i++) {
            genreCaches.get(genre.name).add(hits.getJSONObject(i));
        }
        genreOffsets.put(genre.name, offset + hits.length());
    }

    private boolean processModDownload(String rootProjectId) {
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        List<ModTask> tasksToDownload = new ArrayList<>();

        queue.add(rootProjectId);
        visited.add(rootProjectId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            if (downloadedModIds.contains(currentId)) continue;

            try {
                String projectUrl = API_BASE + "/project/" + currentId;
                String projectResponse = sendGetRequestWithRetry(projectUrl);
                JSONObject projectJson = new JSONObject(projectResponse);

                String slug = projectJson.getString("slug");
                String title = projectJson.getString("title");

                if (localModIds.contains(slug)) {
                    downloadedModIds.add(currentId);
                    continue;
                }

                // --- ИСКЛЮЧЕНИЕ НЕЖЕЛАТЕЛЬНЫХ ЛИЦЕНЗИЙ ---
                JSONObject licenseObj = projectJson.optJSONObject("license");
                String licenseId = (licenseObj != null ? licenseObj.optString("id", "unknown") : "unknown").toLowerCase();
                String licenseName = licenseObj != null ? licenseObj.optString("name", "Unknown License") : "Unknown License";
                String licenseUrl = licenseObj != null ? licenseObj.optString("url", "") : "";

                boolean isForbidden = licenseId.contains("arr")
                        || licenseId.contains("all-rights-reserved")
                        || licenseId.contains("custom")
                        || licenseId.contains("cc-by-nc")
                        || licenseId.contains("gpl"); // Охватывает gpl-2.0, gpl-3.0, lgpl и т.д.

                if (isForbidden) {
                    LOGGER.warn("[Protection] Mod '{}' skipped. Forbidden license: {} ({})", title, licenseName, licenseId);
                    downloadedModIds.add(currentId);
                    continue;
                }

                // Поиск релизной версии
                String encodedLoaders = URLEncoder.encode("[\"" + MOD_LOADER + "\"]", StandardCharsets.UTF_8);
                String encodedVersions = URLEncoder.encode("[\"" + GAME_VERSION + "\"]", StandardCharsets.UTF_8);
                String versionsUrl = API_BASE + "/project/" + currentId + "/version?loaders=" + encodedLoaders + "&game_versions=" + encodedVersions;

                String versionsResponse = sendGetRequestWithRetry(versionsUrl);
                JSONArray versions = new JSONArray(versionsResponse);

                JSONObject releaseVersion = null;
                for (int i = 0; i < versions.length(); i++) {
                    JSONObject ver = versions.getJSONObject(i);
                    if ("release".equals(ver.optString("version_type"))) {
                        releaseVersion = ver;
                        break;
                    }
                }

                if (releaseVersion == null) continue;

                // Получение авторов
                String membersUrl = API_BASE + "/project/" + currentId + "/members";
                String membersResponse = sendGetRequestWithRetry(membersUrl);
                JSONArray membersArray = new JSONArray(membersResponse);
                List<String> authorsList = new ArrayList<>();
                for (int i = 0; i < membersArray.length(); i++) {
                    authorsList.add(membersArray.getJSONObject(i).getJSONObject("user").getString("username"));
                }
                String authors = authorsList.isEmpty() ? "Unknown Author" : String.join(", ", authorsList);

                // Зависимости
                JSONArray dependencies = releaseVersion.optJSONArray("dependencies");
                boolean conflictFound = false;
                if (dependencies != null) {
                    for (int i = 0; i < dependencies.length(); i++) {
                        JSONObject dep = dependencies.getJSONObject(i);
                        String depType = dep.optString("dependency_type");
                        String depId = dep.optString("project_id");

                        if (depId == null || depId.isEmpty() || depId.equals("null")) {
                            String verId = dep.optString("version_id");
                            if (verId != null) depId = getProjectIdFromVersion(verId);
                        }
                        if (depId == null) continue;

                        if ("incompatible".equals(depType)) {
                            incompatibleModIds.add(depId);
                            if (downloadedModIds.contains(depId) || visited.contains(depId)) {
                                conflictFound = true;
                                break;
                            }
                        } else if ("required".equals(depType)) {
                            if (!visited.contains(depId) && !downloadedModIds.contains(depId)) {
                                visited.add(depId);
                                queue.add(depId);
                            }
                        }
                    }
                }

                if (conflictFound) continue;

                JSONArray files = releaseVersion.getJSONArray("files");
                if (files.isEmpty()) continue;

                JSONObject file = files.getJSONObject(0);
                tasksToDownload.add(new ModTask(currentId, title, file.getString("filename"),
                        file.getString("url"), authors, licenseName, licenseId, licenseUrl));

            } catch (Exception e) {
                LOGGER.error("Error parsing mod {}: {}", currentId, e.getMessage());
            }
        }

        if (tasksToDownload.isEmpty()) return false;

        boolean anyDownloaded = false;
        for (ModTask task : tasksToDownload) {
            if (incompatibleModIds.contains(task.projectId)) continue;

            try {
                Path target = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(task.fileName);
                Path tempFile = target.resolveSibling(target.getFileName().toString() + ".tmp");

                LOGGER.info("[+] Downloading: {} (License: {} [{}])", task.title, task.license, task.licenseId);
                downloadFileWithRetry(task.downloadUrl, tempFile);

                String actualFabricId = getModIdFromJar(tempFile);
                if (actualFabricId != null && localModIds.contains(actualFabricId)) {
                    Files.deleteIfExists(tempFile);
                    downloadedModIds.add(task.projectId);
                    continue;
                }

                if (actualFabricId != null) {
                    localModIds.add(actualFabricId);
                    parseConflictsFromJar(tempFile);
                }

                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                downloadedModIds.add(task.projectId);
                anyDownloaded = true;

                // Сохранение лицензии на диск для офлайн-доступа
                saveLicenseLocally(task, target);

                // Вывод в чат с кнопкой открытия локального файла
                sendLicenseChatMessage(task);

            } catch (Exception e) {
                LOGGER.error("Failed to save {}: {}", task.title, e.getMessage());
            }
        }
        return anyDownloaded;
    }

    // --- ЛОКАЛЬНОЕ СОХРАНЕНИЕ ЛИЦЕНЗИЙ (ОФЛАЙН РАБОТА) ---
    private void saveLicenseLocally(ModTask task, Path jarPath) {
        try {
            Path licensesDir = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("licenses");
            Files.createDirectories(licensesDir);

            String baseName = task.fileName.replaceAll("(?i)\\.jar$", "");
            Path localLicenseFile = licensesDir.resolve(baseName + "_LICENSE.txt");
            task.localLicensePath = localLicenseFile;

            boolean extracted = false;

            // 1. Попытка вытащить файл лицензии прямо из скачанного JAR-файла
            try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();

                    // Ищем файлы типа LICENSE, LICENSE.txt, LICENCE, LICENSE-MIT и т.д. в корне JAR
                    if (!entry.isDirectory() && !name.contains("/") && (name.startsWith("license") || name.startsWith("licence"))) {
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            Files.copy(is, localLicenseFile, StandardCopyOption.REPLACE_EXISTING);
                            extracted = true;
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}

            // 2. Если внутри JAR лицензии не нашлось, генерируем подробную локальную визитку
            if (!extracted) {
                List<String> lines = List.of(
                        "==================================================",
                        "MOD INFO (OFFLINE LICENSE DATA)",
                        "==================================================",
                        "Mod Name:    " + task.title,
                        "File Name:   " + task.fileName,
                        "Author(s):   " + task.authors,
                        "License:     " + task.license + " (" + task.licenseId.toUpperCase() + ")",
                        "Online URL:  " + (task.licenseUrl.isEmpty() ? "None provided" : task.licenseUrl),
                        "==================================================",
                        "",
                        "Note: No explicit license file was found packed inside the mod's JAR.",
                        "This document preserves the metadata specified by the author on Modrinth."
                );
                Files.write(localLicenseFile, lines, StandardCharsets.UTF_8);
            }

            // 3. Дописываем информацию в общий сводный файл LICENSES_SUMMARY.txt
            Path summaryFile = licensesDir.resolve("LICENSES_SUMMARY.txt");
            String summaryEntry = String.format(
                    "==================================================\r\n" +
                            "Mod: %s\r\n" +
                            "File: %s\r\n" +
                            "Authors: %s\r\n" +
                            "License: %s (%s)\r\n" +
                            "Local file: %s\r\n" +
                            "==================================================\r\n\r\n",
                    task.title, task.fileName, task.authors, task.license, task.licenseId.toUpperCase(), localLicenseFile.getFileName().toString()
            );
            Files.writeString(summaryFile, summaryEntry, StandardCharsets.UTF_8,
                    Files.exists(summaryFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);

        } catch (Exception e) {
            LOGGER.error("Failed to save local license copy for {}: {}", task.title, e.getMessage());
        }
    }

    private void sendLicenseChatMessage(ModTask task) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        client.execute(() -> {
            client.player.sendMessage(Text.literal("§a[Downloader] Successfully downloaded: §f" + task.title), false);
            client.player.sendMessage(Text.literal("§8↳ §7Authors: §e" + task.authors), false);
            client.player.sendMessage(Text.literal("§8↳ §7License: §b" + task.license + " §8(§3" + task.licenseId.toUpperCase() + "§8)"), false);

            if (task.localLicensePath != null) {
                MutableText offlineText = Text.literal("§8↳ §7Offline file: §a" + task.localLicensePath.getFileName().toString());
                offlineText.append(" §8[§6Open§8]")
                        .styled(style -> style.withClickEvent(new ClickEvent(
                                ClickEvent.Action.OPEN_FILE,
                                task.localLicensePath.toAbsolutePath().toString()
                        )));
                client.player.sendMessage(offlineText, false);
            }
        });
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ СЕТИ И УТИЛИТЫ ---

    private String getProjectIdFromVersion(String versionId) throws Exception {
        String response = sendGetRequestWithRetry(API_BASE + "/version/" + versionId);
        return new JSONObject(response).getString("project_id");
    }

    private String sendGetRequestWithRetry(String url) throws Exception {
        while (true) {
            try { return sendGetRequest(url); }
            catch (Exception e) { if (isNetworkException(e)) waitForInternet(); else throw e; }
        }
    }

    private String sendPostRequestWithRetry(String url, String jsonBody) throws Exception {
        while (true) {
            try { return sendPostRequest(url, jsonBody); }
            catch (Exception e) { if (isNetworkException(e)) waitForInternet(); else throw e; }
        }
    }

    private void downloadFileWithRetry(String url, Path target) throws Exception {
        while (true) {
            try { downloadFile(url, target); return; }
            catch (Exception e) { if (isNetworkException(e)) waitForInternet(); else throw e; }
        }
    }

    private String sendGetRequest(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) { Thread.sleep(30000); return sendGetRequest(url); }
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return response.body();
    }

    private String sendPostRequest(String url, String jsonBody) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream is = response.body()) { Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING); }
        try (ZipFile zf = new ZipFile(target.toFile())) { } catch (IOException e) { Files.deleteIfExists(target); throw e; }
    }

    private void waitForInternet() {
        try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
    }

    private boolean isNetworkException(Throwable t) {
        return t instanceof UnknownHostException || t instanceof ConnectException || t instanceof HttpConnectTimeoutException;
    }

    private String getFileSHA1(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream fis = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = fis.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String getModIdFromJar(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    return new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8)).optString("id", null);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void parseConflictsFromJar(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    JSONObject breaks = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8)).optJSONObject("breaks");
                    if (breaks != null) for (String key : breaks.keySet()) incompatibleModIds.add(key);
                }
            }
        } catch (Exception ignored) {}
    }

    private Genre selectWeightedGenre(List<Genre> genres) {
        int total = genres.stream().mapToInt(g -> g.weight).sum();
        int r = random.nextInt(total);
        for (Genre g : genres) { if ((r -= g.weight) < 0) return g; }
        return genres.get(0);
    }

    public static class Genre {
        String name; List<String> tags; int weight;
        public Genre(String n, List<String> t, int w) { name = n; tags = t; weight = w; }
    }

    private static class ModTask {
        final String projectId, title, fileName, downloadUrl, authors, license, licenseId, licenseUrl;
        Path localLicensePath; // Путь к файлу на диске для офлайн-доступа

        ModTask(String id, String t, String f, String d, String a, String l, String lid, String lu) {
            this.projectId = id; this.title = t; this.fileName = f;
            this.downloadUrl = d; this.authors = a; this.license = l;
            this.licenseId = lid; this.licenseUrl = lu;
        }
    }
}
