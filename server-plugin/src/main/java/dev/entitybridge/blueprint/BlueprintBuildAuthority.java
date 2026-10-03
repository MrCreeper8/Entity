package dev.entitybridge.blueprint;

import dev.entitybridge.mission.Mission;
import dev.entitybridge.mission.MissionStatus;
import dev.entitybridge.stewardship.ProtectedAreaRegistry;
import dev.entitybridge.stewardship.ProtectedAreaSynchronization;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.nio.file.Path;
import java.io.IOException;
import dev.entitybridge.blueprint.BlueprintSupportStore.Key;
import dev.entitybridge.blueprint.BlueprintSupportStore.Receipt;

/** Exact-cell owner confirmation, fenced by the existing current mission and policy. */
public final class BlueprintBuildAuthority {
    private final Supplier<Mission> mission;
    private final ProtectedAreaRegistry areas;
    private final ProtectedAreaSynchronization synchronization;
    private BlueprintPreview preview;
    private String owner;
    private long revision;
    private String policyDigest;
    private Map<BlueprintPreview.Position, BlueprintPreview.Cell> cells = Map.of();
    private final BlueprintSupportStore supportStore;
    private Map<Key, Receipt> supports = new HashMap<>();
    private boolean supportStorageReady = true;

    public BlueprintBuildAuthority(Supplier<Mission> mission, ProtectedAreaRegistry areas,
                                   ProtectedAreaSynchronization synchronization) {
        this(mission, areas, synchronization, null);
    }

    public BlueprintBuildAuthority(Supplier<Mission> mission, ProtectedAreaRegistry areas,
                                   ProtectedAreaSynchronization synchronization, Path supportPath) {
        this.mission = Objects.requireNonNull(mission);
        this.areas = Objects.requireNonNull(areas);
        this.synchronization = Objects.requireNonNull(synchronization);
        supportStore = supportPath == null ? null : new BlueprintSupportStore(supportPath);
        if (supportStore != null) {
            try { supports = supportStore.load(); }
            catch (IOException invalid) { supportStorageReady = false; }
        }
    }

    public synchronized void preview(String owner, BlueprintPreview candidate) {
        this.owner = Objects.requireNonNull(owner);
        this.preview = Objects.requireNonNull(candidate);
        revision = areas.snapshot().revision();
        policyDigest = areas.snapshot().digest();
        HashMap<BlueprintPreview.Position, BlueprintPreview.Cell> indexed = new HashMap<>();
        candidate.cells().forEach(cell -> indexed.put(cell.position(), cell));
        cells = Map.copyOf(indexed);
    }

    public synchronized BlueprintPreview previewFor(String owner, String worldId, String dimension) {
        return preview != null && this.owner.equalsIgnoreCase(owner) && preview.worldId().equals(worldId)
                && preview.dimension().equals(dimension) && areas.snapshot().revision() == revision
                && areas.snapshot().digest().equals(policyDigest) ? preview : null;
    }

    /** Called only for the correlated response to the primary owner's explicit saved-project resume. */
    public synchronized boolean reconcileResume(String owner,BlueprintPreview candidate,String worldId,String dimension,
            long expectedRevision,String expectedPolicyDigest) {
        if(!synchronization.mutationAuthorityReady()||!candidate.worldId().equals(worldId)
                ||!candidate.dimension().equals(dimension)||areas.snapshot().revision()!=expectedRevision
                ||!areas.snapshot().digest().equals(expectedPolicyDigest))return false;
        preview(owner,candidate);
        return true;
    }

    public synchronized void clear() { preview = null; owner = null; cells = Map.of(); }

    public synchronized boolean mayBreak(String dimension, int x, int y, int z, String observed) {
        return mayBreak(dimension,x,y,z,observed,false);
    }
    public synchronized boolean mayBreak(String dimension,int x,int y,int z,String observed,boolean safeRepair) {
        BlueprintPreview.Cell cell = activeCell(dimension, x, y, z);
        if (cell != null && supportCell(cell)) {
            Key key = supportKey(dimension, x, y, z);
            Receipt receipt = supports.get(key);
            if (!supportStorageReady) return false;
            if (receipt == null) return safeRepair&&!air(observed);
            if (!BlueprintPreview.matchesState(receipt.state(),observed)) {
                supports.remove(key);
                persistSupports();
                return safeRepair&&!air(observed);
            }
            return receipt.owner().equalsIgnoreCase(owner) && receipt.projectId().equals(preview.projectId())
                    && receipt.digest().equals(preview.digest());
        }
        // A matching completed block is not a blanket excuse to dismantle the structure again.
        return cell != null && !BlueprintPreview.matchesState(cell.state(),observed)
                && !air(observed) && (safeRepair||BlueprintPreview.matchesState(cell.before(),observed));
    }

    public synchronized boolean mayPlace(String dimension, int x, int y, int z, String replaced, String placed) {
        BlueprintPreview.Cell cell = activeCell(dimension, x, y, z);
        if (cell != null && supportCell(cell)) {
            // This is the final BlockPlace boundary, not the earlier item-use request.
            // Only actual AIR can be replaced; even an empty container is foreign.
            if (!supportStorageReady || !air(replaced)
                    || !BlueprintSupportStore.support(placed)) return false;
            Key key = supportKey(dimension, x, y, z);
            if (!supports.containsKey(key) && supports.size() >= BlueprintPreview.MAX_CELLS) return false;
            supports.put(key, new Receipt(key, owner, preview.projectId(), preview.digest(), placed));
            return persistSupports();
        }
        return cell != null && BlueprintPreview.matchesState(cell.state(),placed)
                && (BlueprintPreview.matchesState(cell.before(),replaced) || air(replaced));
    }
    /** Vanilla removing a bed affects its partner even without a second break event. */
    public synchronized boolean includesBedPair(String dimension,int x,int y,int z,int partnerX,int partnerY,int partnerZ) {
        return Math.abs(x-partnerX)+Math.abs(z-partnerZ)==1&&y==partnerY
                &&activeCell(dimension,x,y,z)!=null&&activeCell(dimension,partnerX,partnerY,partnerZ)!=null;
    }

    public synchronized boolean mayClickPlacement(String dimension, int x, int y, int z, String item) {
        BlueprintPreview.Cell cell = activeCell(dimension, x, y, z);
        return cell != null && (supportCell(cell)
                ? supportStorageReady && BlueprintSupportStore.support(item)
                : !air(cell.state()) && BlueprintPreview.matchesState(cell.material(),item));
    }

    /** Accepted break/foreign replacement invalidates even a same-material replacement. */
    public synchronized void invalidateSupport(String dimension, int x, int y, int z) {
        boolean changed = supports.keySet().removeIf(key -> key.dimension().equals(dimension)
                && key.x() == x && key.y() == y && key.z() == z);
        if (changed) persistSupports();
    }

    private Key supportKey(String dimension, int x, int y, int z) {
        return new Key(preview.worldId(), dimension, x, y, z);
    }

    private static boolean supportCell(BlueprintPreview.Cell cell) {
        return air(cell.before()) && air(cell.state());
    }

    private boolean persistSupports() {
        if (!supportStorageReady) return false;
        if (supportStore == null) return true;
        try { supportStore.save(supports); return true; }
        catch (IOException failure) { supportStorageReady = false; return false; }
    }

    private BlueprintPreview.Cell activeCell(String dimension, int x, int y, int z) {
        if (preview == null || !preview.dimension().equals(dimension) || !synchronization.mutationAuthorityReady()
                || areas.snapshot().revision() != revision || !areas.snapshot().digest().equals(policyDigest)) return null;
        Mission active = mission.get();
        if (active == null || !active.action().equals("build")
                || active.status() != MissionStatus.ACTIVE && active.status() != MissionStatus.RETRYING
                || !active.requestedBy().equalsIgnoreCase(owner)
                || !matches(active, "projectId", preview.projectId()) || !matches(active, "digest", preview.digest())
                || !matches(active, "worldId", preview.worldId())) return null;
        return cells.get(new BlueprintPreview.Position(x, y, z));
    }

    private static boolean matches(Mission mission, String key, String expected) {
        return mission.args().has(key) && mission.args().get(key).isJsonPrimitive()
                && expected.equals(mission.args().get(key).getAsString());
    }

    private static boolean air(String state) {
        return state.equals("minecraft:air") || state.equals("minecraft:cave_air") || state.equals("minecraft:void_air");
    }
}
