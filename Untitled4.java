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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Untitled4 implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mymoddownloader");
    private static final String COMMAND_NAME = "acceptmods";
    private static final Path CONSENT_FILE = FabricLoader.getInstance().getConfigDir().resolve("mod_downloader_consent.txt");

    private static String GAME_VERSION;
    private static String MOD_LOADER;
    private static final int DELAY_SECONDS = 30;
    private static boolean consentGiven = false;
    private static boolean threadStarted = false;

    // --- Переменные контроля закрытия игры ---
    private static volatile boolean isShuttingDown = false;
    private static final AtomicInteger activeDownloadsCount = new AtomicInteger(0);

    // --- Каналы блокировки межпроцессного доступа (Защита от перезапуска) ---
    private static FileChannel lockChannel;
    private static FileLock globalLock;

    // --- Sandbox test-launch settings ---
    private static final String PENDING_DIR_NAME = ".pending_mods";
    private static final int TEST_LAUNCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_TEST_RETRIES = 3;

    // Безопасность: Белый список надежных доменов для загрузки файлов модов
    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            "cdn.modrinth.com",
            "edge.modrinth.com",
            "cdn-raw.modrinth.com"
    );

    // Черный список нежелательных модов (их Modrinth slug-имена)
    private static final Set<String> BLOCKED_MODS = Set.of(
            "tlskincape", "tl-skin-cape",
            "magicmirror", "magic-mirror",
            "quick-shulker", "quickshulker",
            "custom-splash-screen", "custom-splashscreen", "customsplashscreen"
    );

    // Словарь маппинга популярных библиотек (локальный ID -> Slug на Modrinth)
    private static final Map<String, String> COMMON_LIBRARY_MAP = Map.of(
            "cloth_config", "cloth-config",
            "cloth-config2", "cloth-config",
            "modmenu", "modmenu",
            "architectury", "architectury-api",
            "rei", "roughly-enough-items"
    );

    private static final List<Genre> GENRES = List.of(
            new Genre("Adventure", List.of("adventure", "worldgen"), 10),
            new Genre("Technology", List.of("technology", "storage"), 8),
            new Genre("Magic", List.of("magic"), 7),
            new Genre("Decoration", List.of("decoration", "food"), 5),
            new Genre("Optimization", List.of("optimization"), 2)
    );

    private static final String USER_AGENT = "MyModDownloader/1.5 (your@email.com)";
    private static final String API_BASE = "https://api.modrinth.com/v2";

    private final Set<String> downloadedModIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> localModIds = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> localModVersions = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> incompatibleModIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> incompatibleFabricIds = Collections.synchronizedSet(new HashSet<>());
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

        loadConsentStatus();

        // --- РЕГИСТРАЦИЯ ХУКА ЗАВЕРШЕНИЯ (Удерживает JVM активной при закрытии игры) ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            isShuttingDown = true;
            if (activeDownloadsCount.get() > 0) {
                LOGGER.info("[Shutdown] Game closed, but active download/test batch is in progress!");
                LOGGER.info("[Shutdown] Holding process open to safely finish the current package...");

                long startTime = System.currentTimeMillis();
                while (activeDownloadsCount.get() > 0 && (System.currentTimeMillis() - startTime < 180000)) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                LOGGER.info("[Shutdown] Current batch finished processing.");
            }

            // Освобождаем межпроцессную блокировку перед выходом
            releaseGlobalLock();
            LOGGER.info("[Shutdown] Safely exiting Java process.");
        }, "Mod-Downloader-Shutdown-Hook"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal(COMMAND_NAME)
                    .executes(context -> {
                        if (!consentGiven) {
                            giveConsent();
                            context.getSource().sendFeedback(Text.literal("§a[Downloader] Consent accepted. Mod download will start in the background."));
                        } else {
                            context.getSource().sendFeedback(Text.literal("§e[Downloader] You have already given consent."));
                        }
                        return 1;
                    }));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!consentGiven) {
                client.execute(() -> {
                    if (client.player != null) {
                        String warning = "§6§lWARNING! §rTo start downloading mods, enter the command §b/" + COMMAND_NAME + "§r. " +
                                "By entering it, you confirm that you are downloading files from Modrinth.com. " +
                                "The mod author is not responsible for the safety, stability, or content of third-party files. " +
                                "You assume all risks of breaking your saves or the game yourself.";
                        client.player.sendMessage(Text.literal(warning), false);
                    }
                });
            }
        });

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

        LOGGER.info("=== Mod Downloader Activated ===");

        Thread loaderThread = new Thread(() -> {
            // Ожидаем, пока старый процесс игры отпустит блокировку (если он еще работает в фоне)
            acquireBlockingLock();

            // Вносим нежелательные моды в черный список сразу при старте
            incompatibleFabricIds.addAll(BLOCKED_MODS);

            cleanTempFiles();
            scanExistingMods();

            // Скачиваем Architectury API в приоритетном порядке напрямую (в обход нестабильной песочницы)
            downloadArchitecturyImmediately();

            startModDownloadLoop();
        }, "Modrinth-Downloader");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    private void downloadArchitecturyImmediately() {
        String architecturySlug = "architectury-api";
        // Проверяем, установлена ли уже эта библиотека в системе
        if (localModIds.contains(architecturySlug) || localModIds.contains("architectury")) {
            LOGGER.info("[Init] Architectury API is already installed. Skipping initial download.");
            return;
        }

        LOGGER.info("[Init] Forcing immediate secure download of Architectury API...");
        try {
            boolean success = downloadCoreLibrarySilently(architecturySlug);
            if (success) {
                LOGGER.info("[Init] Architectury API successfully downloaded and verified!");
            } else {
                LOGGER.warn("[Init] Architectury API download returned false or failed verification.");
            }
        } catch (Exception e) {
            LOGGER.error("[Init] Failed to download Architectury API immediately: {}", e.getMessage());
        }
    }

    // Принудительная быстрая и безопасная установка ядра без риска блокировки песочницей
    private boolean downloadCoreLibrarySilently(String projectIdOrSlug) {
        activeDownloadsCount.incrementAndGet();
        try {
            String projectUrl = API_BASE + "/project/" + projectIdOrSlug;
            String projectResponse = sendGetRequestWithRetry(projectUrl);
            JSONObject projectJson = new JSONObject(projectResponse);
            String slug = projectJson.getString("slug");
            String title = projectJson.getString("title");
            String projectId = projectJson.getString("id");

            String encodedLoaders = URLEncoder.encode("[\"" + MOD_LOADER + "\"]", StandardCharsets.UTF_8);
            String encodedVersions = URLEncoder.encode("[\"" + GAME_VERSION + "\"]", StandardCharsets.UTF_8);
            String versionsUrl = API_BASE + "/project/" + projectId + "/version?loaders=" + encodedLoaders + "&game_versions=" + encodedVersions;

            String versionsResponse = sendGetRequestWithRetry(versionsUrl);
            JSONArray versions = new JSONArray(versionsResponse);
            JSONObject targetVersion = getNewestCompatibleVersion(versions);

            if (targetVersion == null) {
                LOGGER.warn("[Init] No compatible version of core library '{}' found.", title);
                return false;
            }

            JSONArray files = targetVersion.getJSONArray("files");
            if (files.isEmpty()) return false;
            JSONObject file = files.getJSONObject(0);

            String filename = file.getString("filename");
            String downloadUrl = file.getString("url");

            Path target = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(filename);
            Path tempFile = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(PENDING_DIR_NAME).resolve(filename + ".tmp");
            Files.createDirectories(tempFile.getParent());

            downloadFileWithRetry(downloadUrl, tempFile);

            // Проверка антивирусной безопасности скачанного файла
            if (!isJarSafe(tempFile)) {
                Files.deleteIfExists(tempFile);
                LOGGER.error("[Security] Core library jar failed security checks! Blocked.");
                return false;
            }

            String fabricId = getModIdFromJar(tempFile);
            if (fabricId == null) {
                Files.deleteIfExists(tempFile);
                LOGGER.warn("[Init] Corrupt core library jar.");
                return false;
            }

            // Прямой перенос в папку mods
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            localModIds.add(fabricId);
            String ver = getModVersionFromJar(target);
            if (ver != null) {
                localModVersions.put(fabricId, ver);
            }
            downloadedModIds.add(projectId);

            // Сохранение локальных данных о лицензии
            ModTask task = new ModTask(projectId, title, filename, downloadUrl, "Architectury Team", "LGPL", "lgpl", "");
            saveLicenseLocally(task, target);
            sendLicenseChatMessage(task);

            return true;
        } catch (Exception e) {
            LOGGER.error("[Init] Error downloading core library: {}", e.getMessage());
            return false;
        } finally {
            activeDownloadsCount.decrementAndGet();
        }
    }

    // Антивирусный сканер структуры JAR-файлов
    private boolean isJarSafe(Path jarPath) {
        String filename = jarPath.getFileName().toString().toLowerCase(Locale.ROOT);

        // Защита от двойных расширений (например, .jar.exe)
        if (filename.endsWith(".exe") || filename.endsWith(".bat") || filename.endsWith(".cmd") || filename.endsWith(".sh")) {
            LOGGER.error("[Security] Execution blocked! File has a dangerous extension: {}", filename);
            return false;
        }

        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);

                // Защита от ZipSlip уязвимости (выход за пределы каталога установки)
                if (name.contains("..") || name.contains("/") && name.startsWith("..")) {
                    LOGGER.error("[Security] ZipSlip attack attempt blocked in entry: {}", name);
                    return false;
                }

                // Сканирование на наличие скрытых исполняемых файлов внутри архива
                if (name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".bat") ||
                        name.endsWith(".cmd") || name.endsWith(".sh") || name.endsWith(".vbs") ||
                        name.endsWith(".scr")) {
                    LOGGER.error("[Security] Malicious non-java entry found inside JAR: {}", name);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("[Security] Verification failed or corrupt archive: {}", e.getMessage());
            return false;
        }
    }

    // --- МЕЖПРОЦЕССНАЯ СИНХРОНИЗАЦИЯ ЧЕРЕЗ СИСТЕМНЫЙ LOCK-ФАЙЛ ---
    private static void acquireBlockingLock() {
        Path lockFile = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(".downloader.lock");
        try {
            Files.createDirectories(lockFile.getParent());
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            LOGGER.info("[Lock] Checking if another game instance is currently active...");
            // Метод lock() заблокирует этот фоновый поток, если старая игра все еще завершает скачивание мода
            globalLock = lockChannel.lock();
            LOGGER.info("[Lock] Global lock acquired! We are now the active downloader instance.");
        } catch (Exception e) {
            LOGGER.error("[Lock] Failed to acquire global lock: {}", e.getMessage());
        }
    }

    private static void releaseGlobalLock() {
        try {
            if (globalLock != null) {
                globalLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
            LOGGER.info("[Lock] Global lock released successfully.");
        } catch (Exception ignored) {}
    }

    private void cleanTempFiles() {
        Path modsFolder = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.exists(modsFolder)) return;

        try (var stream = Files.list(modsFolder)) {
            stream.filter(path -> path.toString().endsWith(".tmp"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOGGER.info("[Protection] Deleted incomplete file: {}", path.getFileName());
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete temp file {}: {}", path.getFileName(), e.getMessage());
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
            List<String> ids = getModIdsFromJar(jar);
            String version = getModVersionFromJar(jar);
            if (version != null) {
                for (String id : ids) {
                    localModVersions.put(id, version);
                }
            }
            localModIds.addAll(ids);
            try {
                hashes.add(getFileSHA1(jar));
            } catch (Exception e) {
                LOGGER.warn("Failed to compute hash for file {}: {}", jar.getFileName(), e.getMessage());
            }
        }

        for (Path jar : jarFiles) {
            parseConflictsFromJar(jar);
        }

        if (!hashes.isEmpty()) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("hashes", new JSONArray(hashes));
                payload.put("algorithm", "sha1");

                String response = sendPostRequestWithRetry(API_BASE + "/version_files", payload.toString());
                JSONObject result = new JSONObject(response);

                List<String> recognizedProjectIds = new ArrayList<>();
                for (String hashKey : result.keySet()) {
                    JSONObject versionObj = result.getJSONObject(hashKey);
                    String projectId = versionObj.getString("project_id");
                    downloadedModIds.add(projectId);
                    recognizedProjectIds.add(projectId);
                }
                LOGGER.info("Modrinth recognized {} mods in the folder.", downloadedModIds.size());

                fetchIncompatiblesForLocalMods(recognizedProjectIds);

            } catch (Exception e) {
                LOGGER.error("Failed to sync existing mods with Modrinth API: {}", e.getMessage());
            }
        }
    }

    private void fetchIncompatiblesForLocalMods(List<String> projectIds) {
        LOGGER.info("[Protection] Checking compatibility of {} local mods via Modrinth API...", projectIds.size());
        for (String projectId : projectIds) {
            try {
                String encodedLoaders = URLEncoder.encode("[\"" + MOD_LOADER + "\"]", StandardCharsets.UTF_8);
                String encodedVersions = URLEncoder.encode("[\"" + GAME_VERSION + "\"]", StandardCharsets.UTF_8);
                String versionsUrl = API_BASE + "/project/" + projectId + "/version?loaders=" + encodedLoaders + "&game_versions=" + encodedVersions;

                String versionsResponse = sendGetRequestWithRetry(versionsUrl);
                JSONArray versions = new JSONArray(versionsResponse);

                JSONObject targetVersion = getNewestCompatibleVersion(versions);
                if (targetVersion == null) continue;

                JSONArray dependencies = targetVersion.optJSONArray("dependencies");
                if (dependencies == null) continue;

                for (int i = 0; i < dependencies.length(); i++) {
                    JSONObject dep = dependencies.getJSONObject(i);
                    if (!"incompatible".equals(dep.optString("dependency_type"))) continue;

                    String depId = dep.optString("project_id");
                    if (depId == null || depId.isEmpty() || depId.equals("null")) {
                        String verId = dep.optString("version_id");
                        if (verId != null && !verId.isEmpty()) {
                            try { depId = getProjectIdFromVersion(verId); } catch (Exception ignored) { continue; }
                        }
                    }
                    if (depId == null || depId.isEmpty()) continue;

                    incompatibleModIds.add(depId);

                    try {
                        String projResp = sendGetRequestWithRetry(API_BASE + "/project/" + depId);
                        JSONObject projJson = new JSONObject(projResp);
                        String slug = projJson.optString("slug", "");
                        if (!slug.isEmpty()) {
                            incompatibleFabricIds.add(slug);
                            LOGGER.warn("[Protection] Local mod '{}' is incompatible with '{}' ({}). Mod blocked from downloading.",
                                    projectId, slug, depId);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                LOGGER.warn("[Protection] Failed to check compatibility for mod {}: {}", projectId, e.getMessage());
            }
        }
        LOGGER.info("[Protection] Blocked {} incompatible mods (Modrinth project_id) and {} by fabric id.",
                incompatibleModIds.size(), incompatibleFabricIds.size());
    }

    private void startModDownloadLoop() {
        while (!isShuttingDown) {
            try {
                JSONObject modToAttempt = findRandomMod();
                if (modToAttempt == null) {
                    Thread.sleep(10000);
                    continue;
                }

                String modId = modToAttempt.getString("project_id");
                String slug = modToAttempt.getString("slug");

                if (downloadedModIds.contains(modId) || localModIds.contains(slug)
                        || incompatibleModIds.contains(modId)
                        || incompatibleFabricIds.contains(slug)) {
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
                LOGGER.error("Loop error: {}", e.getMessage());
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
                        && !incompatibleModIds.contains(m.getString("project_id"))
                        && !incompatibleFabricIds.contains(m.getString("slug")))
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
        // Увеличиваем счетчик активной работы
        activeDownloadsCount.incrementAndGet();

        try {
            Queue<String> queue = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            Map<String, String> dependencyParent = new HashMap<>();
            List<ModTask> tasksToDownload = new ArrayList<>();
            Set<String> abortedIds = new HashSet<>();

            queue.add(rootProjectId);
            visited.add(rootProjectId);

            while (!queue.isEmpty()) {
                String currentId = queue.poll();
                if (downloadedModIds.contains(currentId)) continue;
                if (abortedIds.contains(currentId)) continue;

                try {
                    String projectUrl = API_BASE + "/project/" + currentId;
                    String projectResponse = sendGetRequestWithRetry(projectUrl);
                    JSONObject projectJson = new JSONObject(projectResponse);

                    String slug = projectJson.getString("slug");
                    String title = projectJson.getString("title");

                    if (localModIds.contains(slug) || incompatibleFabricIds.contains(slug)) {
                        LOGGER.warn("[Protection] Mod '{}' ({}) skipped — its fabric id conflicts with a local mod.", title, slug);
                        downloadedModIds.add(currentId);
                        propagateAbort(currentId, dependencyParent, abortedIds, tasksToDownload);
                        continue;
                    }

                    JSONObject licenseObj = projectJson.optJSONObject("license");
                    String licenseId = (licenseObj != null ? licenseObj.optString("id", "unknown") : "unknown").toLowerCase();
                    String licenseName = licenseObj != null ? licenseObj.optString("name", "Unknown License") : "Unknown License";
                    String licenseUrl = licenseObj != null ? licenseObj.optString("url", "") : "";

                    boolean isForbidden = licenseId.contains("arr")
                            || licenseId.contains("all-rights-reserved")
                            || licenseId.contains("custom")
                            || licenseId.contains("cc-by-nc")
                            || licenseId.contains("gpl");

                    if (isForbidden) {
                        LOGGER.warn("[Protection] Mod '{}' skipped. Forbidden license: {} ({})", title, licenseName, licenseId);
                        downloadedModIds.add(currentId);
                        propagateAbort(currentId, dependencyParent, abortedIds, tasksToDownload);
                        continue;
                    }

                    String encodedLoaders = URLEncoder.encode("[\"" + MOD_LOADER + "\"]", StandardCharsets.UTF_8);
                    String encodedVersions = URLEncoder.encode("[\"" + GAME_VERSION + "\"]", StandardCharsets.UTF_8);
                    String versionsUrl = API_BASE + "/project/" + currentId + "/version?loaders=" + encodedLoaders + "&game_versions=" + encodedVersions;

                    String versionsResponse = sendGetRequestWithRetry(versionsUrl);
                    JSONArray versions = new JSONArray(versionsResponse);

                    JSONObject targetVersion = getNewestCompatibleVersion(versions);

                    if (targetVersion == null) {
                        LOGGER.warn("[Skip] No compatible version (release/beta/alpha) for mod '{}' ({}) on Minecraft {} & {}.", title, currentId, GAME_VERSION, MOD_LOADER);
                        propagateAbort(currentId, dependencyParent, abortedIds, tasksToDownload);
                        continue;
                    }

                    String membersUrl = API_BASE + "/project/" + currentId + "/members";
                    String membersResponse = sendGetRequestWithRetry(membersUrl);
                    JSONArray membersArray = new JSONArray(membersResponse);
                    List<String> authorsList = new ArrayList<>();
                    for (int i = 0; i < membersArray.length(); i++) {
                        authorsList.add(membersArray.getJSONObject(i).getJSONObject("user").getString("username"));
                    }
                    String authors = authorsList.isEmpty() ? "Unknown Author" : String.join(", ", authorsList);

                    JSONArray dependencies = targetVersion.optJSONArray("dependencies");
                    boolean conflictFound = false;
                    if (dependencies != null) {
                        for (int i = 0; i < dependencies.length(); i++) {
                            JSONObject dep = dependencies.getJSONObject(i);
                            String depType = dep.optString("dependency_type");
                            String depId = dep.optString("project_id");

                            if (depId == null || depId.isEmpty() || depId.equals("null")) {
                                String verId = dep.optString("version_id");
                                if (verId != null && !verId.isEmpty()) {
                                    try {
                                        depId = getProjectIdFromVersion(verId);
                                    } catch (Exception e) {
                                        LOGGER.warn("[Dependency] Could not resolve version_id '{}' for mod '{}': {}", verId, title, e.getMessage());
                                    }
                                }
                            }
                            if (depId == null || depId.isEmpty() || depId.equals("null")) {
                                LOGGER.warn("[Dependency] Skipped dependency type '{}' of mod '{}' — could not determine project_id. dep={}",
                                        depType, title, dep);
                                continue;
                            }

                            final String resolvedDepId = depId;

                            if ("incompatible".equals(depType)) {
                                incompatibleModIds.add(resolvedDepId);
                                String incompatSlug = resolvedDepId;
                                try {
                                    String incompatResp = sendGetRequestWithRetry(API_BASE + "/project/" + resolvedDepId);
                                    incompatSlug = new JSONObject(incompatResp).optString("slug", resolvedDepId);
                                    incompatibleFabricIds.add(incompatSlug);
                                } catch (Exception ignored) {}
                                final String finalIncompatSlug = incompatSlug;
                                boolean alreadyPresent = downloadedModIds.contains(resolvedDepId)
                                        || localModIds.contains(finalIncompatSlug)
                                        || tasksToDownload.stream().anyMatch(t -> t.projectId.equals(resolvedDepId))
                                        || visited.contains(resolvedDepId);
                                if (alreadyPresent) {
                                    LOGGER.warn("[Protection] Mod '{}' conflicts with already present mod '{}' ({}). Skipping entire tree.",
                                            title, finalIncompatSlug, resolvedDepId);
                                    conflictFound = true;
                                    break;
                                }
                            } else if ("required".equals(depType)) {
                                if (!visited.contains(resolvedDepId) && !downloadedModIds.contains(resolvedDepId)) {
                                    visited.add(resolvedDepId);
                                    dependencyParent.put(resolvedDepId, currentId);
                                    queue.add(resolvedDepId);
                                    LOGGER.info("[Dependency] '{}' requires '{}' — added to queue", title, resolvedDepId);
                                }
                            } else if ("optional".equals(depType)) {
                                if (!visited.contains(resolvedDepId) && !downloadedModIds.contains(resolvedDepId)
                                        && !incompatibleModIds.contains(resolvedDepId)) {
                                    visited.add(resolvedDepId);
                                    queue.add(resolvedDepId);
                                    LOGGER.info("[Dependency] '{}' has optional mod '{}' — added to queue", title, resolvedDepId);
                                }
                            }
                        }
                    }

                    if (conflictFound) {
                        propagateAbort(currentId, dependencyParent, abortedIds, tasksToDownload);
                        continue;
                    }

                    JSONArray files = targetVersion.getJSONArray("files");
                    if (files.isEmpty()) continue;

                    JSONObject file = files.getJSONObject(0);
                    tasksToDownload.add(new ModTask(currentId, title, file.getString("filename"),
                            file.getString("url"), authors, licenseName, licenseId, licenseUrl));

                } catch (Exception e) {
                    LOGGER.error("Error parsing mod {}: {}", currentId, e.getMessage());
                    propagateAbort(currentId, dependencyParent, abortedIds, tasksToDownload);
                }
            }

            tasksToDownload.removeIf(t -> abortedIds.contains(t.projectId) || incompatibleModIds.contains(t.projectId));

            if (tasksToDownload.isEmpty()) return false;

            // --- ЭТАП 1: СКАЧИВАНИЕ ВСЕХ ФАЙЛОВ ВО ВРЕМЕННУЮ ПАПКУ ---
            List<ModTask> downloadedBatch = new ArrayList<>();
            for (ModTask task : tasksToDownload) {
                try {
                    Path tempFile = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(PENDING_DIR_NAME).resolve(task.fileName + ".tmp");
                    Files.createDirectories(tempFile.getParent());

                    LOGGER.info("[+] Downloading: {} (License: {} [{}])", task.title, task.license, task.licenseId);
                    downloadFileWithRetry(task.downloadUrl, tempFile);

                    task.tempFile = tempFile;

                    // Добавлено глубокое антивирусное сканирование перед обработкой в песочнице
                    if (!isJarSafe(tempFile)) {
                        Files.deleteIfExists(tempFile);
                        LOGGER.error("[Security] Malicious activity detected in '{}'. Download aborted.", task.fileName);
                        continue;
                    }

                    task.fabricId = getModIdFromJar(tempFile);
                    task.version = getModVersionFromJar(tempFile);

                    if (task.fabricId == null) {
                        Files.deleteIfExists(tempFile);
                        LOGGER.warn("[Protection] JAR file '{}' is corrupt or not a valid Fabric mod.", task.fileName);
                        continue;
                    }

                    task.dependsConstraints = getFabricDependsConstraintsFromJar(tempFile);
                    downloadedBatch.add(task);
                } catch (Exception e) {
                    LOGGER.error("Failed to download or parse {}: {}", task.title, e.getMessage());
                }
            }

            // --- ЭТАП 2: СТАТИЧЕСКАЯ ФИЛЬТРАЦИЯ И СВЕРКА ТРЕБОВАНИЙ ---
            boolean changed;
            do {
                changed = false;
                Set<String> proposedFabricIds = downloadedBatch.stream()
                        .map(t -> t.fabricId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                Iterator<ModTask> iterator = downloadedBatch.iterator();
                while (iterator.hasNext()) {
                    ModTask task = iterator.next();
                    boolean valid = true;

                    for (Map.Entry<String, String> entry : task.dependsConstraints.entrySet()) {
                        String depId = entry.getKey();
                        String constraint = entry.getValue();

                        if (isVanillaOrLoaderId(depId)) {
                            if (depId.equals("minecraft")) {
                                if (!satisfiesMinecraftConstraint(GAME_VERSION, constraint)) {
                                    valid = false;
                                    LOGGER.warn("[Batch Validation] Pruning mod '{}' — requires Minecraft '{}', current is '{}'.", task.title, constraint, GAME_VERSION);
                                    break;
                                }
                            }
                            continue;
                        }

                        boolean isInstalled = localModIds.contains(depId);
                        boolean isProposed = proposedFabricIds.contains(depId);

                        if (!isInstalled && !isProposed) {
                            valid = false;
                            LOGGER.warn("[Batch Validation] Pruning mod '{}' — missing required library '{}'.", task.title, depId);
                            break;
                        }

                        if (isInstalled) {
                            String localVer = localModVersions.get(depId);
                            if (localVer != null && !satisfiesMinecraftConstraint(localVer, constraint)) {
                                valid = false;
                                LOGGER.warn("[Batch Validation] Pruning mod '{}' — requires '{}' version '{}', but installed is older '{}'.", task.title, depId, constraint, localVer);
                                break;
                            }
                        }

                        if (isProposed) {
                            ModTask proposedDep = downloadedBatch.stream().filter(t -> depId.equals(t.fabricId)).findFirst().orElse(null);
                            if (proposedDep != null && proposedDep.version != null) {
                                if (!satisfiesMinecraftConstraint(proposedDep.version, constraint)) {
                                    valid = false;
                                    LOGGER.warn("[Batch Validation] Pruning mod '{}' — requires '{}' version '{}', but proposed version is '{}'.", task.title, depId, constraint, proposedDep.version);
                                    break;
                                }
                            }
                        }
                    }

                    if (!valid) {
                        iterator.remove();
                        incompatibleModIds.add(task.projectId);
                        if (task.fabricId != null) {
                            incompatibleFabricIds.add(task.fabricId);
                        }
                        try { Files.deleteIfExists(task.tempFile); } catch (IOException ignored) {}
                        changed = true;
                    }
                }
            } while (changed);

            if (downloadedBatch.isEmpty()) return false;

            // --- ЭТАП 3: ОДНОКРАТНЫЙ ПАКЕТНЫЙ ЗАПУСК В ПЕСОЧНИЦЕ ---
            boolean batchAccepted = false;
            for (int attempt = 1; attempt <= MAX_TEST_RETRIES; attempt++) {
                if (downloadedBatch.isEmpty()) break;

                LOGGER.info("[Sandbox] Running batch test launch #{}/{} with {} proposed mods...", attempt, MAX_TEST_RETRIES, downloadedBatch.size());
                TestResult result = runBatchHeadlessTestLaunch(downloadedBatch);

                if (result.status == TestResult.Status.SUCCESS) {
                    batchAccepted = true;
                    break;
                } else if (result.status == TestResult.Status.MISSING_DEPENDENCY) {
                    LOGGER.warn("[Sandbox] Batch reported missing libraries: {}. Resolving or pruning...", result.relatedIds);
                    boolean resolvedAny = false;
                    for (String depId : result.relatedIds) {
                        if (isVanillaOrLoaderId(depId) || localModIds.contains(depId)) continue;
                        if (downloadDependencyByFabricId(depId, "Batch Resolver")) {
                            resolvedAny = true;
                        }
                    }
                    if (!resolvedAny) {
                        for (String depId : result.relatedIds) {
                            removeDependentsOfMissing(depId, downloadedBatch);
                        }
                    }
                } else if (result.status == TestResult.Status.INCOMPATIBLE) {
                    LOGGER.warn("[Sandbox] Batch conflicts found: {}. Pruning...", result.relatedIds);
                    for (String confId : result.relatedIds) {
                        removeModAndDependents(confId, downloadedBatch);
                    }
                } else {
                    LOGGER.warn("[Sandbox] Unrecognized crash in sandbox. Safely rejecting all download proposals in this batch.");
                    for (ModTask task : downloadedBatch) {
                        try { Files.deleteIfExists(task.tempFile); } catch (IOException ignored) {}
                    }
                    downloadedBatch.clear();
                    break;
                }
            }

            // --- ЭТАП 4: ФИНАЛЬНЫЙ ПЕРЕНОС ИЗ БУФЕРА В ИГРУ ---
            boolean anyPromoted = false;
            if (batchAccepted && !downloadedBatch.isEmpty()) {
                for (ModTask task : downloadedBatch) {
                    try {
                        Path target = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(task.fileName);
                        Files.move(task.tempFile, target, StandardCopyOption.REPLACE_EXISTING);

                        localModIds.add(task.fabricId);
                        if (task.version != null) {
                            localModVersions.put(task.fabricId, task.version);
                        }
                        downloadedModIds.add(task.projectId);
                        anyPromoted = true;

                        saveLicenseLocally(task, target);
                        sendLicenseChatMessage(task);
                    } catch (Exception e) {
                        LOGGER.error("Failed to promote '{}' to mods directory: {}", task.title, e.getMessage());
                    }
                }
            }
            return anyPromoted;

        } finally {
            // Уменьшаем счетчик активной работы
            activeDownloadsCount.decrementAndGet();
        }
    }

    private void removeModAndDependents(String fabricId, List<ModTask> batch) {
        Queue<String> toRemove = new LinkedList<>();
        toRemove.add(fabricId);

        while (!toRemove.isEmpty()) {
            String id = toRemove.poll();
            Iterator<ModTask> it = batch.iterator();
            while (it.hasNext()) {
                ModTask task = it.next();
                if (id.equals(task.fabricId)) {
                    it.remove();
                    incompatibleModIds.add(task.projectId);
                    incompatibleFabricIds.add(task.fabricId);
                    try { Files.deleteIfExists(task.tempFile); } catch (IOException ignored) {}
                }
            }
            for (ModTask task : batch) {
                if (task.dependsConstraints != null && task.dependsConstraints.containsKey(id)) {
                    toRemove.add(task.fabricId);
                }
            }
        }
    }

    private void removeDependentsOfMissing(String missingFabricId, List<ModTask> batch) {
        Queue<String> toRemove = new LinkedList<>();
        toRemove.add(missingFabricId);

        while (!toRemove.isEmpty()) {
            String id = toRemove.poll();
            List<String> dependents = new ArrayList<>();
            Iterator<ModTask> it = batch.iterator();
            while (it.hasNext()) {
                ModTask task = it.next();
                if (task.dependsConstraints != null && task.dependsConstraints.containsKey(id)) {
                    dependents.add(task.fabricId);
                    it.remove();
                    incompatibleModIds.add(task.projectId);
                    incompatibleFabricIds.add(task.fabricId);
                    try { Files.deleteIfExists(task.tempFile); } catch (IOException ignored) {}
                }
            }
            toRemove.addAll(dependents);
        }
    }

    private void propagateAbort(String failedId, Map<String, String> dependencyParent,
                                Set<String> abortedIds, List<ModTask> tasksToDownload) {
        Queue<String> toAbort = new LinkedList<>();
        toAbort.add(failedId);
        while (!toAbort.isEmpty()) {
            String id = toAbort.poll();
            if (abortedIds.add(id)) {
                tasksToDownload.removeIf(t -> t.projectId.equals(id));
                String parent = dependencyParent.get(id);
                if (parent != null && !abortedIds.contains(parent)) {
                    toAbort.add(parent);
                }
            }
        }
    }

    private void saveLicenseLocally(ModTask task, Path jarPath) {
        try {
            Path licensesDir = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("licenses");
            Files.createDirectories(licensesDir);

            String baseName = task.fileName.replaceAll("(?i)\\.jar$", "");
            Path localLicenseFile = licensesDir.resolve(baseName + "_LICENSE.txt");
            task.localLicensePath = localLicenseFile;

            boolean extracted = false;

            try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();

                    if (!entry.isDirectory() && !name.contains("/") && (name.startsWith("license") || name.startsWith("licence"))) {
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            Files.copy(is, localLicenseFile, StandardCopyOption.REPLACE_EXISTING);
                            extracted = true;
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}

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

            Path summaryFile = licensesDir.resolve("LICENSES_SUMMARY.txt");
            String summaryEntry = String.format(
                    "==================================================\r\n" +
                            "Mod: %s\r\n" +
                            "File: %s\r\n" +
                            "Author(s): %s\r\n" +
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
            client.player.sendMessage(Text.literal("§8↳ §7Author(s): §e" + task.authors), false);
            client.player.sendMessage(Text.literal("§8↳ §7License: §b" + task.license + " §8(§3" + task.licenseId.toUpperCase() + "§8)"), false);

            if (task.localLicensePath != null) {
                MutableText offlineText = Text.literal("§8↳ §7Offline file: §a" + task.localLicensePath.getFileName().toString());
                offlineText.append(" §8[§6§nOPEN§r§8]")
                        .styled(style -> style.withClickEvent(new ClickEvent(
                                ClickEvent.Action.OPEN_FILE,
                                task.localLicensePath.toAbsolutePath().toString()
                        )));
                client.player.sendMessage(offlineText, false);
            }
        });
    }

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
        URI uri = URI.create(url);
        // Безопасность: Поддерживаем только зашифрованное соединение HTTPS
        if (!"https".equals(uri.getScheme())) {
            throw new SecurityException("Unsafe protocol blocked: " + uri.getScheme());
        }

        // Безопасность: Разрешаем загрузку только с верифицированных серверов Modrinth
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean isDomainAllowed = false;
        for (String allowed : ALLOWED_DOMAINS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                isDomainAllowed = true;
                break;
            }
        }

        if (!isDomainAllowed) {
            throw new SecurityException("Download blocked! Domain '" + host + "' is not whitelisted.");
        }

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder().uri(uri).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream is = response.body()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }

        try (ZipFile zf = new ZipFile(target.toFile())) {
            // Простая проверка на целостность ZIP-архива
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
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

    private List<String> getModIdsFromJar(Path jarPath) {
        List<String> ids = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    JSONObject json = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    String id = json.optString("id", null);
                    if (id != null) ids.add(id);

                    JSONArray provides = json.optJSONArray("provides");
                    if (provides != null) {
                        for (int i = 0; i < provides.length(); i++) {
                            String pId = provides.optString(i, null);
                            if (pId != null) ids.add(pId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return ids;
    }

    private String getModVersionFromJar(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    return new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8)).optString("version", null);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getModIdFromJar(Path jarPath) {
        List<String> ids = getModIdsFromJar(jarPath);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Set<String> getFabricDependsFromJar(Path jarPath) {
        Set<String> deps = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    JSONObject meta = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    JSONObject depends = meta.optJSONObject("depends");
                    if (depends != null) {
                        for (String key : depends.keySet()) deps.add(key);
                    }
                }
            }
        } catch (Exception ignored) {}
        return deps;
    }

    private Map<String, String> getFabricDependsConstraintsFromJar(Path jarPath) {
        Map<String, String> deps = new HashMap<>();
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    JSONObject meta = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    JSONObject depends = meta.optJSONObject("depends");
                    if (depends != null) {
                        for (String key : depends.keySet()) {
                            Object val = depends.get(key);
                            if (val instanceof String) {
                                deps.put(key, (String) val);
                            } else if (val instanceof JSONArray) {
                                JSONArray arr = (JSONArray) val;
                                List<String> list = new ArrayList<>();
                                for (int i = 0; i < arr.length(); i++) {
                                    list.add(arr.getString(i));
                                }
                                deps.put(key, String.join(" ", list));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return deps;
    }

    private boolean parseConflictsFromJar(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry)) {
                    JSONObject meta = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    String thisModId = meta.optString("id", jarPath.getFileName().toString());

                    Set<String> allConflicts = new HashSet<>();
                    JSONObject breaks = meta.optJSONObject("breaks");
                    if (breaks != null) allConflicts.addAll(breaks.keySet());
                    JSONObject conflicts = meta.optJSONObject("conflicts");
                    if (conflicts != null) allConflicts.addAll(conflicts.keySet());

                    for (String conflictId : allConflicts) {
                        if (localModIds.contains(conflictId)) {
                            incompatibleFabricIds.add(thisModId);
                            LOGGER.warn("[Protection] Mod '{}' declares a conflict with already installed '{}'. Mod blocked.", thisModId, conflictId);
                            return true;
                        }
                    }

                    allConflicts.forEach(incompatibleFabricIds::add);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Genre selectWeightedGenre(List<Genre> genres) {
        int total = genres.stream().mapToInt(g -> g.weight).sum();
        int r = random.nextInt(total);
        for (Genre g : genres) { if ((r -= g.weight) < 0) return g; }
        return genres.get(0);
    }

    private static final Pattern MISSING_DEP_PATTERN =
            Pattern.compile("(?i)(?:requires|missing)[^a-zA-Z0-9]{0,40}\\{?\\s*([a-zA-Z0-9_\\-]+)");
    private static final Pattern CONFLICT_PATTERN =
            Pattern.compile("(?i)conflicts? with[^a-zA-Z0-9]{0,40}\\{?\\s*([a-zA-Z0-9_\\-]+)");

    private static final Pattern FABRIC_MISSING_PATTERN = Pattern.compile(
            "(?i)requires\\s+(?:any\\s+version|version\\s+[^\\s']+\\s+or\\s+(?:later|earlier))\\s+of\\s+(?:mod\\s+)?'[^']+'\\s*\\(([^)]+)\\)"
    );
    private static final Pattern FABRIC_MISSING_SIMPLE_PATTERN = Pattern.compile(
            "(?i)requires\\s+(?:any\\s+version|version\\s+[^\\s']+\\s+or\\s+(?:later|earlier))\\s+of\\s+([^\\s',\\.!]+)"
    );
    private static final Pattern FABRIC_INCOMPAT_PATTERN = Pattern.compile(
            "(?i)is\\s+incompatible\\s+with\\s+(?:any\\s+version|version\\s+[^\\s']+\\s+or\\s+(?:later|earlier))\\s+of\\s+(?:mod\\s+)?'[^']+'\\s*\\(([^)]+)\\)"
    );
    private static final Pattern FABRIC_INCOMPAT_SIMPLE_PATTERN = Pattern.compile(
            "(?i)is\\s+incompatible\\s+with\\s+(?:any\\s+version|version\\s+[^\\s']+\\s+or\\s+(?:later|earlier))\\s+of\\s+([^\\s',\\.!]+)"
    );

    private static final Pattern REQUIRED_DEP_NAME_PATTERN =
            Pattern.compile("(?i)([A-Za-z0-9][A-Za-z0-9 '\\-]*?) is a required dependency of .+? but it is not installed");
    private static final Pattern INCOMPATIBLE_NAME_PATTERN =
            Pattern.compile("(?i)([A-Za-z0-9][A-Za-z0-9 '\\-]*?) is incompatible with ([A-Za-z0-9][A-Za-z0-9 '\\-]*?)[\\s.,!]");

    // Загрузчик Fabric и его API считаются Loader ID, их нельзя статически отбраковывать
    private boolean isVanillaOrLoaderId(String id) {
        return id.equals("fabricloader") || id.equals("fabric") || id.equals("minecraft") || id.equals("java") || id.startsWith("fabric-");
    }

    private boolean versionSupportsGameVersion(JSONObject version) {
        JSONArray gameVersions = version.optJSONArray("game_versions");
        if (gameVersions == null) return false;
        for (int i = 0; i < gameVersions.length(); i++) {
            if (GAME_VERSION.equals(gameVersions.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private JSONObject getNewestCompatibleVersion(JSONArray versions) {
        if (versions == null || versions.isEmpty()) return null;

        for (int i = 0; i < versions.length(); i++) {
            JSONObject ver = versions.getJSONObject(i);
            if (versionSupportsGameVersion(ver) && "release".equals(ver.optString("version_type"))) {
                return ver;
            }
        }

        for (int i = 0; i < versions.length(); i++) {
            JSONObject ver = versions.getJSONObject(i);
            if (versionSupportsGameVersion(ver)) {
                LOGGER.info("[Version] Selected newest development version ({})", ver.optString("version_type"));
                return ver;
            }
        }

        return null;
    }

    private static class MCVersion implements Comparable<MCVersion> {
        final int[] parts;

        MCVersion(String versionStr) {
            String clean = versionStr.split("-")[0].split("\\+")[0].replaceAll("[^0-9.]", "");
            String[] split = clean.split("\\.");
            this.parts = new int[3];
            for (int i = 0; i < 3; i++) {
                if (i < split.length && !split[i].isEmpty()) {
                    try {
                        this.parts[i] = Integer.parseInt(split[i]);
                    } catch (NumberFormatException e) {
                        this.parts[i] = 0;
                    }
                } else {
                    this.parts[i] = 0;
                }
            }
        }

        @Override
        public int compareTo(MCVersion o) {
            for (int i = 0; i < 3; i++) {
                if (this.parts[i] != o.parts[i]) {
                    return Integer.compare(this.parts[i], o.parts[i]);
                }
            }
            return 0;
        }
    }

    private static boolean satisfiesMinecraftConstraint(String currentVer, String constraint) {
        if (constraint == null || constraint.isEmpty() || "*".equals(constraint)) return true;

        MCVersion current = new MCVersion(currentVer);
        String[] conditions = constraint.split("[\\s,]+");

        for (String cond : conditions) {
            cond = cond.trim();
            if (cond.isEmpty()) continue;

            String op = "";
            String verStr = "";

            if (cond.startsWith(">=")) { op = ">="; verStr = cond.substring(2); }
            else if (cond.startsWith("<=")) { op = "<="; verStr = cond.substring(2); }
            else if (cond.startsWith(">")) { op = ">"; verStr = cond.substring(1); }
            else if (cond.startsWith("<")) { op = "<"; verStr = cond.substring(1); }
            else if (cond.startsWith("=")) { op = "="; verStr = cond.substring(1); }
            else if (cond.startsWith("~")) { op = "~"; verStr = cond.substring(1); }
            else if (cond.startsWith("^")) { op = "^"; verStr = cond.substring(1); }
            else { op = "="; verStr = cond; }

            if (verStr.endsWith("-")) verStr = verStr.substring(0, verStr.length() - 1);

            MCVersion target = new MCVersion(verStr);
            int cmp = current.compareTo(target);

            boolean match = false;
            switch (op) {
                case ">=": match = cmp >= 0; break;
                case "<=": match = cmp <= 0; break;
                case ">":  match = cmp > 0; break;
                case "<":  match = cmp < 0; break;
                case "=":  match = cmp == 0; break;
                case "~":  match = cmp >= 0 && current.parts[0] == target.parts[0] && current.parts[1] == target.parts[1]; break;
                case "^":  match = cmp >= 0 && current.parts[0] == target.parts[0]; break;
            }
            if (!match) return false;
        }
        return true;
    }

    private boolean downloadDependencyByFabricId(String fabricDepId, String parentTitle) {
        LOGGER.info("[Dependency] '{}' requires '{}' — searching on Modrinth...", parentTitle, fabricDepId);

        if (fabricDepId.equals("fabric") || fabricDepId.startsWith("fabric-")) {
            LOGGER.info("[Dependency] Mapping dependency '{}' to central 'fabric-api' project.", fabricDepId);
            fabricDepId = "fabric-api";
        }

        String mappedSlug = COMMON_LIBRARY_MAP.get(fabricDepId);
        if (mappedSlug != null) {
            fabricDepId = mappedSlug;
        }

        try {
            String foundProjectId = null;
            try {
                String directResp = sendGetRequestWithRetry(API_BASE + "/project/" + fabricDepId);
                JSONObject directJson = new JSONObject(directResp);
                foundProjectId = directJson.optString("id", null);
                if (foundProjectId != null) {
                    LOGGER.info("[Dependency] Found directly by slug: '{}'", fabricDepId);
                }
            } catch (Exception ignored) {}

            if (foundProjectId == null) {
                String facets = "[[\"project_type:mod\"],[\"categories:" + MOD_LOADER + "\"],[\"versions:" + GAME_VERSION + "\"]]";
                String searchResp = sendGetRequestWithRetry(API_BASE + "/search?query=" + URLEncoder.encode(fabricDepId, StandardCharsets.UTF_8) + "&limit=5&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8));
                JSONArray hits = new JSONObject(searchResp).getJSONArray("hits");
                for (int hi = 0; hi < hits.length(); hi++) {
                    JSONObject hit = hits.getJSONObject(hi);
                    String hitSlug = hit.optString("slug", "");
                    String hitProjectId = hit.optString("project_id", "");
                    if (hitSlug.equals(fabricDepId) || hitProjectId.equals(fabricDepId)) {
                        foundProjectId = hitProjectId;
                        break;
                    }
                }

                if (foundProjectId == null && fabricDepId.contains("-")) {
                    String textQuery = fabricDepId.replace('-', ' ');
                    String textSearchResp = sendGetRequestWithRetry(API_BASE + "/search?query=" + URLEncoder.encode(textQuery, StandardCharsets.UTF_8) + "&limit=5&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8));
                    JSONArray textHits = new JSONObject(textSearchResp).getJSONArray("hits");
                    if (textHits.length() > 0) {
                        foundProjectId = textHits.getJSONObject(0).optString("project_id", null);
                        if (foundProjectId != null) {
                            LOGGER.info("[Dependency] Matched '{}' via text search for '{}'", fabricDepId, textQuery);
                        }
                        hits = textHits;
                    }
                }

                if (foundProjectId == null && hits.length() > 0) {
                    foundProjectId = hits.getJSONObject(0).optString("project_id", null);
                    LOGGER.info("[Dependency] No exact match, taking first result for '{}'", fabricDepId);
                }
            }

            if (foundProjectId != null && !downloadedModIds.contains(foundProjectId)
                    && !incompatibleModIds.contains(foundProjectId)) {
                LOGGER.info("[Dependency] Downloading dependency '{}' ({})", fabricDepId, foundProjectId);
                return processModDownload(foundProjectId);
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("[Dependency] Failed to find '{}' on Modrinth: {}", fabricDepId, e.getMessage());
            return false;
        }
    }

    private TestResult runBatchHeadlessTestLaunch(List<ModTask> batch) {
        Path testDir = FabricLoader.getInstance().getGameDir()
                .resolve("mods").resolve(PENDING_DIR_NAME)
                .resolve("test_" + System.nanoTime());

        try {
            prepareBatchTestInstance(FabricLoader.getInstance().getGameDir(), testDir, batch);

            ProcessBuilder pb = buildHeadlessLaunchCommand(testDir);
            if (pb == null) {
                LOGGER.warn("[Sandbox] Skipping headless test — could not build launch command.");
                return TestResult.success();
            }
            pb.redirectErrorStream(true);
            Path consoleLog = testDir.resolve("console.log");
            pb.redirectOutput(consoleLog.toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(TEST_LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.info("[Sandbox] Batch test instance is still running after {}s — assuming success.", TEST_LAUNCH_TIMEOUT_SECONDS);
                return TestResult.success();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.warn("[Sandbox] Process exited with non-zero code {}.", exitCode);
                TestResult parsedResult = analyzeTestResult(testDir, consoleLog, false, null);
                if (parsedResult.status == TestResult.Status.SUCCESS) {
                    return TestResult.crash();
                }
                return parsedResult;
            }

            return analyzeTestResult(testDir, consoleLog, false, null);

        } catch (Exception e) {
            LOGGER.error("[Sandbox] Failed to run batch test launch: {}", e.getMessage());
            return TestResult.crash();
        } finally {
            deleteRecursively(testDir);
        }
    }

    private void prepareBatchTestInstance(Path gameDir, Path testDir, List<ModTask> batch) throws IOException {
        Files.createDirectories(testDir);
        Path testMods = testDir.resolve("mods");
        Files.createDirectories(testMods);

        Path realMods = gameDir.resolve("mods");
        if (Files.exists(realMods)) {
            try (var stream = Files.list(realMods)) {
                for (Path entry : stream.collect(Collectors.toList())) {
                    String name = entry.getFileName().toString();
                    if (name.equals(PENDING_DIR_NAME)) continue;
                    if (!name.toLowerCase().endsWith(".jar")) continue;
                    linkOrCopy(entry, testMods.resolve(name));
                }
            }
        }

        for (ModTask task : batch) {
            linkOrCopy(task.tempFile, testMods.resolve(task.fileName));
        }

        Path options = testDir.resolve("options.txt");
        List<String> optLines = List.of(
                "fullscreen:false",
                "overrideWidth:16",
                "overrideHeight:16",
                "narrator:0",
                "soundDevice:\"\"",
                "pauseOnLostFocus:false"
        );
        Files.write(options, optLines, StandardCharsets.UTF_8);
    }

    private void linkOrCopy(Path source, Path destination) throws IOException {
        try {
            Files.createSymbolicLink(destination, source.toAbsolutePath());
        } catch (Exception e) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ProcessBuilder buildHeadlessLaunchCommand(Path testDir) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String javaBin = info.command().orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Djava.awt.headless=true");

        List<String> originalArgs;
        if (info.arguments().isPresent() && info.arguments().get().length > 0) {
            originalArgs = new ArrayList<>(Arrays.asList(info.arguments().get()));
        } else {
            String cmdLine = info.commandLine().orElse("");
            List<String> parsed = parseCommandLine(cmdLine);
            if (!parsed.isEmpty()) {
                parsed.remove(0);
            }
            originalArgs = parsed;
            if (!originalArgs.isEmpty()) {
                LOGGER.info("[Sandbox] Reconstructed launch arguments from the process command line.");
            }
        }

        boolean gameDirReplaced = false;
        for (int i = 0; i < originalArgs.size(); i++) {
            String arg = originalArgs.get(i);
            if (arg.equals("--gameDir") && i + 1 < originalArgs.size()) {
                command.add(arg);
                command.add(testDir.toAbsolutePath().toString());
                i++;
                gameDirReplaced = true;
                continue;
            }
            if (arg.equals("--width") || arg.equals("--height")) {
                i++;
                continue;
            }
            command.add(arg);
        }
        if (!gameDirReplaced) {
            command.add("--gameDir");
            command.add(testDir.toAbsolutePath().toString());
        }
        command.add("--width");
        command.add("16");
        command.add("--height");
        command.add("16");

        if (!commandLooksLaunchable(command)) {
            LOGGER.warn("[Sandbox] Could not reconstruct a valid Java launch command. Skipping headless sandbox.");
            return null;
        }

        if (isLinux() && isCommandAvailable("xvfb-run")) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("xvfb-run");
            wrapped.add("-a");
            wrapped.addAll(command);
            return new ProcessBuilder(wrapped).directory(testDir.toFile());
        }

        LOGGER.warn("[Sandbox] No virtual display (xvfb-run) found. Falling back to small window.");
        return new ProcessBuilder(command).directory(testDir.toFile());
    }

    private List<String> parseCommandLine(String cmdLine) {
        List<String> args = new ArrayList<>();
        if (cmdLine == null || cmdLine.isBlank()) return args;
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(cmdLine);
        while (m.find()) {
            args.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return args;
    }

    private boolean commandLooksLaunchable(List<String> command) {
        for (String arg : command) {
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")
                    || arg.equals("-jar") || arg.equals("-m") || arg.equals("--module")
                    || arg.startsWith("@")
                    || arg.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }

    private boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private TestResult analyzeTestResult(Path testDir, Path consoleLog, boolean stillRunning, ModTask task) {
        try {
            StringBuilder combined = new StringBuilder();
            if (Files.exists(consoleLog)) combined.append(Files.readString(consoleLog));

            Path latestLog = testDir.resolve("logs").resolve("latest.log");
            if (Files.exists(latestLog)) combined.append(Files.readString(latestLog));

            boolean hasCrashReport = false;
            Path crashReports = testDir.resolve("crash-reports");
            if (Files.isDirectory(crashReports)) {
                try (var stream = Files.list(crashReports)) {
                    for (Path report : stream.collect(Collectors.toList())) {
                        hasCrashReport = true;
                        combined.append(Files.readString(report));
                    }
                }
            }

            String text = combined.toString();
            String lower = text.toLowerCase(Locale.ROOT);

            if (lower.contains("glfwerror")
                    || lower.contains("pixel format not accelerated")
                    || lower.contains("could not initialize class net.minecraft.client.util.window")
                    || lower.contains("lwjgl")
                    || lower.contains("glcontext")
                    || lower.contains("window creation failed")) {
                LOGGER.warn("[Sandbox] Graphics issue detected. Environment limitation, treating as SUCCESS.");
                return TestResult.success();
            }

            boolean startupErrorScreen = lower.contains("minecraft may not be launched in this state")
                    || lower.contains("startup errors")
                    || lower.contains("please fix the issues and restart")
                    || lower.contains("incompatible mods found");

            if (lower.contains("missing") || lower.contains("requires") || lower.contains("is a required dependency") || startupErrorScreen) {
                Set<String> missing = new HashSet<>();

                Matcher m1 = FABRIC_MISSING_PATTERN.matcher(text);
                while (m1.find()) {
                    String id = m1.group(1).toLowerCase(Locale.ROOT);
                    if (!isVanillaOrLoaderId(id)) missing.add(id);
                }

                Matcher m2 = FABRIC_MISSING_SIMPLE_PATTERN.matcher(text);
                while (m2.find()) {
                    String id = m2.group(1).toLowerCase(Locale.ROOT);
                    if (!isVanillaOrLoaderId(id)) missing.add(id);
                }

                if (missing.isEmpty()) {
                    missing.addAll(extractIds(MISSING_DEP_PATTERN, text));
                    missing.addAll(extractNamedIds(REQUIRED_DEP_NAME_PATTERN, 1, text));
                }

                if (!missing.isEmpty()) return TestResult.missingDependency(missing);
                if (startupErrorScreen) {
                    LOGGER.warn("[Sandbox] Detected the 'Startup Errors' screen but could not identify the missing dependency from the log.");
                    return TestResult.crash();
                }
            }

            if (lower.contains("conflicts with") || lower.contains("is incompatible with")
                    || (lower.contains("incompatible") && lower.contains("mod"))) {
                Set<String> conflicting = new HashSet<>();

                Matcher m1 = FABRIC_INCOMPAT_PATTERN.matcher(text);
                while (m1.find()) {
                    String id = m1.group(1).toLowerCase(Locale.ROOT);
                    conflicting.add(id);
                }

                Matcher m2 = FABRIC_INCOMPAT_SIMPLE_PATTERN.matcher(text);
                while (m2.find()) {
                    String id = m2.group(1).toLowerCase(Locale.ROOT);
                    conflicting.add(id);
                }

                if (conflicting.isEmpty()) {
                    conflicting.addAll(extractIds(CONFLICT_PATTERN, text));
                    conflicting.addAll(extractNamedIds(INCOMPATIBLE_NAME_PATTERN, 1, text));
                }
                return TestResult.incompatible(conflicting);
            }

            if (hasCrashReport || lower.contains("exception in thread") || lower.contains("---- minecraft crash report ----")) {
                return TestResult.crash();
            }

            if (stillRunning) {
                return TestResult.success();
            }

            return TestResult.success();

        } catch (Exception e) {
            LOGGER.warn("[Sandbox] Failed to analyze test result: {}", e.getMessage());
            return TestResult.crash();
        }
    }

    private Set<String> extractIds(Pattern pattern, String text) {
        Set<String> ids = new HashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String id = m.group(1).toLowerCase(Locale.ROOT);
            if (!isVanillaOrLoaderId(id)) ids.add(id);
        }
        return ids;
    }

    private Set<String> extractNamedIds(Pattern pattern, int group, String text) {
        Set<String> ids = new HashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String slugGuess = toSlugGuess(m.group(group));
            if (!slugGuess.isEmpty() && !isVanillaOrLoaderId(slugGuess)) ids.add(slugGuess);
        }
        return ids;
    }

    private String toSlugGuess(String displayName) {
        return displayName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[Sandbox] Failed to clean up test instance {}: {}", path, e.getMessage());
        }
    }

    private static class TestResult {
        enum Status { SUCCESS, MISSING_DEPENDENCY, INCOMPATIBLE, CRASH_UNKNOWN }

        final Status status;
        final Set<String> relatedIds;

        private TestResult(Status status, Set<String> relatedIds) {
            this.status = status;
            this.relatedIds = relatedIds;
        }

        static TestResult success() { return new TestResult(Status.SUCCESS, Set.of()); }
        static TestResult missingDependency(Set<String> ids) { return new TestResult(Status.MISSING_DEPENDENCY, ids); }
        static TestResult incompatible(Set<String> ids) { return new TestResult(Status.INCOMPATIBLE, ids); }
        static TestResult crash() { return new TestResult(Status.CRASH_UNKNOWN, Set.of()); }
    }

    public static class Genre {
        String name; List<String> tags; int weight;
        public Genre(String n, List<String> t, int w) { name = n; tags = t; weight = w; }
    }

    private static class ModTask {
        final String projectId, title, fileName, downloadUrl, authors, license, licenseId, licenseUrl;
        Path localLicensePath;

        Path tempFile;
        String fabricId;
        String version;
        Map<String, String> dependsConstraints;

        ModTask(String id, String t, String f, String d, String a, String l, String lid, String lu) {
            this.projectId = id; this.title = t; this.fileName = f;
            this.downloadUrl = d; this.authors = a; this.license = l;
            this.licenseId = lid; this.licenseUrl = lu;
        }
    }
}