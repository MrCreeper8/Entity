package dev.entity.client.blueprint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.entity.core.persistence.AtomicStoreRecovery;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One selected construction project, saved beside the existing per-world stores. */
public final class BlueprintProjects {
    private String workProgressIdentity;
    private BlueprintBuildSession.WorkProgress workProgress;
    public BlueprintBuildSession.WorkProgress workProgress(BlueprintProject project) {
        String identity=project.worldId()+":"+project.projectId()+":"+project.digest();
        if(!identity.equals(workProgressIdentity)) {
            workProgressIdentity=identity;
            workProgress=new BlueprintBuildSession.WorkProgress();
        }
        return workProgress;
    }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public record Saved(int schema, long revision, String worldId, BlueprintProject project, boolean confirmed,
                        boolean gather, String owner, long policyRevision, String policyDigest) {
        public Saved(int schema,long revision,String worldId,BlueprintProject project,boolean confirmed,boolean gather) {
            this(schema,revision,worldId,project,confirmed,gather,null,0,null);
        }
    }
    private final Path path;
    private final String worldId;
    private final BlueprintCatalog catalog;
    private Saved saved;
    private final BlueprintSupportLedger supports;

    public BlueprintProjects(Path directory, String worldId, BlueprintCatalog catalog) throws IOException {
        this.path = directory.resolve("blueprint-project.json");
        this.worldId = Objects.requireNonNullElse(worldId, ""); this.catalog = catalog;
        supports=new BlueprintSupportLedger(directory,this.worldId);
        saved = AtomicStoreRecovery.load(path, temporary(), "blueprint project",
                () -> new Saved(1, 0, this.worldId, null, false, false), this::decode);
    }
    public BlueprintCatalog catalog() { return catalog; }
    public BlueprintSupportLedger supports(){return supports;}
    public boolean needsSupportReserve(){
        var p=project();return p!=null&&p.maximum().getY()-p.origin().getY()>=2
                &&p.cells().stream().anyMatch(BlueprintSupportLedger::eligible);
    }
    public Map<String,Integer> supplyMaterials(MinecraftClient client,Map<String,Integer> carried) {
        return BlueprintMaterialSupplyPolicy.withSupportReserve(remainingMaterials(client),carried,needsSupportReserve());
    }
    public BlueprintProject project() { return saved.project(); }
    public boolean confirmed() { return saved.confirmed(); }
    public boolean gather() { return saved.gather(); }
    public Map<String,Integer> remainingMaterials(MinecraftClient client) {
        BlueprintProject p=Objects.requireNonNull(project(),"No selected project");
        return remainingMaterials(p,pos->client.world.getBlockState(pos),
                pos->loaded(client,pos));
    }
    static Map<String,Integer> remainingMaterials(BlueprintProject p,
            java.util.function.Function<BlockPos,BlockState> read,java.util.function.Predicate<BlockPos> loaded) {
        BlockPos origin=p.origin(),max=p.maximum();
        var effective=BlueprintDoorMaterials.effectiveGateConnections(p.desired());
        Map<BlockPos,BlockState> remaining=new LinkedHashMap<>();
        for (var cell:p.cells()) {
            BlockState desired=effective.get(cell.pos());
            // An unloaded cell remains required; it must never be credited as completed air.
            if (!loaded.test(cell.pos()) || !BlueprintTerrainStates.matches(desired,read.apply(cell.pos())))
                remaining.put(cell.pos().subtract(origin),BlueprintTerrainStates.placementState(desired));
        }
        if (remaining.isEmpty()) return Map.of();
        return new BlueprintDesign("project",p.designId(),"confirmed project",
                max.getX()-origin.getX()+1,max.getY()-origin.getY()+1,max.getZ()-origin.getZ()+1,
                remaining).materials();
    }
    public String statusSummary(MinecraftClient client) {
        var p = project();
        if (p == null) return "No selected build. /e build list then /e build select <name>.";
        if (client.world == null || !client.world.getRegistryKey().getValue().toString().equals(p.dimension()))
            return BlueprintBundledLibrary.description(p.designId()) + " at " + p.anchor().toShortString()
                    + " in " + p.dimension() + ": " + (confirmed() ? "confirmed" : "preview only")
                    + ". Bring Entity to that dimension to observe construction progress.";
        int observed = 0, matching = 0;
        var desired = BlueprintDoorMaterials.effectiveGateConnections(p.desired());
        for (var cell : p.cells()) {
            if (!loaded(client, cell.pos())) continue;
            observed++;
            if (BlueprintTerrainStates.matches(desired.get(cell.pos()), client.world.getBlockState(cell.pos()))) matching++;
        }
        int temporary = supports.states(p).size();
        String state = !confirmed() ? "Preview only; /e build show then /e build confirm [gather]."
                : matching == p.cells().size() && temporary == 0 ? "All observed cells match; no temporary supports recorded."
                : "Confirmed project; gather=" + gather() + ".";
        return BlueprintBundledLibrary.description(p.designId()) + " at " + p.anchor().toShortString() + ": "
                + state + " Matching " + matching + "/" + p.cells().size() + " cells (" + observed
                + " loaded), temporary supports " + temporary + ". /e build materials lists missing supplies.";
    }

    public String summary(MinecraftClient client) {
        if(project()==null) return "No selected build. /e build list then /e build select <id>";
        var p=project(); var min=p.origin(); var max=p.maximum();
        long clear=p.cells().stream().filter(c->!BlueprintDesign.parseState(c.before()).isAir()
                && !BlueprintTerrainStates.matches(BlueprintDesign.parseState(c.state()),BlueprintDesign.parseState(c.before()))).count();
        String materials=remainingMaterials(client).entrySet().stream().limit(7)
                .map(e->e.getKey().replace("minecraft:","")+"="+e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        long footingCells=p.cells().stream().filter(c->c.y()<p.anchor().getY()).count();
        long wetFootings=p.cells().stream().filter(c->c.y()<p.anchor().getY()
                &&!BlueprintDesign.parseState(c.before()).getFluidState().isEmpty()).count();
        var effective=BlueprintDoorMaterials.effectiveGateConnections(p.desired());
        long reconciled=p.desired().entrySet().stream().filter(e->!e.getValue().equals(effective.get(e.getKey()))).count();
        return BlueprintBundledLibrary.description(p.designId())+" at "+p.anchor().toShortString()+" ("+(max.getX()-min.getX()+1)+"x"
                +(max.getY()-min.getY()+1)+"x"+(max.getZ()-min.getZ()+1)+"), front rotation "
                +(p.rotation()*90)+" degrees; "+clear+" cells to replace/clear; "
                +(confirmed()?"confirmed; /e build resume continues this project":"PREVIEW ONLY; /e build confirm [gather] authorizes this exact site")+". "
                +"/e build here previews a new floor anchor at your position, not Entity's. "
                +"Remaining: "+materials
                +(reconciled>0?". "+reconciled+" legacy gate wall-connection properties follow the selected neighboring walls; blocks/site unchanged":"")
                +(p.cells().stream().anyMatch(c->BlueprintTerrainStates.naturalSoil(BlueprintDesign.parseState(c.state())))
                    ?". Natural dirt/grass cells use ordinary dirt; grass spread/decay is accepted":"")
                +(footingCells>0?". Foundation: "+footingCells+" exact cobblestone cells below the selected floor"
                    +(wetFootings>0?", filling "+wetFootings+" shallow still-water cells":"")
                    +" (maximum depth "+(p.anchor().getY()-min.getY())+"/4); included in confirmation":"")
                +(needsSupportReserve()?". Temporary access: carry dirt/cobblestone (one working stack recommended); supports are removed from the finished footprint":"");
    }
    public BlueprintProject select(MinecraftClient client, String designId, String dimension,
            BlockPos origin, int rotation) throws IOException {
        if (worldId.isBlank() || client.world == null || client.player == null)
            throw new IllegalArgumentException("Join the authenticated world before selecting a build");
        if (!client.world.getRegistryKey().getValue().toString().equals(dimension))
            throw new IllegalArgumentException("Bring Entity to the selected dimension first");
        BlueprintDesign original = catalog.load(designId);
        BlueprintDesign design = original.rotated(rotation);
        Map<BlockPos,BlockState> desired = new LinkedHashMap<>();
        design.cells().forEach((pos,state) -> desired.put(origin.add(pos), state));
        Set<BlockPos> footings=addFootings(desired,origin,pos->state(client,pos),
                pos->stableFootingGround(state(client,pos),client.world,pos));
        desired.putAll(BlueprintDoorMaterials.effectiveGateConnections(desired));
        if (desired.size() > 32768) throw new IllegalArgumentException("Build including footings exceeds 32768 cells");
        List<BlueprintProject.Cell> cells = new ArrayList<>();
        for (var entry : desired.entrySet()) {
            BlockPos pos = entry.getKey(); BlockState before = state(client, pos);
            if (!before.getFluidState().isEmpty() && !(footings.contains(pos)&&stillWater(before)))
                throw new IllegalArgumentException("Cannot select this site: build intersects fluid at " + pos.toShortString()
                        + "; only shallow still water below the selected floor can receive a foundation. "
                        + "Stand at a dry floor level yourself and /e build here; selection uses your position, not Entity's. The site was not moved");
            if (dataBearing(before) && !before.equals(entry.getValue()))
                throw new IllegalArgumentException("Build would replace furniture/container at " + pos.toShortString());
            if (before.getBlock() instanceof net.minecraft.block.BedBlock && !before.equals(entry.getValue())
                    && !repairableBed(pos,before,desired.keySet(),p->state(client,p)))
                throw new IllegalArgumentException("Build would replace an occupied bed or bed half outside the reviewed site at " + pos.toShortString());
            if (before.getHardness(client.world, pos) < 0 && !before.equals(entry.getValue()))
                throw new IllegalArgumentException("Build intersects unbreakable block at " + pos.toShortString());
            cells.add(new BlueprintProject.Cell(pos.getX(),pos.getY(),pos.getZ(),
                    BlueprintDesign.stateString(before), BlueprintDesign.stateString(entry.getValue())));
        }
        BlueprintProject next = new BlueprintProject(UUID.randomUUID().toString(), worldId,
                original.id(),original.sha256(),dimension,origin.getX(),origin.getY(),origin.getZ(),rotation,"",cells);
        save(new Saved(1,saved.revision()+1,worldId,next,false,false)); return next;
    }

    /** Exact reviewed columns only: shallow dry/source-water gaps over stable dry ground. */
    static Set<BlockPos> addFootings(Map<BlockPos,BlockState> desired,BlockPos origin,
            java.util.function.Function<BlockPos,BlockState> read,
            java.util.function.Predicate<BlockPos> stableGround) {
        Set<BlockPos> footings=new LinkedHashSet<>();
        List<BlockPos> bottoms = desired.entrySet().stream()
                .filter(e -> !e.getValue().isAir() && !desired.containsKey(e.getKey().down()))
                .map(Map.Entry::getKey).toList();
        for (BlockPos bottom : bottoms) {
            if (bottom.getY() != origin.getY()) continue;
            BlockPos below = bottom.down(); int depth = 0;
            while (true) {
                BlockState ground=read.apply(below);
                if(!ground.getFluidState().isEmpty()&&!stillWater(ground))
                    throw footingFailure(bottom,below,"lava, flowing water or waterlogged footing (observed "
                            +BlueprintDesign.stateString(ground)+")");
                // Vanilla flowerbeds are not globally replaceable: their placement
                // rule preserves/merges the petals. That does not make them ground.
                // The builder may clear this exact reviewed plant cell, then put
                // a footing on the stable substrate below. Save it in the same
                // immutable preview; never clear neighboring vegetation implicitly.
                if(!footingClearance(ground)) {
                    if(!stableGround.test(below))throw footingFailure(bottom,below,"unstable/non-full ground support (observed "
                            +BlueprintDesign.stateString(ground)+")");
                    break;
                }
                if (++depth > 4) throw footingFailure(bottom,below,"ground is more than 4 blocks below the selected floor");
                desired.put(below, Blocks.COBBLESTONE.getDefaultState());footings.add(below);
                below = below.down();
            }
        }
        return Set.copyOf(footings);
    }
    private static boolean stillWater(BlockState state) {
        return state.isOf(Blocks.WATER)&&state.getFluidState().isStill();
    }
    static boolean footingClearance(BlockState state) {
        return state.isReplaceable() || state.getBlock() instanceof net.minecraft.block.PlantBlock;
    }
    static boolean stableFootingGround(BlockState state,net.minecraft.world.BlockView world,BlockPos pos) {
        return state.getFluidState().isEmpty()&&!state.hasBlockEntity()
                &&!(state.getBlock() instanceof net.minecraft.block.FallingBlock)
                &&!(state.getBlock() instanceof net.minecraft.block.LeavesBlock)
                &&state.isSideSolidFullSquare(world,pos,net.minecraft.util.math.Direction.UP);
    }
    private static IllegalArgumentException footingFailure(BlockPos floor,BlockPos blocked,String reason) {
        return new IllegalArgumentException("Cannot select this site: foundation below floor "+floor.toShortString()+" blocked at "
                +blocked.toShortString()+": "+reason+". Supports are limited to 4 dry/still-water cells over stable dry ground. "
                +"Prepare this exact column or stand at a new floor anchor yourself and /e build here; "
                +"selection uses your position, not Entity's. The site was not moved");
    }
    public BlueprintProject require(String projectId, String digest) {
        BlueprintProject p = project();
        if (p == null || !p.projectId().equals(projectId) || !p.digest().equals(digest))
            throw new IllegalArgumentException("Build preview changed; use /e build show then confirm it again");
        return p;
    }
    public BlueprintProject confirm(MinecraftClient client, String id, String digest,
            boolean gather) throws IOException {
        BlueprintProject p = require(id,digest);
        // Reviewing the same already-confirmed house renews consent; it is not
        // a fresh site preview. Ordinary player edits use the existing repair
        // rules, while foreign containers, fluids and unavailable cells remain fenced.
        validateSite(client,p,supports,confirmed());
        save(new Saved(1,saved.revision()+1,worldId,p,true,gather)); return p;
    }
    /** Reconcile durable consent without treating unavailable world observations as changed cells. */
    public BlueprintProject resume(String owner,long policyRevision,String policyDigest) throws IOException {
        if(!confirmed()||project()==null)throw new IllegalArgumentException("Preview is not confirmed; /e build confirm first");
        requireAuthority(owner,policyRevision,policyDigest);
        // Released schema-1 projects predate authority metadata. Only the authenticated
        // primary-owner resume path may bind that existing confirmation once.
        if(saved.owner()==null)bindAuthority(owner,policyRevision,policyDigest);
        return project();
    }
    public void bindAuthority(String owner,long policyRevision,String policyDigest) throws IOException {
        if(owner==null||owner.isBlank()||policyDigest==null||policyDigest.isBlank())
            throw new IllegalArgumentException("Build requires authenticated owner and property policy");
        save(new Saved(1,saved.revision()+1,worldId,project(),confirmed(),gather(),owner,policyRevision,policyDigest));
    }
    private void requireAuthority(String owner,long policyRevision,String policyDigest) {
        if(owner==null||owner.isBlank()||policyDigest==null||policyDigest.isBlank())
            throw new IllegalArgumentException("Build requires authenticated owner and property policy");
        if(saved.owner()!=null&&(!saved.owner().equalsIgnoreCase(owner)||saved.policyRevision()!=policyRevision
                ||!Objects.equals(saved.policyDigest(),policyDigest)))
            throw new IllegalArgumentException("Build owner or property policy changed; show and confirm the same project again");
    }
    public void clear() throws IOException { save(new Saved(1,saved.revision()+1,worldId,null,false,false)); }
    public static void validateUnchanged(MinecraftClient client, BlueprintProject p) {
        validateUnchanged(client,p,null);
    }
    public static void validateUnchanged(MinecraftClient client, BlueprintProject p,BlueprintSupportLedger supports) {
        validateSite(client,p,supports,false);
    }
    public static void validateConfirmed(MinecraftClient client,BlueprintProject p,BlueprintSupportLedger supports) {
        validateSite(client,p,supports,true);
    }
    private static void validateSite(MinecraftClient client,BlueprintProject p,BlueprintSupportLedger supports,boolean repair) {
        if (client.world == null || !client.world.getRegistryKey().getValue().toString().equals(p.dimension()))
            throw new IllegalArgumentException("Entity must be in the project's dimension");
        if(supports!=null)supports.reconcile(p,client);
        Set<BlockPos> footprint=p.desired().keySet();
        for (BlueprintProject.Cell cell : p.cells()) {
            BlockState observed=state(client,cell.pos());
            boolean owned=supports!=null&&BlueprintSupportLedger.eligible(cell)
                    &&supports.owns(p,cell.pos(),BlueprintDesign.stateString(observed));
            validateConfirmationObserved(cell,observed,owned,footprint,pos->state(client,pos),repair);
        }
    }
    static void validateConfirmationObserved(BlueprintProject.Cell cell,BlockState observed,boolean ownedSupport,
            Set<BlockPos> footprint,java.util.function.Function<BlockPos,BlockState> read,boolean previouslyConfirmed) {
        if(previouslyConfirmed)validateConfirmedObserved(cell,observed,ownedSupport,footprint,read);
        else validateObserved(cell,observed,ownedSupport);
    }
    static void validateConfirmedObserved(BlueprintProject.Cell cell,BlockState observed,boolean ownedSupport) {
        if(acceptedIntermediate(cell,observed)||ownedSupport||repairable(observed))return;
        throw new SiteChangedException("Confirmed build cannot safely repair " + cell.pos().toShortString()
                + " (observed="+BlueprintDesign.stateString(observed)+"); remove the foreign container, fluid or unbreakable obstruction first");
    }
    static void validateConfirmedObserved(BlueprintProject.Cell cell,BlockState observed,boolean ownedSupport,
            Set<BlockPos> footprint,java.util.function.Function<BlockPos,BlockState> read) {
        if(observed.getBlock() instanceof net.minecraft.block.BedBlock
                && !BlueprintTerrainStates.matches(BlueprintDesign.parseState(cell.state()),observed)) {
            if(repairableBed(cell.pos(),observed,footprint,read))return;
            throw new SiteChangedException("Confirmed build cannot remove occupied bed or affect a bed half outside its exact footprint at "
                    +cell.pos().toShortString());
        }
        validateConfirmedObserved(cell,observed,ownedSupport);
    }
    /** Beds carry color/render state, not stored inventory or editable player data. */
    static boolean dataBearing(BlockState state) {
        return state.hasBlockEntity()&&!(state.getBlock() instanceof net.minecraft.block.BedBlock);
    }
    public static Map<Integer,dev.entity.client.baritone.NativePropertyCosts.Coordinate> bedPartnerOffsets() {
        Map<Integer,dev.entity.client.baritone.NativePropertyCosts.Coordinate> offsets=new HashMap<>();
        for(var block:net.minecraft.registry.Registries.BLOCK) if(block instanceof net.minecraft.block.BedBlock)
            for(var state:block.getStateManager().getStates()) {
                if(state.get(net.minecraft.state.property.Properties.OCCUPIED))continue;
                var direction=state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
                if(state.get(net.minecraft.state.property.Properties.BED_PART)==net.minecraft.block.enums.BedPart.HEAD)
                    direction=direction.getOpposite();
                offsets.put(net.minecraft.block.Block.getRawIdFromState(state),
                        new dev.entity.client.baritone.NativePropertyCosts.Coordinate(direction.getOffsetX(),0,direction.getOffsetZ()));
            }
        return Map.copyOf(offsets);
    }
    public static boolean repairableBed(BlockPos pos,BlockState observed,Set<BlockPos> footprint,
            java.util.function.Function<BlockPos,BlockState> read) {
        if(!(observed.getBlock() instanceof net.minecraft.block.BedBlock)
                ||observed.get(net.minecraft.state.property.Properties.OCCUPIED)||!footprint.contains(pos))return false;
        var facing=observed.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
        var part=observed.get(net.minecraft.state.property.Properties.BED_PART);
        BlockPos partner=pos.offset(part==net.minecraft.block.enums.BedPart.FOOT?facing:facing.getOpposite());
        if(!footprint.contains(partner))return false;
        BlockState other=read.apply(partner);
        return other!=null&&!other.isOf(Blocks.VOID_AIR)
                &&(!(other.getBlock() instanceof net.minecraft.block.BedBlock)
                    ||!other.get(net.minecraft.state.property.Properties.OCCUPIED));
    }
    /** Only the already-confirmed exact cell receives repair authority; never containers or unknown chunks. */
    static boolean repairable(BlockState observed) {
        return observed!=null&&!observed.isOf(Blocks.VOID_AIR)&&!observed.hasBlockEntity()
                &&observed.getFluidState().isEmpty()&&observed.getHardness(null,BlockPos.ORIGIN)>=0;
    }
    /** Expected consent refusal, not a runtime fault eligible for automatic tick retry. */
    public static final class SiteChangedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public SiteChangedException(String message) { super(message); }
    }
    static void validateObserved(BlueprintProject.Cell cell,BlockState observed,boolean ownedSupport) {
        if (!acceptedIntermediate(cell,observed) && !ownedSupport)
            throw new SiteChangedException("World changed since preview at " + cell.pos().toShortString()
                    + " (observed=" + BlueprintDesign.stateString(observed) + ", wanted=" + cell.state()
                    + "); construction is blocked. Stand at the intended floor anchor yourself, "
                    + "/e build here, then review and confirm the new preview");
    }
    static boolean acceptedIntermediate(BlueprintProject.Cell cell,BlockState observed) {
        String current=BlueprintDesign.stateString(observed);
        if (current.equals(cell.before()) || current.equals(cell.state())
                || current.equals("minecraft:air") && !cell.before().equals("minecraft:air")) return true;
        BlockState desired=BlueprintDesign.parseState(cell.state());
        if (BlueprintTerrainStates.matches(desired,observed)
                || BlueprintTerrainStates.matches(BlueprintDesign.parseState(cell.before()),observed)) return true;
        if(BlueprintDoorMaterials.connectedIntermediate(observed,desired))return true;
        // Reversible passage can open a door that this very build just placed.
        // Opening changes use state, not the consented structure. Do not ignore
        // hinge, half or power; gates retain their physical axis through vanilla use.
        boolean portal=desired.getBlock() instanceof net.minecraft.block.DoorBlock
                || desired.getBlock() instanceof net.minecraft.block.TrapdoorBlock
                || desired.getBlock() instanceof net.minecraft.block.FenceGateBlock;
        if(!portal || observed.getBlock()!=desired.getBlock())return false;
        var closed=observed.with(net.minecraft.state.property.Properties.OPEN,
                desired.get(net.minecraft.state.property.Properties.OPEN));
        return BlueprintTerrainStates.matches(desired,closed)
                || BlueprintDoorMaterials.connectedIntermediate(closed,desired);
    }
    public static BlockState state(MinecraftClient client, BlockPos pos) {
        if (!loaded(client,pos))
            throw new IllegalArgumentException("Bring Entity near the full preview so its chunks can be inspected");
        if (pos.getY() < client.world.getBottomY() || pos.getY() >= client.world.getTopYInclusive()+1)
            throw new IllegalArgumentException("Blueprint exceeds world build height");
        return client.world.getBlockState(pos);
    }
    public static boolean loaded(MinecraftClient client,BlockPos pos) {
        // ClientWorld.isChunkLoaded(int,int) is unconditionally true in 1.21.8.
        // getChunk(...,false) asks the client manager for a real received chunk,
        // without manufacturing its EmptyChunk/VOID_AIR fallback.
        return client!=null&&client.world!=null&&client.world.getChunkManager().getChunk(
                pos.getX()>>4,pos.getZ()>>4,net.minecraft.world.chunk.ChunkStatus.FULL,false)!=null;
    }
    public static boolean siteLoaded(MinecraftClient client,BlueprintProject project) {
        return client!=null&&client.world!=null
                &&client.world.getRegistryKey().getValue().toString().equals(project.dimension())
                &&siteLoaded(project,pos->loaded(client,pos));
    }
    static boolean siteLoaded(BlueprintProject project,java.util.function.Predicate<BlockPos> loaded) {
        return project.cells().stream().allMatch(cell->loaded.test(cell.pos()));
    }
    private AtomicStoreRecovery.Decoded<Saved> decode(byte[] content, Path source) throws IOException {
        Saved value = JSON.fromJson(new String(content,StandardCharsets.UTF_8),Saved.class);
        if (value == null || value.schema()!=1 || !worldId.equals(value.worldId()))
            throw new IllegalArgumentException("Wrong blueprint schema/world");
        if (value.confirmed() && value.project()==null) throw new IllegalArgumentException("Missing project");
        if(value.project()!=null&&!worldId.equals(value.project().worldId()))
            throw new IllegalArgumentException("Project belongs to another world");
        if(value.owner()!=null&&(value.owner().isBlank()||value.policyDigest()==null||value.policyDigest().isBlank()
                ||value.policyRevision()<0))throw new IllegalArgumentException("Invalid saved blueprint authority");
        return new AtomicStoreRecovery.Decoded<>(value,value.revision(),value.schema());
    }
    private Path temporary() { return path.resolveSibling(path.getFileName()+".tmp"); }
    private void save(Saved value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(temporary(),JSON.toJson(value),StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(temporary(),StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try { Files.move(temporary(),path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary(),path,StandardCopyOption.REPLACE_EXISTING);
        }
        saved=value;
    }
}
