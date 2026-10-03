package dev.entity.client.baritone;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Stateless native JUMP default for a live mine waiting in water without actual native work. */
public final class NativeMiningBuoyancy {
    private static final Map<Object,BooleanSupplier> OWNERS=new WeakHashMap<>();
    private NativeMiningBuoyancy() {}

    public static synchronized void bind(Object nativeInput,BooleanSupplier currentOwner) {
        OWNERS.put(java.util.Objects.requireNonNull(nativeInput),java.util.Objects.requireNonNull(currentOwner));
    }

    public static boolean shouldFloat(Object nativeInput) {
        BooleanSupplier currentOwner;
        synchronized(NativeMiningBuoyancy.class) { currentOwner=OWNERS.get(nativeInput); }
        return currentOwner!=null&&currentOwner.getAsBoolean();
    }

    public static boolean eligible(boolean ownerValid,boolean miningOperation,boolean inWater,
            boolean nativeRouteActive,boolean interactionActive,boolean directedInput,boolean directFrameActive) {
        return ownerValid&&miningOperation&&inWater&&!nativeRouteActive
                &&!interactionActive&&!directedInput&&!directFrameActive;
    }
}
