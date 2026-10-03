package dev.entitybridge.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Durable Paper-side ledger for exact item handoffs.
 *
 * <p>A prepared nonce is bound to the first matching item stack that Entity
 * actually drops. Pickup progress is cumulative per item UUID, which makes a
 * repeated Bukkit event or a replayed bridge receipt harmless.</p>
 */
public final class DeliveryTransactionRegistry {
    private static final int SCHEMA = 2;
    private static final int MAX_HISTORY = 256;
    private static final long PREPARE_TTL_MILLIS = 60_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path persistenceFile;
    private final Logger logger;
    private final Map<String, Transaction> transactions = new LinkedHashMap<>();
    private final Map<UUID, DropState> drops = new LinkedHashMap<>();
    private final Map<String, Receipt> unacknowledged = new LinkedHashMap<>();
    private final Map<String, ReturnState> unacknowledgedReturns = new LinkedHashMap<>();

    public DeliveryTransactionRegistry() {
        this(null, Logger.getLogger(DeliveryTransactionRegistry.class.getName()));
    }

    public DeliveryTransactionRegistry(Path persistenceFile, Logger logger) {
        this.persistenceFile = persistenceFile;
        this.logger = Objects.requireNonNull(logger, "logger");
        load();
    }

    public synchronized PrepareResult prepare(Preparation preparation) {
        Objects.requireNonNull(preparation, "preparation");
        Transaction prior = transactions.get(preparation.nonce());
        if (prior != null) {
            boolean same = prior.missionId().equals(preparation.missionId())
                    && prior.recipient().equalsIgnoreCase(preparation.recipient())
                    && prior.itemId().equals(normalizeItem(preparation.itemId()))
                    && prior.requestedCount() == preparation.count()
                    && prior.selections().equals(preparation.selections());
            if (!same) return new PrepareResult(false, "nonce is already bound to another handoff");
            transactions.put(prior.nonce(), prior.withPreparedAt(preparation.timestamp()));
            save();
            return new PrepareResult(true, "already prepared");
        }
        Transaction transaction = new Transaction(
                preparation.missionId(),
                preparation.nonce(),
                preparation.recipient(),
                normalizeItem(preparation.itemId()),
                preparation.count(),
                preparation.timestamp(),
                false,
                0, preparation.selections());
        transactions.put(transaction.nonce(), transaction);
        trimHistory();
        save();
        return new PrepareResult(true, "prepared exact Paper handoff");
    }

    /**
     * Resolves a direct inventory commit exclusively from the durable prepared
     * transaction. Client-provided recipient, item, and count values are never
     * trusted. A nonce with a live tagged ground stack remains on the physical
     * fallback path until Entity recovers that stack.
     */
    public synchronized CommitAuthorization authorizeDirectCommit(
            String missionId,
            String nonce,
            long timestamp) {
        String checkedMission;
        String checkedNonce;
        try {
            checkedMission = require(missionId, "missionId", 128);
            checkedNonce = require(nonce, "nonce", 128);
        } catch (IllegalArgumentException error) {
            return CommitAuthorization.rejected("invalid_request", error.getMessage());
        }
        Transaction transaction = transactions.get(checkedNonce);
        if (transaction == null) {
            return CommitAuthorization.rejected(
                    "unknown_nonce", "delivery nonce is not prepared on this Paper server");
        }
        if (!transaction.missionId().equals(checkedMission)) {
            return CommitAuthorization.rejected(
                    "transaction_mismatch", "nonce is bound to a different mission");
        }
        int remaining = Math.max(0, transaction.requestedCount() - transaction.confirmedCount());
        if (transaction.dropClaimed() && remaining > 0) {
            return CommitAuthorization.rejected(
                    "physical_drop_active",
                    "nonce already owns a tagged ground stack; recover it before direct handoff");
        }
        return CommitAuthorization.accepted(new DeliveryCommitRequest(
                transaction.missionId(), transaction.nonce(), transaction.recipient(),
                transaction.itemId(), transaction.requestedCount(), transaction.confirmedCount(),
                remaining, timestamp, transaction.selections()));
    }

    /**
     * Commits only the number of real items already moved by Paper on its main
     * thread and creates the same cumulative durable receipt used by tagged
     * physical drops.
     */
    public synchronized DirectCommitRecord recordDirectCommit(
            String missionId,
            String nonce,
            int insertedCount,
            long timestamp) {
        CommitAuthorization authorization = authorizeDirectCommit(missionId, nonce, timestamp);
        if (!authorization.accepted()) {
            throw new IllegalStateException(authorization.code() + ": " + authorization.reason());
        }
        DeliveryCommitRequest request = authorization.request();
        if (insertedCount < 0 || insertedCount > request.remainingCount()) {
            throw new IllegalArgumentException("insertedCount exceeds the prepared remainder");
        }
        if (insertedCount == 0) {
            return new DirectCommitRecord(request, null);
        }

        Transaction transaction = transactions.get(nonce);
        int confirmed = transaction.confirmedCount() + insertedCount;
        Transaction updated = transaction.withConfirmedCount(confirmed);
        transactions.put(nonce, updated);
        String receiptId = updated.nonce() + ":direct:" + confirmed;
        Receipt receipt = new Receipt(
                receiptId,
                updated.missionId(),
                updated.nonce(),
                updated.recipient(),
                updated.itemId(),
                insertedCount,
                confirmed,
                updated.requestedCount(),
                timestamp);
        unacknowledged.put(receiptId, receipt);
        try {
            save();
        } catch (RuntimeException failure) {
            // The Bukkit committer rolls both inventories back when this call
            // fails. Roll the in-memory ledger back as well so a later replay
            // cannot claim delivery for items returned to Entity.
            transactions.put(nonce, transaction);
            unacknowledged.remove(receiptId);
            throw failure;
        }
        DeliveryCommitRequest committed = new DeliveryCommitRequest(
                updated.missionId(), updated.nonce(), updated.recipient(), updated.itemId(),
                updated.requestedCount(), confirmed,
                Math.max(0, updated.requestedCount() - confirmed), timestamp, updated.selections());
        return new DirectCommitRecord(committed, receipt);
    }

    /** Binds an actual Entity-owned item entity to exactly one prepared nonce. */
    public synchronized Optional<Claim> claimDrop(
            UUID itemEntityId,
            String itemId,
            int count,
            long timestamp) {
        Objects.requireNonNull(itemEntityId, "itemEntityId");
        String normalized = normalizeItem(itemId);
        if (count <= 0) return Optional.empty();
        DropState existing = drops.get(itemEntityId);
        if (existing != null) {
            Transaction transaction = transactions.get(existing.nonce());
            return transaction == null ? Optional.empty() : Optional.of(claim(transaction));
        }
        Transaction selected = transactions.values().stream()
                .filter(transaction -> transaction.selections().isEmpty())
                .filter(transaction -> !transaction.dropClaimed())
                .filter(transaction -> transaction.itemId().equals(normalized))
                .filter(transaction -> timestamp - transaction.preparedAt() <= PREPARE_TTL_MILLIS)
                .filter(transaction -> count <= transaction.requestedCount())
                .max(Comparator.comparingLong(Transaction::preparedAt))
                .orElse(null);
        if (selected == null) return Optional.empty();

        Transaction claimed = selected.withDropClaimed(true);
        transactions.put(claimed.nonce(), claimed);
        drops.put(itemEntityId, new DropState(itemEntityId, claimed.nonce(), count, count));
        save();
        return Optional.of(claim(claimed));
    }

    /**
     * Records the remaining amount after a recipient pickup. The returned
     * receipt contains a cumulative confirmed count, so replay cannot double
     * count delivery progress.
     */
    public synchronized Optional<Receipt> recordPickup(
            UUID itemEntityId,
            String recipient,
            String pickedItem,
            int remainingAfter,
            long timestamp) {
        DropState drop = drops.get(itemEntityId);
        if (drop == null) return Optional.empty();
        Transaction transaction = transactions.get(drop.nonce());
        if (transaction == null
                || !transaction.recipient().equalsIgnoreCase(recipient)
                || !transaction.itemId().equals(normalizeItem(pickedItem))) {
            return Optional.empty();
        }
        int boundedRemaining = Math.max(0, Math.min(drop.initialCount(), remainingAfter));
        if (boundedRemaining >= drop.remaining()) return Optional.empty();
        int delta = drop.remaining() - boundedRemaining;
        int confirmed = Math.min(transaction.requestedCount(), transaction.confirmedCount() + delta);
        Transaction updated = transaction.withConfirmedCount(confirmed);
        transactions.put(updated.nonce(), updated);
        drops.put(itemEntityId, drop.withRemaining(boundedRemaining));

        String receiptId = updated.nonce() + ':' + itemEntityId + ':' + boundedRemaining;
        Receipt receipt = new Receipt(
                receiptId,
                updated.missionId(),
                updated.nonce(),
                updated.recipient(),
                updated.itemId(),
                delta,
                confirmed,
                updated.requestedCount(),
                timestamp);
        unacknowledged.put(receiptId, receipt);
        save();
        return Optional.of(receipt);
    }

    /** Reopens the same nonce when Entity accidentally re-collects its tagged stack. */
    public synchronized Optional<ReturnState> returnDrop(UUID itemEntityId, long timestamp) {
        DropState drop = drops.remove(itemEntityId);
        if (drop == null) return Optional.empty();
        Transaction transaction = transactions.get(drop.nonce());
        if (transaction == null || transaction.confirmedCount() >= transaction.requestedCount()) {
            save();
            return Optional.empty();
        }
        transactions.put(transaction.nonce(), transaction.withDropClaimed(false).withPreparedAt(timestamp));
        String returnId = transaction.nonce() + ':' + itemEntityId + ":returned";
        ReturnState returned = new ReturnState(
                returnId,
                transaction.missionId(),
                transaction.nonce(),
                transaction.recipient(),
                transaction.itemId(),
                transaction.confirmedCount(),
                transaction.requestedCount(),
                transaction.requestedCount() - transaction.confirmedCount(),
                timestamp);
        unacknowledgedReturns.put(returnId, returned);
        save();
        return Optional.of(returned);
    }

    public synchronized void acknowledge(String receiptId) {
        if (receiptId == null || receiptId.isBlank()) return;
        if (unacknowledged.remove(receiptId) != null) save();
    }

    public synchronized List<JsonObject> pendingReceiptFrames() {
        return unacknowledged.values().stream().map(Receipt::toFrame).toList();
    }

    public synchronized List<JsonObject> pendingReturnFrames() {
        return unacknowledgedReturns.values().stream().map(ReturnState::toFrame).toList();
    }

    public synchronized void acknowledgeReturn(String returnId) {
        if (returnId == null || returnId.isBlank()) return;
        if (unacknowledgedReturns.remove(returnId) != null) save();
    }

    synchronized Optional<Transaction> transaction(String nonce) {
        return Optional.ofNullable(transactions.get(nonce));
    }

    private static Claim claim(Transaction transaction) {
        return new Claim(
                transaction.missionId(), transaction.nonce(), transaction.recipient(),
                transaction.itemId(), transaction.requestedCount());
    }

    private void trimHistory() {
        while (transactions.size() > MAX_HISTORY) {
            String oldest = transactions.keySet().iterator().next();
            transactions.remove(oldest);
            drops.values().removeIf(drop -> drop.nonce().equals(oldest));
            unacknowledged.values().removeIf(receipt -> receipt.nonce().equals(oldest));
            unacknowledgedReturns.values().removeIf(returned -> returned.nonce().equals(oldest));
        }
    }

    private void load() {
        if (persistenceFile == null || !Files.isRegularFile(persistenceFile)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(
                    persistenceFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray transactionArray = root.has("transactions")
                    ? root.getAsJsonArray("transactions") : new JsonArray();
            for (JsonElement element : transactionArray) {
                Transaction transaction = GSON.fromJson(element, Transaction.class);
                transactions.put(transaction.nonce(), transaction);
            }
            JsonArray dropArray = root.has("drops") ? root.getAsJsonArray("drops") : new JsonArray();
            for (JsonElement element : dropArray) {
                DropState drop = GSON.fromJson(element, DropState.class);
                drops.put(drop.itemEntityId(), drop);
            }
            JsonArray receiptArray = root.has("unacknowledged")
                    ? root.getAsJsonArray("unacknowledged") : new JsonArray();
            for (JsonElement element : receiptArray) {
                Receipt receipt = GSON.fromJson(element, Receipt.class);
                unacknowledged.put(receipt.receiptId(), receipt);
            }
            JsonArray returnArray = root.has("unacknowledgedReturns")
                    ? root.getAsJsonArray("unacknowledgedReturns") : new JsonArray();
            for (JsonElement element : returnArray) {
                ReturnState returned = GSON.fromJson(element, ReturnState.class);
                unacknowledgedReturns.put(returned.returnId(), returned);
            }
        } catch (Exception error) {
            logger.log(Level.WARNING, "Could not load durable delivery transactions", error);
            transactions.clear();
            drops.clear();
            unacknowledged.clear();
            unacknowledgedReturns.clear();
        }
    }

    private void save() {
        if (persistenceFile == null) return;
        try {
            Files.createDirectories(persistenceFile.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schema", SCHEMA);
            root.add("transactions", GSON.toJsonTree(new ArrayList<>(transactions.values())));
            root.add("drops", GSON.toJsonTree(new ArrayList<>(drops.values())));
            root.add("unacknowledged", GSON.toJsonTree(new ArrayList<>(unacknowledged.values())));
            root.add("unacknowledgedReturns", GSON.toJsonTree(
                    new ArrayList<>(unacknowledgedReturns.values())));
            Path temporary = persistenceFile.resolveSibling(persistenceFile.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, persistenceFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, persistenceFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not persist delivery transaction ledger", error);
        }
    }

    public static String normalizeItem(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (normalized.isBlank()) throw new IllegalArgumentException("itemId is required");
        return normalized.indexOf(':') >= 0 ? normalized : "minecraft:" + normalized;
    }

    public record Preparation(
            String missionId,
            String nonce,
            String recipient,
            String itemId,
            int count,
            long timestamp,
            List<DeliveryStackSelection> selections) {
        public Preparation(String missionId, String nonce, String recipient, String itemId, int count, long timestamp) {
            this(missionId, nonce, recipient, itemId, count, timestamp, List.of());
        }
        public Preparation {
            missionId = require(missionId, "missionId", 128);
            nonce = require(nonce, "nonce", 128);
            recipient = require(recipient, "recipient", 64);
            itemId = normalizeItem(itemId);
            if (count <= 0 || count > 64 * 36) throw new IllegalArgumentException("invalid handoff count");
            selections = DeliveryStackSelection.validate(selections, count);
        }

        public static Preparation fromFrame(JsonObject frame) {
            return new Preparation(
                    string(frame, "missionId"), string(frame, "nonce"),
                    string(frame, "recipient"), string(frame, "item"),
                    integer(frame, "count"), number(frame, "timestamp", System.currentTimeMillis()),
                    DeliveryStackSelection.fromFrame(frame.get("selections"), integer(frame, "count")));
        }
    }

    public record PrepareResult(boolean accepted, String reason) {
    }

    public record CommitAuthorization(
            boolean accepted,
            String code,
            String reason,
            DeliveryCommitRequest request) {
        static CommitAuthorization accepted(DeliveryCommitRequest request) {
            return new CommitAuthorization(true, "accepted", "prepared transaction resolved", request);
        }

        static CommitAuthorization rejected(String code, String reason) {
            return new CommitAuthorization(false, code, reason, null);
        }
    }

    public record DirectCommitRecord(
            DeliveryCommitRequest request,
            Receipt receipt) {
    }

    public record Claim(String missionId, String nonce, String recipient, String itemId, int count) {
    }

    record Transaction(
            String missionId,
            String nonce,
            String recipient,
            String itemId,
            int requestedCount,
            long preparedAt,
            boolean dropClaimed,
            int confirmedCount,
            List<DeliveryStackSelection> selections) {
        Transaction {
            selections = DeliveryStackSelection.validate(selections, requestedCount);
        }
        Transaction withDropClaimed(boolean claimed) {
            return new Transaction(missionId, nonce, recipient, itemId, requestedCount,
                    preparedAt, claimed, confirmedCount, selections);
        }

        Transaction withConfirmedCount(int count) {
            return new Transaction(missionId, nonce, recipient, itemId, requestedCount,
                    preparedAt, dropClaimed, count, selections);
        }

        Transaction withPreparedAt(long timestamp) {
            return new Transaction(missionId, nonce, recipient, itemId, requestedCount,
                    timestamp, dropClaimed, confirmedCount, selections);
        }
    }

    record DropState(UUID itemEntityId, String nonce, int initialCount, int remaining) {
        DropState withRemaining(int count) {
            return new DropState(itemEntityId, nonce, initialCount, count);
        }
    }

    public record Receipt(
            String receiptId,
            String missionId,
            String nonce,
            String recipient,
            String itemId,
            int countDelta,
            int confirmedCount,
            int expectedCount,
            long timestamp) {
        public JsonObject toFrame() {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "delivery_receipt");
            frame.addProperty("receiptId", receiptId);
            frame.addProperty("missionId", missionId);
            frame.addProperty("nonce", nonce);
            frame.addProperty("recipient", recipient);
            frame.addProperty("item", itemId);
            frame.addProperty("countDelta", countDelta);
            frame.addProperty("confirmedCount", confirmedCount);
            frame.addProperty("expectedCount", expectedCount);
            frame.addProperty("timestamp", timestamp);
            return frame;
        }
    }

    public record ReturnState(
            String returnId,
            String missionId,
            String nonce,
            String recipient,
            String itemId,
            int confirmedCount,
            int expectedCount,
            int remainingCount,
            long timestamp) {
        public JsonObject toFrame() {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "delivery_returned");
            frame.addProperty("returnId", returnId);
            frame.addProperty("missionId", missionId);
            frame.addProperty("nonce", nonce);
            frame.addProperty("recipient", recipient);
            frame.addProperty("item", itemId);
            frame.addProperty("confirmedCount", confirmedCount);
            frame.addProperty("expectedCount", expectedCount);
            frame.addProperty("remainingCount", remainingCount);
            frame.addProperty("timestamp", timestamp);
            return frame;
        }
    }

    private static String require(String value, String field, int max) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " is missing or too long");
        }
        return normalized;
    }

    private static String string(JsonObject frame, String field) {
        return frame.has(field) && frame.get(field).isJsonPrimitive()
                ? frame.get(field).getAsString() : "";
    }

    private static int integer(JsonObject frame, String field) {
        return frame.has(field) && frame.get(field).isJsonPrimitive()
                ? frame.get(field).getAsInt() : 0;
    }

    private static long number(JsonObject frame, String field, long fallback) {
        return frame.has(field) && frame.get(field).isJsonPrimitive()
                ? frame.get(field).getAsLong() : fallback;
    }
}
