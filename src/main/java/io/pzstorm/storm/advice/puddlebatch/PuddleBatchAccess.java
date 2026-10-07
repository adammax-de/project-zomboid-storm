package io.pzstorm.storm.advice.puddlebatch;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import zombie.iso.IsoChunk;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.fboRenderChunk.FBORenderCell;

/**
 * Reflection and method handles into the private vanilla state the batch renderer needs: the
 * per-player on-screen chunk list of {@code FBORenderCell}, and {@code IsoPuddles.RenderData},
 * whose {@code addSquare} is the vanilla vertex packer.
 *
 * <p>The packer runs against a scratch {@code RenderData} that only this class holds. The live
 * per-frame {@code RenderData} is never touched. Game thread only.
 */
final class PuddleBatchAccess {

    private static boolean initialized;

    private static Field perPlayerDataField;
    private static Field onScreenChunksField;
    private static MethodHandle renderDataClear;
    private static MethodHandle renderDataAddSquare;
    private static MethodHandle renderDataData;
    private static MethodHandle renderDataNumSquares;
    private static Object scratch;

    private PuddleBatchAccess() {}

    static void ensureInit() throws ReflectiveOperationException {
        if (initialized) {
            return;
        }
        ClassLoader loader = FBORenderCell.class.getClassLoader();
        Class<?> perPlayerDataClass =
                Class.forName(
                        "zombie.iso.fboRenderChunk.FBORenderCell$PerPlayerData", false, loader);
        perPlayerDataField = FBORenderCell.class.getDeclaredField("perPlayerData");
        perPlayerDataField.setAccessible(true);
        onScreenChunksField = perPlayerDataClass.getDeclaredField("onScreenChunks");
        onScreenChunksField.setAccessible(true);

        Class<?> renderDataClass =
                Class.forName(
                        "zombie.iso.IsoPuddles$RenderData",
                        false,
                        IsoPuddles.class.getClassLoader());
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        Method clear = renderDataClass.getDeclaredMethod("clear");
        clear.setAccessible(true);
        renderDataClear =
                lookup.unreflect(clear).asType(MethodType.methodType(void.class, Object.class));

        Method addSquare = null;
        for (Method candidate : renderDataClass.getDeclaredMethods()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (candidate.getName().equals("addSquare")
                    && params.length == 3
                    && params[0] == int.class
                    && params[1] == IsoPuddlesGeometry.class
                    && !params[2].isPrimitive()) {
                addSquare = candidate;
                break;
            }
        }
        if (addSquare == null) {
            throw new NoSuchMethodException(
                    "IsoPuddles$RenderData.addSquare(int, IsoPuddlesGeometry, Tiles)");
        }
        addSquare.setAccessible(true);
        renderDataAddSquare =
                lookup.unreflect(addSquare)
                        .asType(
                                MethodType.methodType(
                                        void.class,
                                        Object.class,
                                        int.class,
                                        IsoPuddlesGeometry.class,
                                        Object.class));

        Field data = renderDataClass.getDeclaredField("data");
        data.setAccessible(true);
        if (data.getType() != float[].class) {
            throw new NoSuchFieldException("IsoPuddles$RenderData.data is not float[]");
        }
        renderDataData =
                lookup.unreflectGetter(data)
                        .asType(MethodType.methodType(float[].class, Object.class));

        Field numSquares = renderDataClass.getDeclaredField("numSquares");
        numSquares.setAccessible(true);
        if (numSquares.getType() != int.class) {
            throw new NoSuchFieldException("IsoPuddles$RenderData.numSquares is not int");
        }
        renderDataNumSquares =
                lookup.unreflectGetter(numSquares)
                        .asType(MethodType.methodType(int.class, Object.class));

        Constructor<?> ctor = renderDataClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        scratch = ctor.newInstance();
        initialized = true;
    }

    @SuppressWarnings("unchecked")
    static ArrayList<IsoChunk> onScreenChunks(FBORenderCell cell, int playerIndex)
            throws IllegalAccessException {
        Object perPlayerData = ((Object[]) perPlayerDataField.get(cell))[playerIndex];
        return (ArrayList<IsoChunk>) onScreenChunksField.get(perPlayerData);
    }

    static void scratchClear() throws Throwable {
        renderDataClear.invokeExact(scratch);
    }

    /** Packs one square with the vanilla packer, the way {@code IsoPuddles.render} calls it. */
    static void scratchAddSquare(int z, IsoPuddlesGeometry geometry) throws Throwable {
        renderDataAddSquare.invokeExact(scratch, z, geometry, (Object) null);
    }

    static float[] scratchData() throws Throwable {
        return (float[]) renderDataData.invokeExact(scratch);
    }

    static int scratchCount() throws Throwable {
        return (int) renderDataNumSquares.invokeExact(scratch);
    }
}
