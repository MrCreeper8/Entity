package dev.entity.client.blueprint;

import com.google.gson.Gson;
import dev.entity.core.persistence.AtomicStoreRecovery;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exact native-construction support receipts, separate from permanent design and route supports. */
public final class BlueprintSupportLedger {
    private static final Gson JSON=new Gson();
    public record Receipt(int x,int y,int z,String state,long admittedAt,boolean observed) {
        BlockPos pos(){return new BlockPos(x,y,z);}
    }
    public record Saved(int schema,long revision,String worldId,String projectId,String digest,List<Receipt> receipts) {}
    private final Path path;
    private final String world;
    private Saved saved;
    private String failure="";
    public BlueprintSupportLedger(Path directory,String world) throws IOException {
        this.path=directory==null?null:directory.resolve("blueprint-supports.json");this.world=world;
        saved=path==null?empty():AtomicStoreRecovery.load(path,temporary(),"blueprint supports",this::empty,(bytes,source)->{
            Saved value=JSON.fromJson(new String(bytes,StandardCharsets.UTF_8),Saved.class);
            if(value==null||value.schema()!=1||!world.equals(value.worldId())||value.receipts()==null
                    ||value.receipts().size()>BlueprintDesign.MAX_CELLS) throw new IOException("Invalid blueprint support ledger");
            Set<BlockPos> positions=new HashSet<>();
            for(var receipt:value.receipts()) if(!material(receipt.state())||!positions.add(receipt.pos()))
                throw new IOException("Invalid or duplicate support receipt");
            return new AtomicStoreRecovery.Decoded<>(value,value.revision(),value.schema());
        });
    }
    static BlueprintSupportLedger memory(String world) {
        try{return new BlueprintSupportLedger(null,world);}catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    private Saved empty(){return new Saved(1,0,world,"","",List.of());}
    public static boolean material(String state){return "minecraft:dirt".equals(state)||"minecraft:cobblestone".equals(state);}
    public static boolean eligible(BlueprintProject.Cell cell) {
        return cell!=null && air(cell.before()) && air(cell.state());
    }
    private static boolean air(String state){return Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(state);}
    private boolean matches(BlueprintProject p){return world.equals(p.worldId())&&saved.projectId().equals(p.projectId())&&saved.digest().equals(p.digest());}
    public synchronized long revision(){return saved.revision();}
    public synchronized String failure(){return failure;}
    public synchronized boolean owns(BlueprintProject project,BlockPos pos,String state) {
        return failure.isEmpty()&&matches(project)&&saved.receipts().stream().anyMatch(r->r.pos().equals(pos)&&observedMatches(r,state));
    }
    private static boolean observedMatches(Receipt receipt,String observed) {
        return receipt.state().equals(observed) || receipt.observed() && BlueprintTerrainStates.matches(
                BlueprintDesign.parseState(receipt.state()),BlueprintDesign.parseState(observed));
    }
    public synchronized boolean ownsObserved(BlueprintProject project,BlockPos pos,String state) {
        return owns(project,pos,state)&&saved.receipts().stream().anyMatch(r->r.pos().equals(pos)&&r.observed());
    }
    public synchronized Map<BlockPos,String> states(BlueprintProject project) {
        Map<BlockPos,String> result=new HashMap<>();
        if(failure.isEmpty()&&matches(project)) for(var r:saved.receipts()) result.put(r.pos(),r.state());
        return Map.copyOf(result);
    }
    public synchronized boolean admit(BlueprintProject project,BlueprintProject.Cell cell,String state,long now) {
        if(!world.equals(project.worldId())||!eligible(cell)||!project.cells().contains(cell)||!material(state))return false;
        if(owns(project,cell.pos(),state))return true;
        List<Receipt> next=matches(project)?new ArrayList<>(saved.receipts()):new ArrayList<>();
        next.removeIf(r->r.pos().equals(cell.pos()));
        next.add(new Receipt(cell.x(),cell.y(),cell.z(),state,now,false));
        return save(new Saved(1,saved.revision()+1,world,project.projectId(),project.digest(),List.copyOf(next)));
    }
    public void reconcile(BlueprintProject project,MinecraftClient client) {
        if(client.world==null||!project.dimension().equals(client.world.getRegistryKey().getValue().toString()))return;
        Map<BlockPos,String> observed=new HashMap<>();
        for(var pos:states(project).keySet()) if(BlueprintProjects.loaded(client,pos))
            observed.put(pos,BlueprintDesign.stateString(client.world.getBlockState(pos)));
        reconcile(project,observed,System.currentTimeMillis());
    }
    synchronized void reconcile(BlueprintProject project,Map<BlockPos,String> observed,long now) {
        if(!matches(project))return;
        List<Receipt> next=new ArrayList<>();
        for(var r:saved.receipts()) {
            String actual=observed.get(r.pos());
            if(actual==null){next.add(r);continue;}
            if(observedMatches(r,actual)) next.add(r.observed()?r:new Receipt(r.x(),r.y(),r.z(),r.state(),r.admittedAt(),true));
            else if(air(actual)&&!r.observed()&&now-r.admittedAt()<2000)next.add(r);
            // Removed, replaced, or unacknowledged: no authority to adopt a later owner block.
        }
        if(!next.equals(saved.receipts()))save(new Saved(1,saved.revision()+1,world,project.projectId(),project.digest(),List.copyOf(next)));
    }
    private Path temporary(){return path.resolveSibling(path.getFileName()+".tmp");}
    private boolean save(Saved next) {
        try {
            if(path!=null){
                Files.createDirectories(path.getParent());Files.writeString(temporary(),JSON.toJson(next),StandardCharsets.UTF_8);
                try(var channel=java.nio.channels.FileChannel.open(temporary(),StandardOpenOption.WRITE)){channel.force(true);}
                try{Files.move(temporary(),path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary(),path,StandardCopyOption.REPLACE_EXISTING);}
            }
            saved=next;failure="";return true;
        }catch(IOException problem){
            org.slf4j.LoggerFactory.getLogger(BlueprintSupportLedger.class).warn(
                    "Cannot persist blueprint support receipt revision {}",next.revision(),problem);
            failure="Cannot save exact construction support ownership ("+problem.getClass().getSimpleName()+")";
            return false;
        }
    }
}
