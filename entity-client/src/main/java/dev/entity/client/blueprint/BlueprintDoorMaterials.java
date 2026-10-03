package dev.entity.client.blueprint;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.BedPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.enums.DoubleBlockHalf;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import java.util.*;

/** Scoped native build compatibility. Real placement rays and completed world states remain exact. */
public final class BlueprintDoorMaterials {
    private record Active(Object builder, Set<BlockState> materials, Map<BlockPos,BlockState> positions) {}
    private static volatile Active active;
    private static long lastNativeTrace;
    private static long lastBreakDecision;
    private static String lastBreakDetail = "none";
    private BlueprintDoorMaterials() {}
    public static boolean unsafeBuildDiagonal(int x,int y,int z,int destX,int destZ,
            java.util.function.Function<BlockPos,BlockState> read) {
        Active a=active;
        if(a==null||Math.abs(destX-x)!=1||Math.abs(destZ-z)!=1)return false;
        var floors=List.of(new BlockPos(x,y-1,z),new BlockPos(destX,y-1,destZ),
                new BlockPos(x,y-1,destZ),new BlockPos(destX,y-1,z));
        if(floors.stream().noneMatch(a.positions()::containsKey))return false;
        boolean bottomSlab=false,bottomStair=false;
        for(var floor:floors) {
            var state=read.apply(floor);
            if(state.getBlock() instanceof net.minecraft.block.StairsBlock
                    &&state.get(net.minecraft.block.StairsBlock.HALF)==net.minecraft.block.enums.BlockHalf.BOTTOM)bottomStair=true;
            if(state.getBlock() instanceof net.minecraft.block.SlabBlock
                    &&state.get(Properties.SLAB_TYPE)==net.minecraft.block.enums.SlabType.BOTTOM)bottomSlab=true;
        }
        return bottomSlab&&bottomStair;
    }
    /** Called only in native's backplace-only cost branch, after other attachments failed. */
    public static boolean unsafeStairBackplace(BlockState source,int dx,int dz) {
        return active!=null && source!=null && source.getBlock() instanceof net.minecraft.block.StairsBlock
                && source.get(net.minecraft.block.StairsBlock.HALF)==net.minecraft.block.enums.BlockHalf.BOTTOM
                && source.get(net.minecraft.block.StairsBlock.SHAPE)==net.minecraft.block.enums.StairShape.STRAIGHT
                && source.get(Properties.HORIZONTAL_FACING).getOffsetX()==-dx
                && source.get(Properties.HORIZONTAL_FACING).getOffsetZ()==-dz;
    }
    /** Keep native's existing sneak branch when its chosen break ray needs the lower eye. */
    public static boolean preserveRequiredBreakSneak(boolean original,Object builder,BlockPos target,
                                                     baritone.api.utils.Rotation rotation) {
        Active a=active;
        if(a!=null && a.builder()==builder && target!=null && rotation!=null) {
            lastBreakDecision=System.currentTimeMillis();
            lastBreakDetail=target.toShortString()+" rotation="+rotation+" nativeSneak="+original;
        }
        if(original||a==null||a.builder()!=builder||target==null||rotation==null) return original;
        BlockPos exact=new BlockPos(target.getX(),target.getY(),target.getZ());
        if(!a.positions().containsKey(exact))return false;
        var client=MinecraftClient.getInstance();
        if(client.player==null||client.world==null)return false;
        var nativePort=baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
        if(nativePort.getBuilderProcess()!=builder)return false;
        var aim=nativePort.getLookBehavior().getAimProcessor().peekRotation(rotation);
        var direction=net.minecraft.util.math.Vec3d.fromPolar(aim.getPitch(),aim.getYaw());
        return crouchedBreakRayRequired(exact,client.player.getPos(),
                client.player.getEyeHeight(net.minecraft.entity.EntityPose.STANDING),
                client.player.getEyeHeight(net.minecraft.entity.EntityPose.CROUCHING),direction,
                client.player.getBlockInteractionRange(),(start,end)->{
                    var hit=client.world.raycast(new net.minecraft.world.RaycastContext(start,end,
                                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                                net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                    return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK?hit.getBlockPos():null;
                });
    }
    static boolean crouchedBreakRayRequired(BlockPos target,net.minecraft.util.math.Vec3d body,
            double standingEye,double crouchingEye,net.minecraft.util.math.Vec3d direction,double reach,
            java.util.function.BiFunction<net.minecraft.util.math.Vec3d,net.minecraft.util.math.Vec3d,BlockPos> ray) {
        var standing=body.add(0,standingEye,0);
        if(target.equals(ray.apply(standing,standing.add(direction.multiply(reach)))))return false;
        var crouching=body.add(0,crouchingEye,0);
        return target.equals(ray.apply(crouching,crouching.add(direction.multiply(reach))));
    }
    /** Bounded observation of the native decision, before other tick owners run. */
    public static void observeNativeDecision(baritone.api.process.PathingCommand command) {
        Active a=active;
        if(a==null) return;
        var nativePort=baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
        if(a.builder()!=nativePort.getBuilderProcess()) return;
        long now=System.currentTimeMillis();
        if(now-lastNativeTrace<2000) return;
        lastNativeTrace=now;
        var client=MinecraftClient.getInstance();
        if(client.player==null) return;
        var inputs=nativePort.getInputOverrideHandler();
        var feet=client.player.getBlockPos();
        String goal=command==null||command.goal==null ? "none" : command.goal.toString();
        if(goal.length()>1800)goal=goal.substring(0,1800)+"...";
        org.slf4j.LoggerFactory.getLogger("Entity2Blueprint").info(
                "Native build input at {} selected={} sneakForced={} useForced={} attackForced={} sneaking={} command={} goalSatisfied={} goal={} onGround={} yaw={} pitch={} breakAgeMs={} break={} crosshair={} movement={}",
                client.player.getPos(),client.player.getMainHandStack(),
                inputs.isInputForcedDown(baritone.api.utils.input.Input.SNEAK),
                inputs.isInputForcedDown(baritone.api.utils.input.Input.CLICK_RIGHT),
                inputs.isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT),client.player.isSneaking(),
                command==null?"none":command.commandType,
                command!=null&&command.goal!=null&&command.goal.isInGoal(feet.getX(),feet.getY(),feet.getZ()),goal,
                client.player.isOnGround(),client.player.getYaw(),client.player.getPitch(),
                lastBreakDecision==0?-1:now-lastBreakDecision,lastBreakDetail,
                client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                        ? hit.getBlockPos().toShortString()+"/"+hit.getSide()+"/"+hit.getType() : client.crosshairTarget,
                nativeMovementDetail(nativePort,client));
    }
    private static String nativeMovementDetail(baritone.api.IBaritone port,MinecraftClient client) {
        try {
            var path=port.getPathingBehavior().getCurrent();
            if(path==null||path.getPosition()<0||path.getPosition()>=path.getPath().movements().size())return "none";
            var movement=path.getPath().movements().get(path.getPosition());
            // Pinned ProGuard has several fields named 'a'; select by exact type,
            // never ambiguous field name. Read-only diagnostic, no update() call.
            Object state=diagnosticField(movement,"baritone.cd");
            Object target=diagnosticField(state,"baritone.cd$a");
            return movement.getClass().getSimpleName()+":"+movement.getSrc()+"->"+movement.getDest()
                    +" status="+diagnosticField(state,"baritone.api.pathing.movement.MovementStatus")
                    +" target="+diagnosticField(target,"baritone.api.utils.Rotation")
                    +" keys="+diagnosticField(state,"java.util.Map")
                    +" sampled="+client.player.input.playerInput
                    +" collision="+client.player.horizontalCollision
                    +" directOwner="+diagnosticDirectOwner(client);
        } catch(RuntimeException ex) {return "diagnostic-unavailable:"+ex.getClass().getSimpleName();}
    }
    private static Object diagnosticDirectOwner(MinecraftClient client) {
        try {
            Class<?> type=Class.forName("dev.entity.client.control.MinecraftMovementGateway");
            Object gateway=type.getMethod("shared",MinecraftClient.class).invoke(null,client);
            Object snapshot=type.getMethod("snapshot").invoke(gateway);
            return ((Optional<?>)snapshot.getClass().getMethod("activeAction").invoke(snapshot)).isPresent();
        } catch(ReflectiveOperationException|RuntimeException ex){return "unavailable";}
    }
    private static Object diagnosticField(Object owner,String type) {
        if(owner==null)return null;
        for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())for(var field:c.getDeclaredFields())
            if(field.getType().getName().equals(type))try {field.setAccessible(true);return field.get(owner);}
            catch(ReflectiveOperationException|RuntimeException ex){return "unavailable";}
        return null;
    }
    static void open(Object builder, Map<BlockPos,BlockState> desired) {
        Set<BlockState> materials = new HashSet<>();
        Map<BlockPos,BlockState> positions = new HashMap<>();
        desired.forEach((pos,state)->{
            if (!state.isAir()) materials.add(state);
            positions.put(new BlockPos(pos.getX(),pos.getY(),pos.getZ()),state);
        });
        active = new Active(builder, Set.copyOf(materials), Map.copyOf(positions));
        lastBreakDecision=0;
        lastBreakDetail="none";
    }
    /** Native break adjacency can exclude a real visible stance above the soil's Y. */
    public static boolean appendCoveredSoilRemovalApproach(Object builder,List<Goal> goals,BlockPos pos) {
        var client=MinecraftClient.getInstance();
        if(client==null||client.world==null||client.player==null||pos==null)return false;
        var world=client.world;
        // Query a real received chunk, never ClientWorld's synthetic empty fallback.
        java.util.function.Predicate<BlockPos> loaded=p->world.getChunkManager().getChunk(
                p.getX()>>4,p.getZ()>>4,net.minecraft.world.chunk.ChunkStatus.FULL,false)!=null;
        if(!loaded.test(pos)||!loaded.test(pos.up()))return false;
        return appendCoveredSoilRemovalApproach(builder,goals,pos,world::getBlockState,
                stand->{
                    if(!loaded.test(stand)||!loaded.test(stand.up())||!loaded.test(stand.down())
                            ||!world.getFluidState(stand).isEmpty()||!world.getFluidState(stand.up()).isEmpty())return false;
                    var floor=stand.down();
                    return world.getBlockState(floor).isSideSolidFullSquare(world,floor,net.minecraft.util.math.Direction.UP)
                            &&world.isSpaceEmpty(new net.minecraft.util.math.Box(stand.getX()+.2,stand.getY(),stand.getZ()+.2,
                                    stand.getX()+.8,stand.getY()+1.8,stand.getZ()+.8));
                },(stand,target)->nativeSoilCenterVisibleFromStand(stand,target,
                        client.player.getEyeHeight(net.minecraft.entity.EntityPose.STANDING),client.player.getBlockInteractionRange(),
                        (start,end)->{
                            var hit=world.raycast(new net.minecraft.world.RaycastContext(start,end,
                                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                                    net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                            return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK&&hit.getBlockPos().equals(target);
                        }));
    }
    /** Native GoalBreak excludes Y+1 even when the requested soil has a real visible side stance. */
    static boolean appendCoveredSoilRemovalApproach(Object builder,List<Goal> goals,BlockPos pos,
            java.util.function.Function<BlockPos,BlockState> read,java.util.function.Predicate<BlockPos> standable,
            java.util.function.BiPredicate<BlockPos,BlockPos> visible) {
        Active a=active;
        if(a==null||a.builder()!=builder||pos==null)return false;
        var target=new BlockPos(pos.getX(),pos.getY(),pos.getZ());
        var wanted=a.positions().get(target);
        var above=a.positions().get(target.up());
        if(wanted==null||!wanted.isOf(net.minecraft.block.Blocks.AIR)
                ||!BlueprintTerrainStates.naturalSoil(read.apply(target))
                ||above==null||!above.isOf(net.minecraft.block.Blocks.COBBLESTONE_WALL)
                ||!BlueprintTerrainStates.matches(above,read.apply(target.up())))return false;
        var candidates=new ArrayList<Goal>();
        for(var side:net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            var stand=target.offset(side).up();
            if(standable.test(stand)&&visible.test(stand,target))candidates.add(new GoalBlock(stand));
        }
        if(candidates.isEmpty())return false;
        goals.add(new GoalComposite(candidates.toArray(Goal[]::new)));
        return true;
    }
    static boolean nativeSoilCenterVisibleFromStand(BlockPos stand,BlockPos target,double eye,double reach,
            java.util.function.BiPredicate<net.minecraft.util.math.Vec3d,net.minecraft.util.math.Vec3d> clear) {
        var start=net.minecraft.util.math.Vec3d.ofBottomCenter(stand).add(0,eye,0);
        // RotationUtils.reachable first aims at the full soil outline's center.
        // The actual ray hits its exposed top before that point; no new aim sample.
        var end=net.minecraft.util.math.Vec3d.ofCenter(target);
        return start.squaredDistanceTo(end)<=reach*reach&&clear.test(start,end);
    }
    /** Native adjacent goals do not account for facing or multipart placement. */
    public static boolean appendApproach(Object builder, Collection<? extends BlockPos> pending,
                                         List<Goal> goals, BlockPos pos) {
        var client=MinecraftClient.getInstance();
        var world=client==null?null:client.world;
        // Carried material is not executable work until Minecraft's attachment
        // prerequisites exist. Otherwise a torch with a missing beam can keep
        // native assemble nonempty forever, hiding that beam's material deficit.
        if(world!=null&&deferUnsupportedPlacement(builder,pos,(p,state)->state.canPlaceAt(world,p)))return true;
        if(world!=null&&client.player!=null&&appendSideAttachmentApproach(builder,pos,goals,
                p->world.getBlockState(p).isSideSolidFullSquare(world,p,net.minecraft.util.math.Direction.UP),
                (p,face)->world.getBlockState(p).isSideSolidFullSquare(world,p,face),
                (p,state)->stairShapeReady(state,world,p),
                stand->{
                    if(!world.isChunkLoaded(stand)||!world.getFluidState(stand).isEmpty()
                            ||!world.getFluidState(stand.up()).isEmpty())return false;
                    var floor=stand.down();
                    return world.getBlockState(floor).isSideSolidFullSquare(world,floor,net.minecraft.util.math.Direction.UP)
                            &&world.isSpaceEmpty(new net.minecraft.util.math.Box(stand.getX()+.2,stand.getY(),stand.getZ()+.2,
                                    stand.getX()+.8,stand.getY()+1.8,stand.getZ()+.8));
                },(stand,support,face)->visibleSideFromStand(stand,support,face,
                        client.player.getEyeHeight(net.minecraft.entity.EntityPose.CROUCHING),client.player.getBlockInteractionRange(),
                        (start,end)->{
                            var hit=world.raycast(new net.minecraft.world.RaycastContext(start,end,
                                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                                    net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                            return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK
                                    &&hit.getBlockPos().equals(support)&&hit.getSide()==face;
                        })))return true;
        if(world!=null&&client.player!=null&&appendVerticalAxisApproach(builder,pending,goals,pos,
                support->{
                    var shape=world.getBlockState(support).getOutlineShape(world,support);
                    return !shape.isEmpty()&&shape.getMax(net.minecraft.util.math.Direction.Axis.Y)==1;
                },stand->world.isChunkLoaded(stand)&&world.getFluidState(stand).isEmpty()
                        &&world.getFluidState(stand.up()).isEmpty()
                        &&world.getBlockState(stand.down()).isSideSolidFullSquare(world,stand.down(),net.minecraft.util.math.Direction.UP)
                        &&world.isSpaceEmpty(new net.minecraft.util.math.Box(stand.getX()+.2,stand.getY(),stand.getZ()+.2,
                                stand.getX()+.8,stand.getY()+1.8,stand.getZ()+.8)),
                (stand,support)->visibleShapeTopFromStand(stand,support,
                        world.getBlockState(support).getOutlineShape(world,support).getBoundingBox(),
                        client.player.getEyeHeight(net.minecraft.entity.EntityPose.CROUCHING),client.player.getBlockInteractionRange(),
                        (start,end)->{
                            var hit=world.raycast(new net.minecraft.world.RaycastContext(start,end,
                                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                                    net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                            return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK
                                    &&hit.getBlockPos().equals(support)&&hit.getSide()==net.minecraft.util.math.Direction.UP;
                        })))return true;
        if(world!=null&&client.player!=null&&appendAxisApproach(builder,pending,goals,pos,world::getBlockState,
                stand->world.isChunkLoaded(stand)&&world.getFluidState(stand).isEmpty()
                        &&world.getFluidState(stand.up()).isEmpty()
                        &&world.isSpaceEmpty(new net.minecraft.util.math.Box(stand.getX()+.2,stand.getY(),stand.getZ()+.2,
                                stand.getX()+.8,stand.getY()+1.8,stand.getZ()+.8)),
                (stand,support,face)->visibleSideFromStand(stand,support,face,
                        client.player.getEyeHeight(net.minecraft.entity.EntityPose.CROUCHING),client.player.getBlockInteractionRange(),
                        (start,end)->{
                            var hit=world.raycast(new net.minecraft.world.RaycastContext(start,end,
                                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                                    net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                            return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK
                                    &&hit.getBlockPos().equals(support)&&hit.getSide()==face;
                        })))return true;
        if(appendApproach(builder,pending,goals,pos,support->{
            return world!=null && world.getBlockState(support).isSideSolidFullSquare(
                    world,support,net.minecraft.util.math.Direction.UP);
        },(position,state)->{
            return world!=null && stairShapeReady(state,world,position);
        },(stand,support)->{
            if(world==null||client.player==null)return false;
            return visibleTopFromStand(stand,support,client.player.getEyeHeight(net.minecraft.entity.EntityPose.CROUCHING),
                    client.player.getBlockInteractionRange(),(start,end)->{
            var hit=world.raycast(new net.minecraft.world.RaycastContext(start,end,
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
            return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK
                    &&hit.getBlockPos().equals(support)&&hit.getSide()==net.minecraft.util.math.Direction.UP;
            });
        }))return true;
        if(world==null||client.player==null)return false;
        return appendSupportedApproach(builder,pending,goals,pos,
                support->world.getBlockState(support).isSideSolidFullSquare(world,support,net.minecraft.util.math.Direction.UP),
                stand->{
                    if(!world.isChunkLoaded(stand)||!world.getFluidState(stand).isEmpty()
                            ||!world.getFluidState(stand.up()).isEmpty())return false;
                    var floor=stand.down();
                    var body=new net.minecraft.util.math.Box(stand.getX()+.2,stand.getY(),stand.getZ()+.2,
                            stand.getX()+.8,stand.getY()+1.8,stand.getZ()+.8);
                    return world.getBlockState(floor).isSideSolidFullSquare(world,floor,net.minecraft.util.math.Direction.UP)
                            &&world.isSpaceEmpty(body);
                },(stand,support)->visibleTopFromStand(stand,support,
                        client.player.getEyeHeight(net.minecraft.entity.EntityPose.CROUCHING),
                        client.player.getBlockInteractionRange(),(start,end)->{
                            var hit=world.raycast(new net.minecraft.world.RaycastContext(start,end,
                                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                                    net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                            return hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK
                                    &&hit.getBlockPos().equals(support)&&hit.getSide()==net.minecraft.util.math.Direction.UP;
                        }));
    }

    static boolean deferUnsupportedPlacement(Object builder,BlockPos pos,
            java.util.function.BiPredicate<BlockPos,BlockState> canPlaceAt) {
        Active a=active;
        if(a==null||a.builder()!=builder)return false;
        var exact=new BlockPos(pos.getX(),pos.getY(),pos.getZ());
        var wanted=a.positions().get(exact);
        return wanted!=null&&!wanted.isAir()&&!canPlaceAt.test(exact,wanted);
    }
    @FunctionalInterface interface FaceVisibility {
        boolean test(BlockPos stand,BlockPos support,net.minecraft.util.math.Direction face);
    }
    /** A wall attachment is reached from a real floor, not from the attachment's Y. */
    static boolean appendSideAttachmentApproach(Object builder,BlockPos pos,List<Goal> goals,
            java.util.function.Predicate<BlockPos> solidTop,
            java.util.function.BiPredicate<BlockPos,net.minecraft.util.math.Direction> solidFace,
            java.util.function.BiPredicate<BlockPos,BlockState> shapeReady,
            java.util.function.Predicate<BlockPos> standable,FaceVisibility visible) {
        Active a=active;
        if(a==null||a.builder()!=builder)return false;
        var target=new BlockPos(pos.getX(),pos.getY(),pos.getZ());
        var wanted=a.positions().get(target);
        if(wanted==null)return false;
        net.minecraft.util.math.Direction face;
        if(wanted.isOf(net.minecraft.block.Blocks.WALL_TORCH)
                ||wanted.isOf(net.minecraft.block.Blocks.SOUL_WALL_TORCH)
                ||wanted.isOf(net.minecraft.block.Blocks.REDSTONE_WALL_TORCH)) {
            face=wanted.get(Properties.HORIZONTAL_FACING);
        } else if(wanted.getBlock() instanceof net.minecraft.block.StairsBlock
                &&wanted.get(net.minecraft.block.StairsBlock.HALF)==net.minecraft.block.enums.BlockHalf.BOTTOM
                &&!solidTop.test(target.down())) {
            // An eave can sit above a decorative torch. That torch cannot be
            // replaced with a floor: use the existing rear beam's side instead.
            var below=a.positions().get(target.down());
            if(below==null||below.isAir()||!shapeReady.test(target,wanted))return false;
            face=wanted.get(Properties.HORIZONTAL_FACING).getOpposite();
        } else return false;
        var support=target.offset(face.getOpposite());
        if(!solidFace.test(support,face))return false;
        var center=target.offset(face,2);
        var candidates=new ArrayList<Goal>();
        for(int dy=-4;dy<=1;dy++)for(int offset=-1;offset<=1;offset++) {
            var stand=center.add(0,dy,0).offset(face.rotateYClockwise(),offset);
            if(standable.test(stand)&&visible.test(stand,support,face))candidates.add(new GoalBlock(stand));
        }
        if(candidates.isEmpty())return false;
        goals.add(new GoalComposite(candidates.toArray(Goal[]::new)));
        return true;
    }
    static boolean visibleSideFromStand(BlockPos stand,BlockPos support,net.minecraft.util.math.Direction face,
            double eye,double reach,java.util.function.BiPredicate<net.minecraft.util.math.Vec3d,net.minecraft.util.math.Vec3d> clear) {
        var start=net.minecraft.util.math.Vec3d.ofBottomCenter(stand).add(0,eye,0);
        // Native possibleToPlace samples the side center at quarter height.
        // A witness at another point would not prove the native actuator can use it.
        for(double along:new double[]{.5})for(double height:new double[]{.25}) {
            double x=face.getAxis()==net.minecraft.util.math.Direction.Axis.X?(face.getOffsetX()>0?.9999:.0001):along;
            double z=face.getAxis()==net.minecraft.util.math.Direction.Axis.Z?(face.getOffsetZ()>0?.9999:.0001):along;
            var end=new net.minecraft.util.math.Vec3d(support.getX()+x,support.getY()+height,support.getZ()+z);
            if(start.squaredDistanceTo(end)<=reach*reach&&clear.test(start,end))return true;
        }
        return false;
    }
    /**
     * A placeable block and a place to stand are different facts. Native's generic
     * GoalAdjacent excludes lower feet when air is above the target; GoalPlace
     * demands standing above it when our own body currently obstructs placement.
     * Neither is necessary when a real supported, visible side stance already exists.
     * Keep all actual native collision/raycast/item checks; change only its route goal.
     */
    static boolean appendSupportedApproach(Object builder,Collection<? extends BlockPos> pending,
            List<Goal> goals,BlockPos pos,java.util.function.Predicate<BlockPos> solidTop,
            java.util.function.Predicate<BlockPos> standable,
            java.util.function.BiPredicate<BlockPos,BlockPos> visibleSupport) {
        Active a=active;
        if(a==null||a.builder()!=builder)return false;
        BlockPos target=new BlockPos(pos.getX(),pos.getY(),pos.getZ());
        BlockState wanted=a.positions().get(target);
        if(wanted==null||wanted.isAir()||!solidTop.test(target.down()))return false;
        // Facing/axis/multipart approaches are handled before this generic branch.
        if(wanted.contains(Properties.HORIZONTAL_FACING)||wanted.contains(Properties.FACING)
                ||wanted.contains(Properties.AXIS)||wanted.getBlock() instanceof net.minecraft.block.SlabBlock)return false;
        if(pending.contains(pos.down())||pending.contains(pos.down(2)))return true;
        var candidates=new LinkedHashSet<BlockPos>();
        for(var side:net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            var center=target.offset(side,2);
            var lateral=side.rotateYClockwise();
            // Reach a raised window from the existing floor, not a needless dirt
            // ascend. Two horizontal cells clear even a GoalBlock edge arrival.
            for(int dy:new int[]{-1,0})for(int offset:new int[]{0,-1,1}) {
                var stand=center.add(0,dy,0).offset(lateral,offset);
                if(standable.test(stand)&&visibleSupport.test(stand,target.down()))candidates.add(stand);
            }
        }
        if(candidates.isEmpty())return false; // retain native support acquisition if none exists
        goals.add(new GoalComposite(candidates.stream().map(GoalBlock::new).toArray(Goal[]::new)));
        return true;
    }
    static boolean visibleTopFromStand(BlockPos stand,BlockPos support,double eye,double reach,
            java.util.function.BiPredicate<net.minecraft.util.math.Vec3d,net.minecraft.util.math.Vec3d> clear) {
        var start=net.minecraft.util.math.Vec3d.ofBottomCenter(stand).add(0,eye,0);
        // An adjacent chest may cover the center while a side of the same top
        // face remains visible. Check actual face samples, not center distance.
        for(double[] point:new double[][]{{.5,.5},{.1,.5},{.9,.5},{.5,.1},{.5,.9}}) {
            var end=new net.minecraft.util.math.Vec3d(support.getX()+point[0],support.getY()+.9999,support.getZ()+point[1]);
            if(start.squaredDistanceTo(end)<=reach*reach&&clear.test(start,end))return true;
        }
        return false;
    }
    /** A vertical beam above a pane is placed from the roof, not inside its occupied rim. */
    static boolean appendVerticalAxisApproach(Object builder,Collection<? extends BlockPos> pending,
            List<Goal> goals,BlockPos pos,java.util.function.Predicate<BlockPos> topAttachment,
            java.util.function.Predicate<BlockPos> standable,
            java.util.function.BiPredicate<BlockPos,BlockPos> visibleTop) {
        Active a=active;
        if(a==null||a.builder()!=builder)return false;
        var target=new BlockPos(pos.getX(),pos.getY(),pos.getZ());
        var wanted=a.positions().get(target);
        if(wanted==null||!(wanted.getBlock() instanceof net.minecraft.block.PillarBlock)
                ||wanted.get(Properties.AXIS)!=net.minecraft.util.math.Direction.Axis.Y
                ||!topAttachment.test(target.down()))return false;
        if(pending.contains(pos.down())||pending.contains(pos.down(2)))return true;
        var candidates=new LinkedHashSet<BlockPos>();
        for(var side:net.minecraft.util.math.Direction.Type.HORIZONTAL)
            for(int dy=-1;dy<=2;dy++)for(int radius=1;radius<=2;radius++)for(int offset=-1;offset<=1;offset++) {
                // Above the target's top the body cannot overlap the new beam.
                // One adjacent roof cell then exposes the pane's top, whereas
                // the extra setback can hide it behind that same roof/scaffold.
                if(radius==1&&dy<1)continue;
                var stand=target.offset(side,radius).up(dy).offset(side.rotateYClockwise(),offset);
                if(standable.test(stand)&&visibleTop.test(stand,target.down()))candidates.add(stand);
            }
        if(candidates.isEmpty())return false;
        goals.add(new GoalComposite(candidates.stream().map(GoalBlock::new).toArray(Goal[]::new)));
        return true;
    }
    static boolean visibleShapeTopFromStand(BlockPos stand,BlockPos support,net.minecraft.util.math.Box shape,
            double eye,double reach,
            java.util.function.BiPredicate<net.minecraft.util.math.Vec3d,net.minecraft.util.math.Vec3d> clear) {
        var start=net.minecraft.util.math.Vec3d.ofBottomCenter(stand).add(0,eye,0);
        // Native samples the actual outline bounds, not the whole block cube.
        for(double[] point:new double[][]{{.5,.5},{.1,.5},{.9,.5},{.5,.1},{.5,.9}}) {
            var end=new net.minecraft.util.math.Vec3d(support.getX()+shape.minX+point[0]*(shape.maxX-shape.minX),
                    support.getY()+shape.maxY-.0001,support.getZ()+shape.minZ+point[1]*(shape.maxZ-shape.minZ));
            if(start.squaredDistanceTo(end)<=reach*reach&&clear.test(start,end))return true;
        }
        return false;
    }
    /** Logs take their axis from the clicked face, not the player's facing. */
    static boolean appendAxisApproach(Object builder,Collection<? extends BlockPos> pending,
            List<Goal> goals,BlockPos pos,java.util.function.Function<BlockPos,BlockState> read) {
        return appendAxisApproach(builder,pending,goals,pos,read,
                stand->read.apply(stand).isAir()&&read.apply(stand.up()).isAir()
                        &&!read.apply(stand.down()).isAir()&&!read.apply(stand.down()).isReplaceable()
                        &&read.apply(stand.down()).getFluidState().isEmpty(),(stand,support,face)->true);
    }
    static boolean appendAxisApproach(Object builder,Collection<? extends BlockPos> pending,
            List<Goal> goals,BlockPos pos,java.util.function.Function<BlockPos,BlockState> read,
            java.util.function.Predicate<BlockPos> bodyClear,FaceVisibility visible) {
        Active a=active;
        if(a==null||a.builder()!=builder)return false;
        BlockPos exact=new BlockPos(pos.getX(),pos.getY(),pos.getZ());
        BlockState wanted=a.positions().get(exact);
        if(wanted==null||!(wanted.getBlock() instanceof net.minecraft.block.PillarBlock)
                ||wanted.get(Properties.AXIS)==net.minecraft.util.math.Direction.Axis.Y)return false;
        if(pending.contains(pos.down())||pending.contains(pos.down(2)))return true;
        for(var against:net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            if(against.getAxis()!=wanted.get(Properties.AXIS))continue;
            BlockState support=read.apply(exact.offset(against));
            // A floor's top face would produce a vertical log. Until an axis-
            // aligned neighbor exists, this log must not satisfy the entire OR
            // goal. Native then builds its available neighbors or reports the
            // actual layer's missing material to the existing supply owner.
            if(support.isAir()||support.isReplaceable()||!support.getFluidState().isEmpty())continue;
            var face=against.getOpposite();
            var center=exact.offset(face,2);
            var candidates=new ArrayList<Goal>();
            // Completed wall/beam cells are not places to stand. A clear body and
            // a visible axis face are mandatory; the native route may build its
            // own permitted footing instead of requiring a scaffold already there.
            for(int dy=-4;dy<=1;dy++)for(int offset=-1;offset<=1;offset++) {
                var stand=center.add(0,dy,0).offset(face.rotateYClockwise(),offset);
                if(bodyClear.test(stand)&&visible.test(stand,exact.offset(against),face))
                    candidates.add(new GoalBlock(stand));
            }
            if(!candidates.isEmpty())goals.add(new GoalComposite(candidates.toArray(Goal[]::new)));
        }
        return true;
    }
    static boolean appendApproach(Object builder, Collection<? extends BlockPos> pending,
                                  List<Goal> goals, BlockPos pos,
                                  java.util.function.Predicate<BlockPos> solidTop) {
        return appendApproach(builder,pending,goals,pos,solidTop,(position,state)->true);
    }
    static boolean appendApproach(Object builder, Collection<? extends BlockPos> pending,
                                  List<Goal> goals, BlockPos pos,
                                  java.util.function.Predicate<BlockPos> solidTop,
                                  java.util.function.BiPredicate<BlockPos,BlockState> shapeReady) {
        return appendApproach(builder,pending,goals,pos,solidTop,shapeReady,(stand,support)->true);
    }
    static boolean appendApproach(Object builder, Collection<? extends BlockPos> pending,
                                  List<Goal> goals, BlockPos pos,
                                  java.util.function.Predicate<BlockPos> solidTop,
                                  java.util.function.BiPredicate<BlockPos,BlockState> shapeReady,
                                  java.util.function.BiPredicate<BlockPos,BlockPos> visibleSupport) {
        Active a=active;
        if (a==null || a.builder()!=builder) return false;
        // BetterBlockPos equals vanilla BlockPos but deliberately hashes differently.
        // Canonicalize both map boundaries; toImmutable() can return the subclass.
        BlockState door=a.positions().get(new BlockPos(pos.getX(),pos.getY(),pos.getZ()));
        if(door!=null&&door.getBlock() instanceof net.minecraft.block.SlabBlock
                &&door.get(Properties.SLAB_TYPE)==net.minecraft.block.enums.SlabType.BOTTOM
                &&solidTop.test(pos.down())) {
            // Native GoalPlace can demand standing ABOVE an unplaced slab;
            // route scaffolding cannot replace that exact permanent cell. A real
            // support top already exists, so approach its visible face instead.
            if(!pending.contains(pos.down()))for(var facing:net.minecraft.util.math.Direction.Type.HORIZONTAL)
                appendVisibleTopGoals(goals,pos,facing,visibleSupport,true);
            return true;
        }
        if(door!=null && door.getBlock() instanceof net.minecraft.block.StairsBlock) {
            // Minecraft derives corners from existing neighbors. An approach to
            // a corner that cannot yet exist would satisfy native's OR goal and
            // starve the neighboring straight stair that makes it placeable.
            if(!shapeReady.test(pos,door)) return true;
            BlockPos below=new BlockPos(pos.getX(),pos.getY()-1,pos.getZ());
            BlockState wantedBelow=a.positions().get(below);
            // Stand in the still-empty stair cell so native bridging creates its
            // support BELOW it. Standing above an adjacent support hides its far
            // side behind its top face, so no legal native ray can place the roof.
            // Once the top face exists, withdraw on the facing-correct side.
            // GoalBlock accepts a cell edge: one adjacent cell can still leave
            // the 0.6-wide body overlapping the stair's bottom slab. Two cells
            // give clearance, as for beds/doors; native may place during approach.
            // Native costs still enforce original-AIR and receipt ownership; this
            // goal is not mutation permission or a replacement placement controller.
            boolean seedBelow=wantedBelow!=null && wantedBelow.isAir() && !solidTop.test(below);
            if(seedBelow)goals.add(new GoalBlock(pos));
            else appendVisibleTopGoals(goals,pos,door.get(Properties.HORIZONTAL_FACING),visibleSupport,true);
            return true;
        }
        if (door != null && (frontFacingFurniture(door) || door.getBlock() instanceof net.minecraft.block.AnvilBlock
                || door.getBlock() instanceof net.minecraft.block.FenceGateBlock)) {
            // Chest/furnace placement faces opposite the player's aim. A generic
            // adjacent goal can be satisfied on the wrong side and stop the entire
            // composite, including other still-reachable placement goals.
            if (!pending.contains(pos.down()) && !pending.contains(pos.down(2))) {
                var side=door.get(Properties.HORIZONTAL_FACING);
                // Vanilla AnvilBlock rotates the player's facing clockwise;
                // chest/furnace face the player. Their approach sides differ.
                if(door.getBlock() instanceof net.minecraft.block.AnvilBlock)side=side.rotateYClockwise();
                if(door.getBlock() instanceof net.minecraft.block.FenceGateBlock)side=side.getOpposite();
                goals.add(new GoalBlock(pos.offset(side,2)));
            }
            return true;
        }
        if (door != null && door.getBlock() instanceof BedBlock) {
            // The head appears only as a consequence of placing the foot. It is
            // never a standalone placement target, even when its item is held.
            if (door.get(BedBlock.PART)==BedPart.HEAD) return true;
            var facing=door.get(BedBlock.FACING);
            BlockPos head=pos.offset(facing);
            BlockState paired=a.positions().get(new BlockPos(head.getX(),head.getY(),head.getZ()));
            if (paired==null || paired.getBlock()!=door.getBlock()
                    || paired.get(BedBlock.PART)!=BedPart.HEAD || paired.get(BedBlock.FACING)!=facing) return false;
            if (!pending.contains(pos.down()) && !pending.contains(pos.down(2))
                    && !pending.contains(head.down())) {
                // Two cells back keeps body and facing correct. A chest directly
                // behind a bed can obstruct that center ray, so admit the two
                // lateral stances only when their real floor-face ray is clear.
                // Never keep an already-satisfied but occluded OR-goal member.
                appendVisibleTopGoals(goals,pos,facing,visibleSupport,false);
            }
            return true;
        }
        if (door==null || !(door.getBlock() instanceof DoorBlock)
                || door.get(DoorBlock.HALF)!=DoubleBlockHalf.LOWER) return false;
        // Preserve BuilderProcess's lower-cell dependency guards before adding a goal.
        if (!pending.contains(pos.down()) && !pending.contains(pos.down(2))) {
            // GoalBlock is satisfied at a cell edge, not its center. One adjacent
            // cell can leave the 0.6-wide player overlapping the new door's shape.
            BlockPos stand=pos.offset(door.get(DoorBlock.FACING).getOpposite(),2);
            // A raised foundation can put the outdoor walking surface one block
            // below the door. Both stances can reach its support's top face;
            // native pathfinding chooses the supported one, without a new pillar.
            goals.add(new GoalComposite(new GoalBlock(stand),new GoalBlock(stand.down())));
        }
        return true;
    }
    private static void appendVisibleTopGoals(List<Goal> goals,BlockPos pos,
            net.minecraft.util.math.Direction facing,
            java.util.function.BiPredicate<BlockPos,BlockPos> visibleSupport,boolean raisedRoofApproach) {
        var candidates=new ArrayList<Goal>();
        BlockPos center=pos.offset(facing.getOpposite(),2);
        for(int offset=-1;offset<=1;offset++) {
            BlockPos stand=center.offset(facing.rotateYClockwise(),offset);
            if(visibleSupport.test(stand,pos.down()))candidates.add(new GoalBlock(stand));
            // Completed neighboring roof blocks can cover every same-level ray.
            // Native may scaffold one level higher, but only when that exposes
            // the actual support top; this does not relax placement permission.
            else if(raisedRoofApproach&&visibleSupport.test(stand.up(),pos.down()))candidates.add(new GoalBlock(stand.up()));
        }
        if(!candidates.isEmpty())goals.add(new GoalComposite(candidates.toArray(Goal[]::new)));
    }
    /** Read vanilla's derived dry-stair shape without a click or world mutation. */
    static boolean stairShapeReady(BlockState desired,net.minecraft.world.WorldView world,BlockPos pos) {
        if(!(desired.getBlock() instanceof net.minecraft.block.StairsBlock)) return true;
        if(desired.get(Properties.WATERLOGGED)) return false;
        var direction=net.minecraft.util.math.Direction.NORTH;
        BlockPos neighbor=pos.offset(direction);
        // For a dry stair and horizontal update, vanilla reads neighbors only.
        // It uses neither the scheduled-tick view nor randomness on this branch.
        BlockState shaped=desired.getStateForNeighborUpdate(world,null,pos,direction,neighbor,
                world.getBlockState(neighbor),null);
        return desired.equals(shaped);
    }
    static void close(Object builder) {
        Active a = active;
        if (a != null && a.builder() == builder) active = null;
    }
    private static boolean frontFacingFurniture(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof AbstractFurnaceBlock;
    }
    /** Match the existing +/-5 native candidate cube; actual reach/ray/collision remain authoritative. */
    public static int placementScanTop(int original,Object builder) {
        Active a=active;
        return original==1&&a!=null&&a.builder()==builder?5:original;
    }
    /** Native dy==1/air-above heuristic is not valid for a two-high door placed from lower ground. */
    public static int ceilingExcludedOffset(int original, Object builder, int x, int y, int z, BlockState desired) {
        Active a=active;
        if(original!=1||a==null||a.builder()!=builder||desired==null)return original;
        var client=MinecraftClient.getInstance();
        var below=new BlockPos(x,y-1,z);
        boolean supported=client!=null&&client.world!=null&&client.world.getBlockState(below)
                .isSideSolidFullSquare(client.world,below,net.minecraft.util.math.Direction.UP);
        return ceilingExcludedOffset(original,builder,x,y,z,desired,supported);
    }
    static int ceilingExcludedOffset(int original,Object builder,int x,int y,int z,BlockState desired,
                                     boolean supported) {
        Active a=active;
        BlockState wanted=a==null?null:a.positions().get(new BlockPos(x,y,z));
        if (original==1 && a!=null && a.builder()==builder && desired!=null
                && (supported || desired.getBlock() instanceof DoorBlock && desired.get(DoorBlock.HALF)==DoubleBlockHalf.LOWER)
                && (desired.equals(wanted)||desired.equals(BlueprintTerrainStates.placementState(wanted)))) {
            // The scoped map preserves source grass, while a missing natural-soil cell's native
            // schematic requests survival dirt. Use that same exact placement projection here,
            // or a supported lower route can arrive while native dy==1 skips its own target.
            // Outside native's finite dy scan, so only this heuristic is
            // bypassed. The native world, collision, ray and exact state checks
            // still inspect the real cells before any packet. A supported raised
            // window needs no ceiling when its top attachment is visible from
            // the lower floor; the native actual ray still has to prove that.
            return Integer.MIN_VALUE;
        }
        return original;
    }
    public static boolean approximateMatch(BlockState candidate, BlockState desired) {
        Active a = active;
        return a != null && a.materials().contains(desired) && candidate != null
                && candidate.getBlock().asItem() != net.minecraft.item.Items.AIR
                && candidate.getBlock().asItem() == desired.getBlock().asItem();
    }
    /** Neighbor-derived connections settle as construction adds adjacent blocks. */
    static boolean connectedIntermediate(BlockState current,BlockState desired) {
        if(current==null||desired==null||current.getBlock()!=desired.getBlock())return false;
        var block=desired.getBlock();
        Set<String> derived;
        if(block instanceof net.minecraft.block.WallBlock)derived=Set.of("north","east","south","west","up");
        else if(block instanceof net.minecraft.block.FenceBlock||block instanceof net.minecraft.block.PaneBlock)
            derived=Set.of("north","east","south","west");
        else if(block instanceof net.minecraft.block.FenceGateBlock)derived=Set.of("in_wall");
        else return false;
        for(var property:desired.getProperties()) {
            if(block instanceof net.minecraft.block.FenceGateBlock && property==Properties.HORIZONTAL_FACING) {
                if(!BlueprintTerrainStates.sameGateAxis(desired,current))return false;
            } else if(!derived.contains(property.getName())&&!Objects.equals(current.get(property),desired.get(property)))return false;
        }
        return true;
    }
    /** Scoped native validity; final matching additionally requires settled connections. */
    public static boolean nativeConnectionMatch(BlockState current,BlockState desired) {
        Active a=active;
        if(a==null||!a.materials().contains(desired))return false;
        // Native validity already ignores OPEN for passage. Gate use can also
        // reverse facing; handle both together, never in final physical matching.
        if(BlueprintTerrainStates.sameGateAxis(desired,current))
            current=current.with(Properties.OPEN,desired.get(Properties.OPEN));
        return connectedIntermediate(current,desired);
    }
    /** A native route must not plan dirt scaffolding in a permanent torch/slab/furniture cell. */
    public static boolean requiresExactBuildPlacement(BlockState state) {
        return !state.isAir() && (!state.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE,BlockPos.ORIGIN)
                ||state.contains(Properties.HORIZONTAL_FACING)||state.contains(Properties.FACING)
                ||state.contains(Properties.AXIS));
    }
    /** Legacy source metadata can contradict the mandatory vanilla wall connection.
     * Preserve its content identity; reconcile only when BOTH neighbors are explicitly planned. */
    static Map<BlockPos,BlockState> effectiveGateConnections(Map<BlockPos,BlockState> original) {
        var result=new LinkedHashMap<BlockPos,BlockState>(original);
        original.forEach((pos,state)->{
            if(!(state.getBlock() instanceof net.minecraft.block.FenceGateBlock))return;
            var side=state.get(Properties.HORIZONTAL_FACING).rotateYClockwise();
            var a=original.get(pos.offset(side));var b=original.get(pos.offset(side.getOpposite()));
            if(a==null||b==null)return; // no invented state beyond the approved footprint
            boolean inWall=a.getBlock() instanceof net.minecraft.block.WallBlock||b.getBlock() instanceof net.minecraft.block.WallBlock;
            result.put(pos,state.with(Properties.IN_WALL,inWall));
        });
        return Map.copyOf(result);
    }
    /** Native block-only prediction omits the item's standing/wall selection. */
    public static BlockState placementState(Object builder, net.minecraft.block.Block block,
                                           net.minecraft.item.ItemPlacementContext context) {
        Active a=active;
        BlockPos pos=context.getBlockPos();
        BlockState desired=a==null ? null : a.positions().get(new BlockPos(pos.getX(),pos.getY(),pos.getZ()));
        if (a!=null && a.builder()==builder && desired!=null
                && context.getStack().getItem() instanceof net.minecraft.item.VerticallyAttachableBlockItem item
                && item.getBlock()==block && desired.getBlock().asItem()==item) {
            return itemPlacementState(block,context);
        }
        return block.getPlacementState(context);
    }
    public static BlockState itemPlacementState(net.minecraft.block.Block block,
                                               net.minecraft.item.ItemPlacementContext context) {
        if(context.getStack().getItem() instanceof net.minecraft.item.VerticallyAttachableBlockItem item
                && item.getBlock()==block) {
            return ((dev.entity.client.mixin.BlockItemPlacementInvoker)item).entity2$getPlacementState(context);
        }
        return block.getPlacementState(context);
    }
    static BlockState inventoryState(ItemStack stack, BlockState hypothetical) {
        // A held block's hypothetical placement at our feet is not its inventory
        // identity. Native placement prediction still checks the exact destination.
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem item
                ? item.getBlock().getDefaultState() : hypothetical;
    }
    /** The native click may fire on the right face before its aim has settled. */
    public static boolean actualPlacementMatches(BlockState desired, BlockState predicted) {
        // Wooden portals are placed closed, then opened by a separate use.
        // Native ignores OPEN while placing; the exact build observer does not.
        if (predicted != null && desired.contains(Properties.OPEN) && desired.get(Properties.OPEN)
                && (desired.getBlock() instanceof DoorBlock
                    || desired.getBlock() instanceof net.minecraft.block.TrapdoorBlock
                    || desired.getBlock() instanceof net.minecraft.block.FenceGateBlock)
                && desired.getBlock() == predicted.getBlock()
                && (desired.with(Properties.OPEN,false).equals(predicted)
                    ||connectedIntermediate(predicted,desired.with(Properties.OPEN,false)))) return true;
        // Every actual click must place the requested variant. The native look
        // can cross a slab's midpoint between prediction and the actual packet;
        // an item-only match would then create an unconsented bottom slab.
        return desired.equals(predicted)||connectedIntermediate(predicted,desired);
    }
    public static List<BlockState> normalize(Object builder, int size, List<BlockState> hypothetical) {
        Active a = active;
        if (a == null || a.builder() != builder || a.materials().isEmpty()) return hypothetical;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return hypothetical;
        List<BlockState> result = new ArrayList<>(hypothetical);
        for (int i = 0; i < Math.min(Math.min(size, result.size()), 36); i++) {
            result.set(i, inventoryState(client.player.getInventory().getStack(i), result.get(i)));
        }
        return result;
    }
}
