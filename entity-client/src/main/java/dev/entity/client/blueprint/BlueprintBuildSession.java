package dev.entity.client.blueprint;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.schematic.ISchematic;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Native construction plus physical completion, without a second placement/path controller. */
public final class BlueprintBuildSession {
    public record Observation(int matched,int total,int permanentMatched,boolean complete,boolean paused,String detail,
                              int verifiedWorkUnits) {
        public Observation(int matched,int total,int permanentMatched,boolean complete,boolean paused,String detail) {
            this(matched,total,permanentMatched,complete,paused,detail,permanentMatched);
        }
    }
    private final MinecraftClient client;
    private final IBaritone baritone;
    private final BlueprintProject project;
    private final Runnable validateSite;
    private final Settings settings;
    private final Map<BlockPos,BlockState> desired;
    private final Map<BlockPos,BlueprintProject.Cell> consented;
    private final List<Map.Entry<BlockPos,BlockState>> observedOrder;
    private final Map<Settings.Setting<?>,Object> previous=new LinkedHashMap<>();
    private int stableComplete;
    private BlueprintSupportLedger supports;
    private volatile boolean clearingSupports;
    private final Map<BlockPos,BlockPos> attachmentSupportCandidates=new HashMap<>();
    private volatile Map<BlockPos,BlockState> requiredSupports=Map.of();
    private Optional<Map<String,Integer>> layerMaterials=Optional.empty();
    /** Observation credit belongs to the project, not one replaceable body lease. */
    public static final class WorkProgress {
        private Map<BlockPos,Boolean> previousStructure=Map.of();
        private Map<BlockPos,BlockState> previousObserved=Map.of();
        private final Set<BlockPos> creditedCleanup=new HashSet<>();
        private int verifiedWork;
    }
    private final WorkProgress work;
    private long worldRevision;
    private long refreshedRevision;
    private boolean finalUseStateReady;

    public Optional<Map<String,Integer>> layerMaterials() { return layerMaterials; }
    public BlueprintBuildSession(MinecraftClient client,IBaritone baritone,BlueprintProject project) {
        this(client,baritone,project,()->BlueprintProjects.validateConfirmed(client,project,null),BaritoneAPI.getSettings());
    }
    public BlueprintBuildSession(MinecraftClient client,IBaritone baritone,BlueprintProject project,BlueprintSupportLedger supports) {
        this(client,baritone,project,()->BlueprintProjects.validateConfirmed(client,project,supports),BaritoneAPI.getSettings(),supports);
    }
    public BlueprintBuildSession(MinecraftClient client,IBaritone baritone,BlueprintProject project,
            BlueprintSupportLedger supports,WorkProgress work) {
        this(client,baritone,project,()->BlueprintProjects.validateConfirmed(client,project,supports),BaritoneAPI.getSettings(),supports,work);
    }
    BlueprintBuildSession(MinecraftClient client,IBaritone baritone,BlueprintProject project,Runnable validateSite,Settings settings) {
        this(client,baritone,project,validateSite,settings,null);
    }
    BlueprintBuildSession(MinecraftClient client,IBaritone baritone,BlueprintProject project,Runnable validateSite,Settings settings,BlueprintSupportLedger supports) {
        this(client,baritone,project,validateSite,settings,supports,new WorkProgress());
    }
    BlueprintBuildSession(MinecraftClient client,IBaritone baritone,BlueprintProject project,Runnable validateSite,Settings settings,
            BlueprintSupportLedger supports,WorkProgress work) {
        this.client=client; this.baritone=baritone; this.project=project;
        this.work=Objects.requireNonNull(work);
        desired=BlueprintDoorMaterials.effectiveGateConnections(project.desired());
        long reconciled=project.desired().entrySet().stream().filter(e->!e.getValue().equals(desired.get(e.getKey()))).count();
        if(reconciled>0)org.slf4j.LoggerFactory.getLogger("Entity2Blueprint").info(
                "Reconciled {} legacy gate in_wall properties from exact planned walls; source/project identity, blocks and footprint unchanged",reconciled);
        consented=project.cells().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                BlueprintProject.Cell::pos,java.util.function.Function.identity()));
        observedOrder=desired.entrySet().stream().sorted(Comparator
                .comparingInt((Map.Entry<BlockPos,BlockState> e)->e.getKey().getY())
                .thenComparingInt(e->e.getKey().getZ()).thenComparingInt(e->e.getKey().getX())).toList();
        this.validateSite=validateSite;
        this.settings=settings;
        this.supports=supports;
        for(var cell:project.cells()) if(BlueprintSupportLedger.eligible(cell)) {
            BlockPos above=cell.pos().up();
            BlockState target=desired.get(above);
            if(target!=null&&(target.getBlock() instanceof net.minecraft.block.StairsBlock
                    ||target.getBlock() instanceof net.minecraft.block.SlabBlock
                        &&target.get(net.minecraft.state.property.Properties.SLAB_TYPE)==net.minecraft.block.enums.SlabType.BOTTOM
                    ||target.getBlock() instanceof net.minecraft.block.PillarBlock
                        &&target.get(net.minecraft.state.property.Properties.AXIS)==net.minecraft.util.math.Direction.Axis.Y))
                attachmentSupportCandidates.put(cell.pos(),above);
        }
    }
    public void start() {
        validateSite.run();
        if(client!=null&&client.world!=null)refreshSupportTargets(client.world::getBlockState,
                p->BlueprintProjects.loaded(client,p),carriedSupport());
        var s=settings;
        change(s.allowBreak,true); change(s.allowPlace,true); change(s.allowInventory,true);
        // A reviewed AIR cell can be below our feet beside a completed wall.
        // Native still excludes our own support and owns the real break ray.
        change(s.breakFromAbove,true);
        change(s.autoTool,true); change(s.buildIgnoreExisting,false);
        change(s.acceptableThrowawayItems,List.of(net.minecraft.item.Items.DIRT,net.minecraft.item.Items.COBBLESTONE));
        // Finish lower walls before closing access with a roof. This is native
        // BuilderProcess prefix ordering, not another construction controller.
        change(s.buildInLayers,true); change(s.layerOrder,false); change(s.layerHeight,1);
        // Native distanceTrim keeps any nonempty nearby subset, even when every
        // member awaits a farther attachment/shape prerequisite. Preserve this
        // bounded project's pending layer so deferred corners cannot erase its
        // executable work merely because the player approaches from that side.
        change(s.distanceTrim,false);
        change(s.startAtLayer,0); change(s.buildOnlySelection,false);
        change(s.buildRepeat,new BlockPos(0,0,0)); change(s.buildRepeatCount,1);
        change(s.schematicOrientationX,false); change(s.schematicOrientationY,false);
        change(s.schematicOrientationZ,false);
        change(s.buildIgnoreBlocks,List.of()); change(s.buildSkipBlocks,List.of());
        change(s.buildValidSubstitutes,Map.of()); change(s.buildSubstitutes,Map.of());
        change(s.okIfAir,List.of()); change(s.buildIgnoreDirection,false);
        // Native construction must not dismantle a correctly placed door when
        // passage opens it. Existing passage restores it; final observation
        // below still requires the exact desired (closed) state.
        change(s.buildIgnoreProperties,List.of("open")); change(s.skipFailedLayers,false);
        change(s.buildRepeatSneaky,false); change(s.mapArtMode,false); change(s.okIfWater,false);
        change(s.buildSchematicRotation,net.minecraft.util.BlockRotation.NONE);
        change(s.buildSchematicMirror,net.minecraft.util.BlockMirror.NONE);
        submitNativeBuild();
    }
    private void submitNativeBuild() {
        BlockPos min=project.origin(),max=project.maximum();
        ISchematic schematic=new ISchematic() {
            public int widthX(){return max.getX()-min.getX()+1;}
            public int heightY(){return max.getY()-min.getY()+1;}
            public int lengthZ(){return max.getZ()-min.getZ()+1;}
            public boolean inSchematic(int x,int y,int z,BlockState current){return desired.containsKey(min.add(x,y,z));}
            public BlockState desiredState(int x,int y,int z,BlockState current,List<BlockState> approxPlaceable){
                return nativeDesiredState(min.add(x,y,z),current);
            }
        };
        BlueprintDoorMaterials.open(baritone.getBuilderProcess(), desired);
        baritone.getBuilderProcess().build("entity2-blueprint-"+project.projectId(),schematic,min);
    }
    BlockState nativeDesiredState(BlockPos pos,BlockState current) {
        BlockState target=desired.getOrDefault(pos,current);
        // Native Builder breaks mismatches before placing. A recorded scaffold
        // is useful construction until the permanent structure exists, not yet
        // a final-AIR cleanup target. Never request rebuilding a missing support.
        if(!clearingSupports&&target.isAir()&&supports!=null
                &&supports.ownsObserved(project,pos,BlueprintDesign.stateString(current)))return current;
        if(!clearingSupports&&current.isAir()&&requiredSupports.containsKey(pos))return requiredSupports.get(pos);
        return BlueprintTerrainStates.matches(target,current)?current:BlueprintTerrainStates.placementState(target);
    }
    private BlockState carriedSupport() {
        boolean cobblestone=false;
        if(client!=null&&client.player!=null) for(var item:client.player.getInventory().getMainStacks()) {
            if(item.isOf(net.minecraft.item.Items.DIRT))return net.minecraft.block.Blocks.DIRT.getDefaultState();
            if(item.isOf(net.minecraft.item.Items.COBBLESTONE))cobblestone=true;
        }
        return (cobblestone?net.minecraft.block.Blocks.COBBLESTONE:net.minecraft.block.Blocks.DIRT).getDefaultState();
    }
    void refreshSupportTargets(java.util.function.Function<BlockPos,BlockState> read,
                               java.util.function.Predicate<BlockPos> loaded,BlockState material) {
        Map<BlockPos,BlockState> next=new HashMap<>();
        if(!clearingSupports&&supports!=null) for(var candidate:attachmentSupportCandidates.entrySet()) {
            if(loaded.test(candidate.getValue())&&!desired.get(candidate.getValue()).equals(read.apply(candidate.getValue())))
                next.put(candidate.getKey(),material);
        }
        // Reaching an AIR cell while sneaking on a neighboring rim does not
        // create a floor. Ask the same native builder to place the actual floor
        // first; original-AIR consent and its normal placement receipt still gate it.
        requiredSupports=Map.copyOf(next);
    }
    public Observation observe() {
        if(supports!=null){
            supports.reconcile(project,client);
            if(!supports.failure().isEmpty())return new Observation(0,desired.size(),0,false,true,supports.failure());
        }
        return observe(client.world::getBlockState,p->BlueprintProjects.loaded(client,p));
    }
    Observation observe(java.util.function.Function<BlockPos,BlockState> read,java.util.function.Predicate<BlockPos> loaded) {
        refreshSupportTargets(read,loaded,carriedSupport());
        layerMaterials=observeLayerMaterials(read,loaded);
        int matched=0,permanentMatched=0,permanentTotal=0,permanentReady=0;
        Map<BlockPos,Boolean> structure=new HashMap<>();
        Map<BlockPos,BlockState> observedStates=new HashMap<>();
        boolean damage=false,changedPending=false;
        List<String> pending=new ArrayList<>();
        for (var cell:observedOrder) {
            if (!loaded.test(cell.getKey())) {
                stableComplete=0;
                return new Observation(matched,desired.size(),permanentMatched,false,false,"Native builder is loading the project site");
            }
            BlockState observed=read.apply(cell.getKey());
            observedStates.put(cell.getKey(),observed);
            BlueprintProjects.validateConfirmedObserved(consented.get(cell.getKey()),observed,
                    supports!=null&&BlueprintSupportLedger.eligible(consented.get(cell.getKey()))
                            &&supports.owns(project,cell.getKey(),BlueprintDesign.stateString(observed)),desired.keySet(),read);
            if(!cell.getValue().isAir()) {
                permanentTotal++;
                // Owned scaffolding can change pane/fence/wall connections until
                // it is removed. Start cleanup once the actual structure exists;
                // final progress and completion below still require exact states.
                boolean ready=structureReady(observed,cell.getValue());
                structure.put(cell.getKey(),ready);
                if(ready)permanentReady++;
                if(Boolean.TRUE.equals(work.previousStructure.get(cell.getKey()))&&!ready)damage=true;
                if(!ready&&work.previousObserved.containsKey(cell.getKey())&&!observed.equals(work.previousObserved.get(cell.getKey())))
                    changedPending=true;
            }
            else if(!observed.isAir()&&work.previousObserved.containsKey(cell.getKey())
                    &&!observed.equals(work.previousObserved.get(cell.getKey()))
                    &&(supports==null||!supports.ownsObserved(project,cell.getKey(),BlueprintDesign.stateString(observed))))
                changedPending=true;
            if (BlueprintTerrainStates.matches(cell.getValue(),observed)) {
                matched++;
                if(!cell.getValue().isAir())permanentMatched++;
            }
            else if(pending.size()<4) pending.add(cell.getKey().toShortString()+" observed="
                    +BlueprintDesign.stateString(observed)+" wanted="+BlueprintDesign.stateString(cell.getValue()));
        }
        // Observe transitions, not an absolute high-water mark: repaired blast
        // damage is real work even below the old count. OPEN/connection changes
        // remain structurally ready and therefore cannot manufacture repair credit.
        if(work.previousObserved.isEmpty())work.verifiedWork=permanentReady;
        else for(var cell:structure.entrySet())
            if(cell.getValue()&&!work.previousStructure.getOrDefault(cell.getKey(),false))work.verifiedWork++;
        work.previousStructure=Map.copyOf(structure);
        work.previousObserved=Map.copyOf(observedStates);
        if(changedPending)worldRevision++;
        if(damage)clearingSupports=false;
        if(!clearingSupports&&permanentReady==permanentTotal) {
            clearingSupports=true;
            // Refresh the same native job once. Its cached layer may otherwise
            // report Done before seeing final AIR after the last roof placement.
            if(matched!=desired.size()){submitNativeBuild();refreshedRevision=worldRevision;}
        }
        else if(matched!=desired.size()&&(damage||(baritone.getBuilderProcess().isPaused()
                ||!baritone.getBuilderProcess().isActive())&&worldRevision!=refreshedRevision)) {
            // A changed site invalidates native cached layer work once. An
            // unchanged pause is still a concrete material/access prerequisite.
            refreshSupportTargets(read,loaded,carriedSupport());
            submitNativeBuild();refreshedRevision=worldRevision;
        }
        if(clearingSupports) for(var cell:observedOrder)
            if(cell.getValue().isAir()&&BlueprintTerrainStates.matches(cell.getValue(),observedStates.get(cell.getKey()))
                    &&work.creditedCleanup.add(cell.getKey()))work.verifiedWork++;
        finalUseStateReady=clearingSupports&&observedOrder.stream().allMatch(cell->
                BlueprintTerrainStates.matches(cell.getValue(),observedStates.get(cell.getKey()))
                        ||sameOpenPortal(observedStates.get(cell.getKey()),cell.getValue()));
        stableComplete=matched==desired.size()?stableComplete+1:0;
        boolean paused=baritone.getBuilderProcess().isPaused();
        boolean inactive=!baritone.getBuilderProcess().isActive();
        return new Observation(matched,desired.size(),permanentMatched,stableComplete>=5,
                (paused||inactive)&&stableComplete==0,
                matched+"/"+desired.size()+" blueprint cells physically match"
                        +(clearingSupports?"; final support cleanup":"; permanent structure "+permanentMatched+"/"+permanentTotal)
                        +(paused?"; native builder needs materials or access":"")
                        +(!pending.isEmpty()?"; lowest pending "+String.join("; ",pending):""),
                work.verifiedWork);
    }

    static boolean structureReady(BlockState actual,BlockState wanted) {
        if(wanted.getBlock() instanceof net.minecraft.block.BedBlock&&actual.getBlock()==wanted.getBlock())
            actual=actual.with(net.minecraft.state.property.Properties.OCCUPIED,wanted.get(net.minecraft.state.property.Properties.OCCUPIED));
        if(actual.getBlock()==wanted.getBlock()&&(wanted.getBlock() instanceof net.minecraft.block.DoorBlock
                ||wanted.getBlock() instanceof net.minecraft.block.TrapdoorBlock
                ||wanted.getBlock() instanceof net.minecraft.block.FenceGateBlock))
            actual=actual.with(net.minecraft.state.property.Properties.OPEN,wanted.get(net.minecraft.state.property.Properties.OPEN));
        return BlueprintTerrainStates.matches(wanted,actual)
                ||BlueprintDoorMaterials.connectedIntermediate(actual,wanted)||sameOpenPortal(actual,wanted);
    }

    /** Same bottom-up prefix and projected support states submitted to the native builder. */
    Optional<Map<String,Integer>> observeLayerMaterials(java.util.function.Function<BlockPos,BlockState> read,
            java.util.function.Predicate<BlockPos> loaded) {
        BlockPos min=project.origin(),max=project.maximum();
        Map<BlockPos,BlockState> layer=new LinkedHashMap<>();
        Integer nextY=null;
        for(var cell:observedOrder) {
            BlockPos pos=cell.getKey();
            if(nextY!=null&&pos.getY()>nextY) break;
            if(!loaded.test(pos)) return Optional.empty();
            BlockState actual=read.apply(pos),wanted=nativeDesiredState(pos,actual);
            // Native buildIgnoreProperties=[open] leaves passage restoration to
            // its existing owner; reopening that cell is not a material shortage.
            if(wanted.isAir() || structureReady(actual,wanted)) continue;
            nextY=pos.getY();
            layer.put(pos.subtract(min),wanted);
        }
        if(layer.isEmpty()) return Optional.of(Map.of());
        return Optional.of(new BlueprintDesign("native-layer",project.designId(),"native next layer",
                max.getX()-min.getX()+1,max.getY()-min.getY()+1,max.getZ()-min.getZ()+1,layer).materials());
    }
    /** Exact structural match needing only its requested use-state; not a material shortage. */
    public Optional<Map.Entry<BlockPos,BlockState>> pendingPortal() {
        // Final use-state waits for construction/owned support cleanup, avoiding
        // a close/open fight with an active native doorway traversal.
        return finalUseStateReady?pendingPortal(client.world::getBlockState):Optional.empty();
    }
    Optional<Map.Entry<BlockPos,BlockState>> pendingPortal(java.util.function.Function<BlockPos,BlockState> read) {
        return observedOrder.stream().filter(cell -> {
            BlockState actual=read.apply(cell.getKey());
            BlockState wanted=cell.getValue();
            return wanted.contains(net.minecraft.state.property.Properties.OPEN)
                    && !BlueprintTerrainStates.matches(wanted,actual) && sameOpenPortal(actual,wanted);
        }).findFirst();
    }
    public static boolean sameOpenPortal(BlockState actual,BlockState wanted) {
        boolean portal=wanted.getBlock() instanceof net.minecraft.block.DoorBlock
                || wanted.getBlock() instanceof net.minecraft.block.TrapdoorBlock
                || wanted.getBlock() instanceof net.minecraft.block.FenceGateBlock;
        return portal&&actual.getBlock()==wanted.getBlock()
                &&BlueprintTerrainStates.matches(wanted,actual.with(net.minecraft.state.property.Properties.OPEN,
                        wanted.get(net.minecraft.state.property.Properties.OPEN)));
    }
    private <T> void change(Settings.Setting<T> setting,T value) {
        previous.putIfAbsent(setting,setting.value); setting.value=value;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public void close() {
        if(!previous.isEmpty()) {
            // cancelEverything can retain an unsafe bridge. It must not outlive
            // the construction settings/scope and place its held stair as a step.
            try {baritone.getPathingBehavior().forceCancel();}
            finally {baritone.getInputOverrideHandler().clearAllKeys();}
        }
        BlueprintDoorMaterials.close(baritone.getBuilderProcess());
        for(var entry:previous.entrySet()) ((Settings.Setting)entry.getKey()).value=entry.getValue();
        previous.clear();
    }
}
