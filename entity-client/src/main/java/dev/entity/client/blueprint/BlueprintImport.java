package dev.entity.client.blueprint;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.ISchematicSystem;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.function.Supplier;

/** Finite public downloads and a validated native parser; never executes downloaded content. */
final class BlueprintImport {
    static final int MAX_COMPRESSED = 8 * 1024 * 1024;
    static final int MAX_DECODED = 32 * 1024 * 1024;
    static final int MAX_PAGE = 1024 * 1024;
    private static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String CACHE_MAGIC = "entity2-blueprint-cache-v1";
    private static final Pattern CACHE_ID = Pattern.compile("import-[0-9a-f]{64}");
    private static final Pattern HREF = Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final String FORMATS = "Use Sponge .schem v1/v2 or Litematica .litematic v7";
    private static final String PUBLIC_FILE = "Use a public direct .schem/.litematic download, or /e build list for built-in designs";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private BlueprintImport() { }

    static List<String> cachedIds(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".blueprint"))
                    .map(name -> name.substring(0, name.length() - ".blueprint".length()))
                    .filter(name -> CACHE_ID.matcher(name).matches()).sorted().toList();
        } catch (IOException unavailable) {
            return List.of();
        }
    }

    /** Cheap catalog metadata only; selecting an alias still validates every cell/hash in load. */
    static String cachedName(Path root, String id) throws IOException {
        if (!CACHE_ID.matcher(id).matches()) throw new IOException("Invalid blueprint cache ID");
        Path path = root.resolve(id + ".blueprint");
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_DECODED)
            throw new IOException("Blueprint cache is missing or oversized");
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            if (!CACHE_MAGIC.equals(input.readUTF()) || !id.equals(input.readUTF()))
                throw new IOException("Invalid blueprint cache identity");
            return input.readUTF();
        }
    }

    static BlueprintDesign importSource(Path root, String source) throws IOException {
        return importSource(root, source, () -> BaritoneAPI.getProvider().getSchematicSystem());
    }

    static BlueprintDesign importSource(Path root, String source, Supplier<ISchematicSystem> formats) throws IOException {
        ImportTrace trace = new ImportTrace();
        try {
            URI uri = sourceUri(source);
            trace.source = sourceLabel(uri);
            Download download = resolveDownload(download(uri, 0, trace), 0, trace);
            trace.stage = "parse";
            BlueprintDesign parsed = parse(download.bytes(), null, safeSource(source, uri),
                    friendlyName(download.fileName().isBlank() ? download.uri().getPath() : download.fileName()), formats, trace);
            trace.stage = "cache";
            BlueprintDesign saved = save(root, parsed);
            trace.stage = "complete";
            trace.success = true;
            return saved;
        } finally {
            // Standalone imports have no mission journal. Persist facts without URLs,
            // redirect tokens, downloaded HTML or exception text containing user input.
            try { trace.record(root); }
            catch (IOException unavailable) {
                org.slf4j.LoggerFactory.getLogger(BlueprintImport.class)
                        .warn("Blueprint import outcome could not be persisted (storage unavailable)");
            }
        }
    }

    static final class ImportTrace {
        String source = "unresolved source", stage = "source-validation", format = "not-parsed";
        int httpStatus, downloadedBytes;
        boolean success;

        void record(Path root) throws IOException {
            var event = new com.google.gson.JsonObject();
            event.addProperty("timestamp", System.currentTimeMillis());
            event.addProperty("source", source);
            event.addProperty("stage", stage);
            event.addProperty("httpStatus", httpStatus);
            event.addProperty("downloadedBytes", downloadedBytes);
            event.addProperty("format", format);
            event.addProperty("success", success);
            Files.createDirectories(root);
            Files.writeString(root.resolve("import-outcomes.ndjson"), event + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** Keep the client source contract identical to Paper's accepted bare/prefixed IDs. */
    static URI sourceUri(String source) throws IOException {
        if (source == null || source.isBlank() || source.length() > 2048 || source.matches(".*\\s.*"))
            throw new IOException("Use a public HTTPS schematic URL or abfielder:<ID>");
        try {
            return source.matches("(?:abfielder:)?[0-9]{1,12}")
                    ? URI.create("https://abfielder.com/Products/ProductDetails.php?id="
                            + source.substring(source.indexOf(':') + 1))
                    : URI.create(source);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Use a public HTTPS schematic URL or abfielder:<ID>", invalid);
        }
    }

    static String safeSource(String source, URI uri) {
        if (source.matches("(?:abfielder:)?[0-9]{1,12}"))
            return "abfielder:" + source.substring(source.indexOf(':') + 1);
        // Attribution must never persist an expiring download token or browser challenge query.
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getRawPath();
    }

    static BlueprintDesign parse(byte[] compressed, String extension, String source, String name) throws IOException {
        return parse(compressed, extension, source, name, () -> BaritoneAPI.getProvider().getSchematicSystem());
    }

    static BlueprintDesign parse(byte[] compressed, String extension, String source, String name,
            Supplier<ISchematicSystem> formats) throws IOException {
        return parse(compressed, extension, source, name, formats, null);
    }

    private static BlueprintDesign parse(byte[] compressed, String extension, String source, String name,
            Supplier<ISchematicSystem> formats, ImportTrace trace) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_COMPRESSED) throw new IOException("Blueprint download exceeds 8 MiB");
        if (!isGzip(compressed)) throw new IOException("The response is not a compressed schematic file. " + FORMATS
                + "; HTML pages, ZIP archives and legacy .schematic files are not supported");
        try {
            byte[] decoded;
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                decoded = readLimited(gzip, MAX_DECODED);
            }
            NbtCompound nbt = NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(decoded)),
                    new NbtSizeTracker(MAX_DECODED, 128));
            // Download endpoints need not end with a filename. Bounded NBT content, not a
            // URL suffix or an untrusted Content-Disposition header, selects the native parser.
            extension = formatOf(nbt);
            if (trace != null) trace.format = extension + "-v" + nbt.getInt("Version", -1);
            validateNbt(nbt, extension);
            var format = formats.get()
                    .getByFile(new java.io.File("blueprint." + extension))
                    .orElseThrow(() -> new IOException("Native schematic parser is unavailable"));
            IStaticSchematic nativeSchematic = format.parse(new ByteArrayInputStream(compressed));
            BlueprintDesign.validateDimensions(nativeSchematic.widthX(), nativeSchematic.heightY(), nativeSchematic.lengthZ());
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            for (int y = 0; y < nativeSchematic.heightY(); y++) {
                for (int z = 0; z < nativeSchematic.lengthZ(); z++) {
                    for (int x = 0; x < nativeSchematic.widthX(); x++) {
                        if (nativeSchematic.inSchematic(x, y, z, Blocks.AIR.getDefaultState())) {
                            BlockState state = nativeSchematic.getDirect(x, y, z);
                            validateBuildState(state);
                            cells.put(new BlockPos(x, y, z), state);
                        }
                    }
                }
            }
            BlueprintDesign draft = new BlueprintDesign("pending", name, source,
                    nativeSchematic.widthX(), nativeSchematic.heightY(), nativeSchematic.lengthZ(), cells);
            draft.materials();
            return new BlueprintDesign("import-" + draft.sha256(), name, source,
                    draft.width(), draft.height(), draft.length(), draft.cells());
        } catch (RuntimeException invalid) {
            throw new IOException("Unsupported or invalid blueprint: " + invalid.getMessage(), invalid);
        }
    }

    static String formatOf(NbtCompound root) throws IOException {
        if (root.getCompound("Regions").isPresent()) return "litematic";
        if (root.getCompound("Palette").isPresent() && root.contains("BlockData")) return "schem";
        if (root.getCompound("Schematic").isPresent())
            throw new IOException("Sponge .schem v3 is not supported by the bundled native parser. " + FORMATS);
        if (root.contains("Blocks") && root.contains("Data"))
            throw new IOException("Legacy .schematic is not supported. " + FORMATS);
        throw new IOException("The download is not a recognized schematic. " + FORMATS);
    }

    static void validateNbt(NbtCompound root, String extension) throws IOException {
        rejectContents(root);
        int version = root.getInt("Version", -1);
        if (extension.equals("schem")) {
            if (version != 1 && version != 2) throw new IOException("Sponge .schem versions 1 and 2 are supported; found " + version + ". " + FORMATS);
            BlueprintDesign.validateDimensions(root.getInt("Width", 0), root.getInt("Height", 0), root.getInt("Length", 0));
            NbtCompound palette = root.getCompound("Palette").orElseThrow(() -> new IOException("Missing schematic palette"));
            if (palette.isEmpty() || palette.getSize() > BlueprintDesign.MAX_CELLS) throw new IOException("Invalid schematic palette size");
            for (String entry : palette.getKeys()) validateBuildState(BlueprintDesign.parseState(entry));
        } else if (extension.equals("litematic")) {
            if (version != 7) throw new IOException("Litematica version 7 (Minecraft 1.21+) is supported; found " + version + ". " + FORMATS);
            NbtCompound regions = root.getCompound("Regions").orElseThrow(() -> new IOException("Missing litematic regions"));
            if (regions.isEmpty() || regions.getSize() > 128) throw new IOException("Invalid litematic region count");
            long total = 0;
            long[] min = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
            long[] max = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
            for (NbtElement element : regions.values()) {
                if (!(element instanceof NbtCompound region)) throw new IOException("Invalid litematic region");
                rejectContents(region);
                NbtCompound size = region.getCompound("Size").orElseThrow(() -> new IOException("Missing region size"));
                NbtCompound position = region.getCompound("Position").orElseThrow(() -> new IOException("Missing region position"));
                String[] axes = {"x", "y", "z"};
                int[] dimensions = new int[3];
                for (int i = 0; i < 3; i++) {
                    long signed = size.getInt(axes[i], 0);
                    dimensions[i] = (int) Math.min(Integer.MAX_VALUE, Math.abs(signed));
                    long origin = position.getInt(axes[i], 0);
                    long low = signed < 0 ? origin + signed + 1 : origin;
                    min[i] = Math.min(min[i], low);
                    max[i] = Math.max(max[i], low + dimensions[i]);
                }
                BlueprintDesign.validateDimensions(dimensions[0], dimensions[1], dimensions[2]);
                total += (long) dimensions[0] * dimensions[1] * dimensions[2];
                if (total > BlueprintDesign.MAX_CELLS) throw new IOException("Too many litematic region cells");
                NbtList palette = region.getList("BlockStatePalette").orElseThrow(() -> new IOException("Missing region palette"));
                if (palette.isEmpty() || palette.size() > BlueprintDesign.MAX_CELLS) throw new IOException("Invalid region palette size");
                for (NbtElement entry : palette) {
                    if (!(entry instanceof NbtCompound block)) throw new IOException("Invalid region palette entry");
                    String state = block.getString("Name", "");
                    NbtCompound properties = block.getCompoundOrEmpty("Properties");
                    if (!properties.isEmpty()) {
                        TreeMap<String, String> values = new TreeMap<>();
                        for (String key : properties.getKeys()) values.put(key, properties.getString(key, ""));
                        state += "[" + String.join(",", values.entrySet().stream()
                                .map(value -> value.getKey() + "=" + value.getValue()).toList()) + "]";
                    }
                    validateBuildState(BlueprintDesign.parseState(state));
                }
            }
            for (int i = 0; i < 3; i++) if (max[i] - min[i] > BlueprintDesign.MAX_SIDE) throw new IOException("Litematic regions span excessive bounds");
            BlueprintDesign.validateDimensions((int) (max[0] - min[0]), (int) (max[1] - min[1]), (int) (max[2] - min[2]));
        } else throw new IOException("Unsupported schematic extension");
    }

    private static void rejectContents(NbtCompound compound) throws IOException {
        for (String key : List.of("Entities", "TileEntities", "BlockEntities", "PendingBlockTicks", "PendingFluidTicks")) {
            NbtElement element = compound.get(key);
            if (element != null && (!(element instanceof NbtList list) || !list.isEmpty())) {
                throw new IOException("Blueprint contains unsupported entity, block-entity contents or pending ticks (" + key + ")");
            }
        }
    }

    static void validateBuildState(BlockState state) {
        if (state == null) throw new IllegalArgumentException("Null schematic block");
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        if (state.isAir()) return;
        if (!id.startsWith("minecraft:") || !state.getFluidState().isEmpty()
                || id.matches(".*(redstone|piston|command_block|structure_block|jigsaw|barrier|spawner|portal|tnt|tripwire|repeater|comparator|observer|sculk_sensor|sculk_shrieker|daylight_detector|target|lever|button|pressure_plate|rail|dispenser|dropper|hopper|crafter).*")) {
            throw new IllegalArgumentException("Unsupported fluid, machinery or special block: " + id);
        }
        if (state.getBlock().asItem() == Items.AIR && !state.isOf(Blocks.WALL_TORCH) && !state.isOf(Blocks.SOUL_WALL_TORCH)) {
            throw new IllegalArgumentException("No ordinary placement item for " + id);
        }
    }

    static BlueprintDesign save(Path root, BlueprintDesign design) throws IOException {
        Files.createDirectories(root);
        Path path = root.resolve(design.id() + ".blueprint");
        if (Files.exists(path)) return load(root, design.id());
        Path temporary = Files.createTempFile(root, ".blueprint-", ".tmp");
        try {
            try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeUTF(CACHE_MAGIC); output.writeUTF(design.id()); output.writeUTF(design.name());
                output.writeUTF(design.source()); output.writeUTF(design.sha256());
                output.writeInt(design.width()); output.writeInt(design.height()); output.writeInt(design.length());
                output.writeInt(design.cells().size());
                for (var entry : design.cells().entrySet()) {
                    output.writeInt(entry.getKey().getX()); output.writeInt(entry.getKey().getY()); output.writeInt(entry.getKey().getZ());
                    output.writeUTF(BlueprintDesign.stateString(entry.getValue()));
                }
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path); }
        } finally { Files.deleteIfExists(temporary); }
        return load(root, design.id());
    }

    static BlueprintDesign load(Path root, String id) throws IOException {
        if (!CACHE_ID.matcher(id).matches()) throw new IOException("Unknown blueprint ID: " + id);
        Path path = root.resolve(id + ".blueprint");
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_DECODED) throw new IOException("Blueprint cache is missing or oversized: " + id);
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            if (!CACHE_MAGIC.equals(input.readUTF()) || !id.equals(input.readUTF())) throw new IOException("Invalid blueprint cache identity");
            String name = input.readUTF(), source = input.readUTF(), expectedHash = input.readUTF();
            int width = input.readInt(), height = input.readInt(), length = input.readInt();
            BlueprintDesign.validateDimensions(width, height, length);
            int count = input.readInt();
            if (count < 1 || count > BlueprintDesign.MAX_CELLS) throw new IOException("Invalid cached cell count");
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                BlockPos position = new BlockPos(input.readInt(), input.readInt(), input.readInt());
                BlockState state = BlueprintDesign.parseState(input.readUTF());
                validateBuildState(state);
                if (cells.put(position, state) != null) throw new IOException("Duplicate cached blueprint cell");
            }
            if (input.read() != -1) throw new IOException("Trailing blueprint cache data");
            BlueprintDesign design = new BlueprintDesign(id, name, source, width, height, length, cells);
            if (!design.sha256().equals(expectedHash) || !id.equals("import-" + design.sha256())) throw new IOException("Blueprint cache hash mismatch");
            return design;
        } catch (RuntimeException invalid) { throw new IOException("Invalid blueprint cache", invalid); }
    }

    private static Download resolveDownload(Download page, int depth, ImportTrace trace) throws IOException {
        if (isGzip(page.bytes())) return page;
        trace.stage = "resolve-page";
        if (depth > 2 || page.bytes().length > MAX_PAGE)
            throw new IOException("The website did not provide a supported public schematic file. " + PUBLIC_FILE);
        String html = new String(page.bytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (html.contains("cf-chl-") || html.contains("<title>just a moment")
                || html.contains("<title>attention required") || html.contains("challenges.cloudflare.com"))
            throw new IOException(downloadFailure(page.uri(), 403));
        List<URI> candidates = pageCandidates(page.uri(), page.bytes());
        if (candidates.isEmpty()) throw new IOException("This link returned a web page or unsupported file, not a schematic. " + PUBLIC_FILE);
        return resolveDownload(download(candidates.get(0), 0, trace), depth + 1, trace);
    }

    /** Only follow public download anchors on the two explicitly supported catalog sites. */
    static List<URI> pageCandidates(URI page, byte[] bytes) throws IOException {
        if (bytes.length > MAX_PAGE) throw new IOException("Blueprint download page exceeds 1 MiB");
        Matcher links = HREF.matcher(new String(bytes, StandardCharsets.UTF_8));
        List<URI> candidates = new ArrayList<>();
        String schematicId = minecraftSchematicsId(page);
        while (links.find()) {
            String href = links.group(1).replace("&amp;", "&");
            URI linked;
            try { linked = page.resolve(href); } catch (IllegalArgumentException invalid) { continue; }
            if (!"https".equalsIgnoreCase(linked.getScheme()) || linked.getUserInfo() != null
                    || linked.getFragment() != null || linked.equals(page)) continue;
            String path = linked.getPath();
            if (isAbfielder(page) && isAbfielder(linked)) {
                if (extension(path) != null) candidates.add(0, linked);
                else if (path.endsWith("/ProductDownloadThankYou.php") || path.endsWith("/serveProductDownload.php"))
                    candidates.add(linked);
            } else if (schematicId != null && isMinecraftSchematics(linked)) {
                if (extension(path) != null) candidates.add(0, linked);
                else if (path.equals("/schematic/" + schematicId + "/download/")
                        || path.equals("/schematic/" + schematicId + "/download/action/")) candidates.add(linked);
            }
        }
        return List.copyOf(candidates);
    }

    static String downloadFailure(URI uri, int status) {
        String site = sourceLabel(uri);
        if (status == 401 || status == 403 || status == 503)
            return site + " denied automated access or requires browser/login verification (HTTP " + status
                    + "). Browser verification links cannot be imported. " + PUBLIC_FILE;
        return "Blueprint download returned HTTP " + status + ". Check the public download link, or use /e build list";
    }

    private static String sourceLabel(URI uri) {
        return uri != null && isAbfielder(uri) ? "Abfielder"
                : uri != null && isMinecraftSchematics(uri) ? "Minecraft-Schematics" : "the public source";
    }

    private static Download download(URI uri, int redirects, ImportTrace trace) throws IOException {
        trace.stage = "source-validation";
        validatePublicUri(uri);
        trace.source = sourceLabel(uri);
        trace.stage = "request";
        trace.httpStatus = 0;
        trace.downloadedBytes = 0;
        if (redirects > 4) throw new IOException("Too many blueprint download redirects");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Entity2-BlueprintImporter/1.0")
                .header("Accept", "application/octet-stream, application/gzip, text/html;q=0.5")
                .GET().build();
        try {
            HttpResponse<InputStream> response;
            try { response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
            catch (HttpTimeoutException timedOut) {
                throw new IOException("Blueprint request to " + sourceLabel(uri)
                        + " timed out before response headers. Schematic parsing did not start. " + PUBLIC_FILE, timedOut);
            }
            try (InputStream input = response.body()) {
                int status = response.statusCode();
                trace.stage = "response";
                trace.httpStatus = status;
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("Download redirect has no location"));
                    return download(uri.resolve(location), redirects + 1, trace);
                }
                if (status != 200) throw new IOException(downloadFailure(uri, status));
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (contentLength > MAX_COMPRESSED) throw new IOException("Blueprint download exceeds 8 MiB");
                String filename = fileName(response.headers().firstValue("Content-Disposition").orElse(""));
                trace.stage = "download-body";
                byte[] bytes = readBodyLimited(input, MAX_COMPRESSED, BODY_READ_TIMEOUT, uri, contentLength);
                trace.downloadedBytes = bytes.length;
                return new Download(uri, filename, bytes);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Blueprint import interrupted", interrupted);
        }
    }

    static void validatePublicUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("Blueprint downloads require public HTTPS without credentials or custom ports");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                    || (bytes.length == 4 && ((bytes[0] & 255) == 0
                    || ((bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127)))) {
                throw new IOException("Private/local network URLs are not schematic sources");
            }
        }
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        return readLimited(input, limit, null);
    }

    private static byte[] readLimited(InputStream input, int limit, AtomicInteger received) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (bytes.size() > limit - read) throw new IOException("Blueprint input exceeds its size limit");
            bytes.write(buffer, 0, read);
            if (received != null) received.set(bytes.size());
        }
        return bytes.toByteArray();
    }

    /**
     * ofInputStream completes at the headers, so HttpRequest.timeout does not
     * bound read(). One deadline covers the entire body, including slow trickles.
     * Closing the HttpClient stream cancels its subscription and releases a
     * blocked reader; the dedicated daemon cannot hold the client open on exit.
     */
    static byte[] readBodyLimited(InputStream input, int limit, Duration timeout) throws IOException {
        return readBodyLimited(input, limit, timeout, null, -1);
    }

    static byte[] readBodyLimited(InputStream input, int limit, Duration timeout,
                                 URI source, long expectedLength) throws IOException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Body timeout must be positive");
        AtomicInteger received = new AtomicInteger();
        FutureTask<byte[]> read = new FutureTask<>(() -> readLimited(input, limit, received));
        Thread reader = new Thread(read, "Entity2-blueprint-download-body");
        reader.setDaemon(true);
        reader.start();
        try (input) {
            try {
                byte[] bytes = read.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                if (expectedLength >= 0 && bytes.length != expectedLength)
                    throw new IOException("Blueprint download body ended incomplete from " + sourceLabel(source)
                            + " (received " + bytes.length + " of " + expectedLength
                            + " bytes). Schematic parsing did not start. " + PUBLIC_FILE);
                return bytes;
            } catch (TimeoutException timedOut) {
                throw new IOException("Blueprint download body timed out after " + timeout.toMillis()
                        + " ms from " + sourceLabel(source) + " (received " + received.get()
                        + (expectedLength >= 0 ? " of " + expectedLength : "")
                        + " bytes). Schematic parsing did not start. " + PUBLIC_FILE, timedOut);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Blueprint download body interrupted", interrupted);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof IOException io) throw io;
                if (failed.getCause() instanceof RuntimeException invalid) throw invalid;
                if (failed.getCause() instanceof Error fatal) throw fatal;
                throw new IOException("Blueprint download body could not be read", failed.getCause());
            } finally {
                read.cancel(true);
            }
        }
    }

    private static String extension(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".litematic") ? "litematic" : lower.endsWith(".schem") ? "schem" : null;
    }
    private static boolean isAbfielder(URI uri) {
        return "abfielder.com".equalsIgnoreCase(uri.getHost()) || "www.abfielder.com".equalsIgnoreCase(uri.getHost());
    }
    private static boolean isMinecraftSchematics(URI uri) {
        return "minecraft-schematics.com".equalsIgnoreCase(uri.getHost())
                || "www.minecraft-schematics.com".equalsIgnoreCase(uri.getHost());
    }
    private static String minecraftSchematicsId(URI uri) {
        if (!isMinecraftSchematics(uri)) return null;
        Matcher path = Pattern.compile("^/schematic/([0-9]{1,12})(?:/.*)?$").matcher(uri.getPath());
        return path.matches() ? path.group(1) : null;
    }
    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 255) == 0x1f && (bytes[1] & 255) == 0x8b;
    }
    static String fileName(String disposition) {
        Matcher encoded = Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8'[^']*'([^;]+)").matcher(disposition);
        if (encoded.find()) {
            try { return URLDecoder.decode(encoded.group(1).trim().replace("+", "%2B"), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalid) { /* use the ordinary filename, if present */ }
        }
        Matcher name = Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*\"?([^\";]+)").matcher(disposition);
        return name.find() ? name.group(1).trim() : "";
    }
    private static String friendlyName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return name.isBlank() ? "Imported blueprint" : name.substring(0, Math.min(120, name.length()));
    }
    private record Download(URI uri, String fileName, byte[] bytes) { }
}
