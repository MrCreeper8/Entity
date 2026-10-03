package dev.entitybridge.command;

import dev.entity.core.stewardship.ProtectedAreaPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

final class CommandInput {
    private CommandInput() {
    }

    static String canonicalAction(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "kill" -> "attack";
            case "go" -> "goto";
            case "collect", "fetch" -> "get";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    static String identifier(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        normalized = normalized
                .replace('-', '_')
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_");
        return normalized.matches("[a-z0-9_]+") ? normalized : null;
    }

    /** Parses either the established coordinate form or one finite player destination. */
    static GoToRequest goTo(String[] args) {
        if (args.length == 2) {
            String player = args[1].trim();
            if (!player.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("player");
            }
            return GoToRequest.player(player);
        }
        if (args.length != 4) {
            throw new IllegalArgumentException("goto");
        }
        Double x = finiteDouble(args[1]);
        Double y = finiteDouble(args[2]);
        Double z = finiteDouble(args[3]);
        if (x == null || y == null || z == null) {
            throw new IllegalArgumentException("coordinates");
        }
        return GoToRequest.coordinates(x, y, z);
    }

    static ResourceQuery resource(String[] args, int firstResourceArgument, int maxAmount) {
        if (args.length <= firstResourceArgument) {
            throw new IllegalArgumentException("missing resource");
        }
        int resourceEnd = args.length;
        int amount = 1;
        String last = args[args.length - 1];
        if (last.matches("[+-]?\\d+")) {
            try {
                amount = Integer.parseInt(last);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("amount");
            }
            if (amount < 1 || amount > maxAmount) {
                throw new IllegalArgumentException("amount");
            }
            resourceEnd--;
        }
        if (resourceEnd <= firstResourceArgument) {
            throw new IllegalArgumentException("missing resource");
        }
        StringBuilder query = new StringBuilder();
        for (int index = firstResourceArgument; index < resourceEnd; index++) {
            if (!query.isEmpty()) {
                query.append('_');
            }
            query.append(args[index]);
        }
        String identifier = identifier(query.toString());
        if (identifier == null) {
            throw new IllegalArgumentException("resource");
        }
        return new ResourceQuery(identifier, amount);
    }

    /**
     * Parses the intentionally compact delivery grammar:
     * {@code <item> [count]} or {@code <player> <item> [count]}.
     *
     * <p>The entire tail is tried as an item first. This keeps multi-word item
     * names such as {@code diamond pickaxe} usable without quoting. If that is
     * not an item, the first word is interpreted as the optional player. Both
     * normalizers return {@code null} when their input is invalid.</p>
     */
    static DeliveryQuery delivery(
            String[] args,
            int firstArgument,
            int maxAmount,
            String defaultPlayer,
            Function<String, String> itemNormalizer,
            Function<String, String> playerNormalizer
    ) {
        if (args.length <= firstArgument) {
            throw new IllegalArgumentException("missing item");
        }
        String[] withoutOverride = withoutReserveOverride(args, firstArgument);
        if (withoutOverride != args) {
            DeliveryQuery parsed = delivery(withoutOverride, firstArgument, maxAmount,
                    defaultPlayer, itemNormalizer, playerNormalizer);
            return new DeliveryQuery(parsed.item(), parsed.count(), parsed.player(), parsed.all(), true);
        }

        int allIndex = -1;
        for (int index = firstArgument; index < args.length; index++) {
            if (!args[index].equalsIgnoreCase("all")) continue;
            if (allIndex >= 0) throw new IllegalArgumentException("amount");
            allIndex = index;
        }
        if (allIndex >= 0) {
            for (int index = firstArgument; index < args.length; index++) {
                if (index != allIndex && args[index].matches("[+-]?\\d+")) {
                    throw new IllegalArgumentException("amount");
                }
            }
            ArrayList<String> filtered = new ArrayList<>(Arrays.asList(args));
            filtered.remove(allIndex);
            DeliveryQuery parsed = delivery(
                    filtered.toArray(String[]::new),
                    firstArgument,
                    maxAmount,
                    defaultPlayer,
                    itemNormalizer,
                    playerNormalizer);
            return new DeliveryQuery(parsed.item(), parsed.count(), parsed.player(), true);
        }

        ResourceQuery unaddressed = null;
        try {
            unaddressed = resource(args, firstArgument, maxAmount);
        } catch (IllegalArgumentException exception) {
            // A namespace colon after a possible player makes the complete
            // tail invalid as one identifier. It can still be a valid
            // addressed form such as "Friend minecraft:diamond 4".
            if ("amount".equals(exception.getMessage())) {
                throw exception;
            }
        }
        if (unaddressed != null) {
            String item = itemNormalizer.apply(unaddressed.query());
            if (item != null) {
                return new DeliveryQuery(item, unaddressed.amount(), defaultPlayer, false);
            }
        }

        if (args.length <= firstArgument + 1) {
            throw new IllegalArgumentException("item");
        }
        String player = playerNormalizer.apply(args[firstArgument]);
        if (player == null) {
            throw new IllegalArgumentException("player");
        }
        ResourceQuery addressed = resource(args, firstArgument + 1, maxAmount);
        String item = itemNormalizer.apply(addressed.query());
        if (item == null) {
            throw new IllegalArgumentException("item");
        }
        return new DeliveryQuery(item, addressed.amount(), player, false);
    }

    /**
     * Parses two or more {@code <item words> <count>} groups, optionally
     * preceded by a delivery recipient. Single-item input deliberately
     * returns {@code null} so the established delivery grammar remains the
     * sole authority for every legacy command.
     */
    static DeliveryBatch deliveryBatch(
            String[] args,
            int firstArgument,
            int maxAmount,
            String defaultPlayer,
            Function<String, String> itemNormalizer,
            Function<String, String> playerNormalizer
    ) {
        String[] withoutOverride = withoutReserveOverride(args, firstArgument);
        if (withoutOverride != args) {
            DeliveryBatch parsed = deliveryBatch(withoutOverride, firstArgument, maxAmount,
                    defaultPlayer, itemNormalizer, playerNormalizer);
            return parsed == null ? null : new DeliveryBatch(parsed.items(), parsed.player(), true);
        }
        List<ItemAmount> unaddressed = null;
        IllegalArgumentException unaddressedFailure = null;
        try {
            unaddressed = itemBatch(args, firstArgument, maxAmount, itemNormalizer);
        } catch (IllegalArgumentException failure) {
            unaddressedFailure = failure;
        }
        if (unaddressed != null) {
            return new DeliveryBatch(unaddressed, defaultPlayer);
        }
        if (args.length <= firstArgument + 1) return null;
        String player = playerNormalizer.apply(args[firstArgument]);
        if (player == null) {
            if (unaddressedFailure != null) throw unaddressedFailure;
            return null;
        }
        List<ItemAmount> addressed;
        try {
            addressed = itemBatch(args, firstArgument + 1, maxAmount, itemNormalizer);
        } catch (IllegalArgumentException addressedFailure) {
            // Prefer the item-first interpretation's precise validation error.
            // This preserves the legacy rule that a complete valid item tail
            // belongs to the owner, while still accepting a real recipient
            // when only the addressed grammar parses.
            if (unaddressedFailure != null) throw unaddressedFailure;
            throw addressedFailure;
        }
        if (addressed == null) {
            if (unaddressedFailure != null) throw unaddressedFailure;
            return null;
        }
        return new DeliveryBatch(addressed, player);
    }

    /** Parses two or more counted item groups for acquisition commands. */
    static List<ItemAmount> itemBatch(
            String[] args,
            int firstArgument,
            int maxAmount,
            Function<String, String> itemNormalizer
    ) {
        if (args.length <= firstArgument) return null;
        ArrayList<ItemAmount> result = new ArrayList<>();
        LinkedHashSet<String> seenItems = new LinkedHashSet<>();
        int itemStart = firstArgument;
        int total = 0;
        for (int index = firstArgument; index < args.length; index++) {
            String token = args[index];
            if (!token.matches("[+-]?\\d+")) continue;
            if (index == itemStart) {
                // A leading number may still belong to the legacy single-item
                // grammar. Once one complete pair was seen, however, another
                // number without an item is unambiguously malformed batch
                // input and must not be reinterpreted as one long item name.
                if (result.isEmpty()) return null;
                throw new IllegalArgumentException("amount");
            }
            int count;
            try {
                count = Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("amount");
            }
            if (count < 1 || count > maxAmount) {
                throw new IllegalArgumentException("amount");
            }
            String item = normalizedItem(args, itemStart, index, itemNormalizer);
            // A number can legitimately be part of a legacy multi-word item
            // name (for example "music disc 5"). If the prefix is not an
            // item, leave the entire command to the single-item parser.
            if (item == null) {
                if (result.isEmpty()) return null;
                throw new IllegalArgumentException("item");
            }
            if (!seenItems.add(item)) {
                throw new IllegalArgumentException("duplicate");
            }
            try {
                total = Math.addExact(total, count);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("amount", overflow);
            }
            result.add(new ItemAmount(item, count));
            itemStart = index + 1;
        }
        if (itemStart != args.length) {
            // One complete pair followed by another known item is a batch
            // missing its final count, not a plausible legacy single item.
            if (!result.isEmpty()
                    && normalizedItem(args, itemStart, args.length, itemNormalizer) != null) {
                throw new IllegalArgumentException("amount");
            }
            if (result.size() >= 2) throw new IllegalArgumentException("amount");
        }
        if (result.size() < 2) return null;
        return List.copyOf(result);
    }

    private static String normalizedItem(
            String[] args,
            int start,
            int end,
            Function<String, String> itemNormalizer
    ) {
        StringBuilder query = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (!query.isEmpty()) query.append('_');
            query.append(args[index]);
        }
        String identifier = identifier(query.toString());
        return identifier == null ? null : itemNormalizer.apply(identifier);
    }

    static ToggleRequest toggle(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("toggle");
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> new ToggleRequest("status", null);
            case "on", "enable", "enabled", "true" -> new ToggleRequest("set", true);
            case "off", "disable", "disabled", "false" -> new ToggleRequest("set", false);
            default -> throw new IllegalArgumentException("toggle");
        };
    }

    static StockRequest stock(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("stock");
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> new StockRequest("status", null);
            case "run" -> new StockRequest("run", null);
            case "on", "enable", "enabled", "true" -> new StockRequest("set", true);
            case "off", "disable", "disabled", "false" -> new StockRequest("set", false);
            default -> throw new IllegalArgumentException("stock");
        };
    }

    static PerceptionRequest perception(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("perception");
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> new PerceptionRequest("status", null);
            case "normal" -> new PerceptionRequest("set", "NORMAL");
            case "legit" -> new PerceptionRequest("set", "LEGITIMATE");
            default -> throw new IllegalArgumentException("perception");
        };
    }

    static IdleRequest idle(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("idle");
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2) return switch (operation) {
            case "status" -> new IdleRequest("status", null, null, null, null);
            case "on", "off" -> new IdleRequest("set", operation.equals("on"), null, null, null);
            default -> throw new IllegalArgumentException("idle");
        };
        if (args.length == 3 && operation.equals("offline")) {
            if (!Set.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("idle offline");
            return new IdleRequest("offline", args[2].equalsIgnoreCase("on"), null, null, null);
        }
        if (args.length == 3 && operation.equals("delay")) {
            int minutes = idleNumber(args[2], 1, 1440);
            return new IdleRequest("delay", null, minutes * 60_000L, null, null);
        }
        if (args.length == 4 && operation.equals("target")) {
            String category = args[2].toLowerCase(Locale.ROOT);
            if (!List.of("food", "fuel", "logs", "iron").contains(category))
                throw new IllegalArgumentException("idle target");
            return new IdleRequest("target", null, null, category, idleNumber(args[3], 0, 4096));
        }
        throw new IllegalArgumentException("idle");
    }

    private static int idleNumber(String value, int minimum, int maximum) {
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("idle range");
        try {
            int number = Integer.parseInt(value);
            if (number < minimum || number > maximum) throw new IllegalArgumentException("idle range");
            return number;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("idle range"); }
    }

    static HomeRequest home(String[] args) {
        if (args.length == 3
                && args[2].equalsIgnoreCase("confirm")
                && (args[1].equalsIgnoreCase("set")
                || args[1].equalsIgnoreCase("adopt") || args[1].equalsIgnoreCase("clear"))) {
            return new HomeRequest(args[1].toLowerCase(Locale.ROOT) + "_confirm");
        }
        if (args.length != 2) throw new IllegalArgumentException("home");
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set", "establish", "setup", "adopt", "status", "show", "go", "sleep", "clear" ->
                    new HomeRequest(args[1].toLowerCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("home");
        };
    }

    static final List<String> HOME_STORAGE_OPERATIONS = List.of("add", "confirm", "list", "remove");

    static final List<String> TIDY_OPERATIONS = List.of("home", "confirm", "status", "keep", "junk", "auto", "spot");

    static TidyRequest tidy(String[] args, Function<String, String> exactItemNormalizer) {
        if (args.length == 0) throw new IllegalArgumentException("tidy");
        if (args.length == 1) return new TidyRequest("preview", false, null, null);
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (operation) {
                case "home" -> new TidyRequest("preview", true, null, null);
                case "confirm", "status" -> new TidyRequest(operation, false, null, null);
                case "spot" -> new TidyRequest("spot_preview", false, null, null);
                default -> throw new IllegalArgumentException("tidy");
            };
        }
        if (args.length == 3 && (operation.equals("keep") || operation.equals("junk"))) {
            String exact = args[2].toLowerCase(Locale.ROOT);
            if (exact.startsWith("minecraft:")) exact = exact.substring("minecraft:".length());
            String item = exact.matches("[a-z0-9_]+") ? exactItemNormalizer.apply(exact) : null;
            if (item == null) throw new IllegalArgumentException("tidy exact item");
            return new TidyRequest(operation, false, item, null);
        }
        if (args.length == 3 && operation.equals("auto")) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "on" -> new TidyRequest("auto", false, null, true);
                case "off" -> new TidyRequest("auto", false, null, false);
                default -> throw new IllegalArgumentException("tidy");
            };
        }
        if (args.length == 3 && operation.equals("spot") && args[2].equalsIgnoreCase("confirm")) {
            return new TidyRequest("spot_confirm", false, null, null);
        }
        throw new IllegalArgumentException("tidy");
    }

    static HomeStorageRequest homeStorage(String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("storage")) {
            throw new IllegalArgumentException("home storage");
        }
        String operation = args[2].toLowerCase(Locale.ROOT);
        if (args.length == 3 && Set.of("add", "confirm", "list").contains(operation)) {
            return new HomeStorageRequest(operation, null);
        }
        if (args.length == 4 && operation.equals("remove")) {
            String id = args[3];
            // Client-issued registry IDs are opaque: do not truncate or reinterpret them.
            if (id.isBlank() || id.length() > 160 || !id.matches("[A-Za-z0-9_.:-]+")) {
                throw new IllegalArgumentException("storage id");
            }
            return new HomeStorageRequest(operation, id);
        }
        throw new IllegalArgumentException("home storage");
    }

    static AreaRequest area(String[] args) {
        if (args.length == 2) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "finish", "confirm", "cancel", "list" ->
                        new AreaRequest(args[1].toLowerCase(Locale.ROOT), null, null);
                default -> throw new IllegalArgumentException("area");
            };
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("show")) {
            return new AreaRequest("show", null, areaName(args[2]));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            return new AreaRequest("remove", null, areaName(args[2]));
        }
        if (args.length == 4
                && args[1].equalsIgnoreCase("begin")) {
            ProtectedAreaPolicy.AreaKind kind;
            try {
                kind = ProtectedAreaPolicy.AreaKind.parse(args[2]);
                if (kind == ProtectedAreaPolicy.AreaKind.ENTITY_BASE) {
                    throw new IllegalArgumentException("Use /e home protect begin <name>");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("area", invalid);
            }
            return new AreaRequest("begin", kind, areaName(args[3]));
        }
        if (args.length == 4
                && args[1].equalsIgnoreCase("remove")
                && args[3].equalsIgnoreCase("confirm")) {
            return new AreaRequest("remove_confirm", null, areaName(args[2]));
        }
        throw new IllegalArgumentException("area");
    }

    static AreaRequest homeProtect(String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("protect")) {
            throw new IllegalArgumentException("home protect");
        }
        String operation = args[2].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return switch (operation) {
                case "finish", "confirm", "cancel" -> new AreaRequest(operation, null, null);
                case "show" -> new AreaRequest("list", null, null);
                default -> throw new IllegalArgumentException("home protect");
            };
        }
        if (args.length == 4) {
            return switch (operation) {
                case "begin" -> new AreaRequest(operation,
                        ProtectedAreaPolicy.AreaKind.ENTITY_BASE, areaName(args[3]));
                case "show", "remove" -> new AreaRequest(operation, null, areaName(args[3]));
                default -> throw new IllegalArgumentException("home protect");
            };
        }
        if (args.length == 5 && operation.equals("remove")
                && args[4].equalsIgnoreCase("confirm")) {
            return new AreaRequest("remove_confirm", null, areaName(args[3]));
        }
        throw new IllegalArgumentException("home protect");
    }

    static FarmRequest farm(String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            return new FarmRequest("status", null, null);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("status")) {
            return new FarmRequest("status", areaName(args[2]), null);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("run")) {
            return new FarmRequest("run", areaName(args[2]), null);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("auto")) {
            Boolean enabled = switch (args[3].toLowerCase(Locale.ROOT)) {
                case "on" -> true;
                case "off" -> false;
                default -> throw new IllegalArgumentException("farm auto");
            };
            return new FarmRequest("auto", areaName(args[2]), enabled);
        }
        throw new IllegalArgumentException("farm");
    }

    static final List<String> BUILD_OPERATIONS = List.of("list", "import", "select", "here",
            "rotate", "show", "materials", "confirm", "status", "resume", "clear");

    static BuildRequest build(String[] args) {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("build");
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2 && Set.of("list", "here", "show", "materials", "status", "resume", "clear", "confirm")
                .contains(operation)) return new BuildRequest(operation, null, null, null, false);
        if (args.length == 3) {
            if (operation.equals("confirm") && args[2].equalsIgnoreCase("gather"))
                return new BuildRequest(operation, null, null, null, true);
            if (operation.equals("clear") && args[2].equalsIgnoreCase("confirm"))
                return new BuildRequest("clear_confirm", null, null, null, false);
            if (operation.equals("select") && args[2].matches("[A-Za-z0-9_.:-]{1,96}"))
                return new BuildRequest(operation, null, args[2].toLowerCase(Locale.ROOT), null, false);
            if (operation.equals("rotate") && Set.of("90", "180", "270").contains(args[2]))
                return new BuildRequest(operation, null, null, Integer.parseInt(args[2]), false);
            if (operation.equals("import") && args[2].length() <= 2048 && !args[2].matches(".*\\s.*")) {
                String source = args[2];
                if (source.matches("(?:abfielder:)?[0-9]{1,12}"))
                    return new BuildRequest(operation, source, null, null, false);
                try {
                    java.net.URI uri = java.net.URI.create(source);
                    if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                            && uri.getUserInfo() == null)
                        return new BuildRequest(operation, source, null, null, false);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        throw new IllegalArgumentException("build");
    }

    private static String areaName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,24}")) {
            throw new IllegalArgumentException("area name");
        }
        return normalized;
    }

    /** Combat is a persistent policy mode, never an attack-mission synonym. */
    static CombatRequest combat(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("combat");
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> new CombatRequest("status", null);
            case "avoid", "defensive", "aggressive" ->
                    new CombatRequest("set", args[1].toLowerCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("combat");
        };
    }

    /** Keeps the legacy no-argument iron default while adding safe aliases/queries. */
    static GearRequest gear(String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("gear");
        }
        if (args.length == 1) {
            return new GearRequest("submit", "iron");
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> new GearRequest("status", null);
            // Diamond is the strongest loadout already understood by the client.
            case "best" -> new GearRequest("submit", "diamond");
            case "wood", "stone", "iron", "diamond" ->
                    new GearRequest("submit", args[1].toLowerCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("gear");
        };
    }

    static ProtectionRequest protection(String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            return new ProtectionRequest("status", null, null);
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("protection");
        }
        String scope = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "self" -> "self";
            case "me", "owner" -> "owner";
            default -> throw new IllegalArgumentException("scope");
        };
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (mode.equals("status")) {
            return new ProtectionRequest("status", scope, null);
        }
        Boolean enabled = switch (mode) {
            case "on", "enable", "enabled", "true" -> true;
            case "off", "disable", "disabled", "false" -> false;
            default -> throw new IllegalArgumentException("enabled");
        };
        return new ProtectionRequest("set", scope, enabled);
    }

    private static Double finiteDouble(String value) {
        try {
            double result = Double.parseDouble(value);
            return Double.isFinite(result) ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record GoToRequest(String player, Double x, Double y, Double z) {
        static GoToRequest player(String player) {
            return new GoToRequest(player, null, null, null);
        }

        static GoToRequest coordinates(double x, double y, double z) {
            return new GoToRequest(null, x, y, z);
        }

        boolean targetsPlayer() {
            return player != null;
        }
    }

    record ResourceQuery(String query, int amount) {
    }

    private static String[] withoutReserveOverride(String[] args, int firstArgument) {
        int found = -1;
        for (int i = firstArgument; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            if (!args[i].equals("--use-reserves") || found >= 0) {
                throw new IllegalArgumentException("use --use-reserves at most once");
            }
            found = i;
        }
        if (found < 0) return args;
        ArrayList<String> filtered = new ArrayList<>(Arrays.asList(args));
        filtered.remove(found);
        return filtered.toArray(String[]::new);
    }

    record DeliveryQuery(String item, int count, String player, boolean all, boolean useReserves) {
        DeliveryQuery(String item, int count, String player, boolean all) {
            this(item, count, player, all, false);
        }
    }

    record ItemAmount(String item, int count) {
    }

    record DeliveryBatch(List<ItemAmount> items, String player, boolean useReserves) {
        DeliveryBatch(List<ItemAmount> items, String player) { this(items, player, false); }
        DeliveryBatch {
            items = List.copyOf(items);
        }
    }

    record ToggleRequest(String operation, Boolean enabled) {
    }

    record StockRequest(String operation, Boolean enabled) {
    }

    record PerceptionRequest(String operation, String mode) {
    }

    record IdleRequest(String operation, Boolean enabled, Long delayMillis, String category, Integer count) {
    }

    record HomeRequest(String operation) {
    }

    record HomeStorageRequest(String operation, String storageId) {
    }

    record TidyRequest(String operation, boolean includeStorage, String item, Boolean enabled) {
    }

    record AreaRequest(
            String operation,
            ProtectedAreaPolicy.AreaKind kind,
            String name) {
    }

    record FarmRequest(String operation, String area, Boolean enabled) {
    }

    record BuildRequest(String operation, String source, String design, Integer rotation, boolean gather) {
        boolean mission() { return operation.equals("confirm") || operation.equals("resume"); }
        boolean ownerOnly() { return !Set.of("list", "show", "materials", "status").contains(operation); }
    }

    record CombatRequest(String operation, String combatMode) {
    }

    record GearRequest(String operation, String tier) {
    }

    record ProtectionRequest(String operation, String scope, Boolean enabled) {
    }
}
