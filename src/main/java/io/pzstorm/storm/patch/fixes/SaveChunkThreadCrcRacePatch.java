package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the shared-{@code CRC32} race in {@code ServerChunkLoader$SaveChunkThread.addLoadedJob}.
 *
 * <p>{@code addLoadedJob} serializes the chunk on the <b>calling</b> thread before queueing the
 * result, passing the thread object's single shared {@code crc32} field into {@code
 * IsoChunk.SaveLoadedChunk} → {@code IsoChunk.Save}, which finishes with a reset/update/getValue
 * sequence and embeds the value in the chunk file header. During {@code ServerMap.SaveAll} with
 * &ge;10 loaded cells, four {@code WorkerThread}s run {@code ServerCell.Save(true)} → {@code
 * addLoadedJob} concurrently, interleaving on that one {@code CRC32} — a fraction of every periodic
 * save's chunks get a garbage embedded checksum, and each later load of those chunks logs "CRC
 * mismatch" from {@code sanityCheck.checkCRC} (log-only; the chunk bytes are task-local and
 * correct, so no data is harmed — but the map log fills with alarming noise after every save).
 *
 * <p>The substitution redirects the {@code crc32} field read inside {@code addLoadedJob} to {@link
 * io.pzstorm.storm.map.StormChunkSaveCrc#crc(Object)}, a per-thread instance. The constructor's
 * field write is untouched (the now-unread field stays initialized). See {@code
 * SaveLoadedTaskCrcRacePatch} for the sibling race on the outer {@code crcSave}.
 *
 * <p>Fail-loud while a {@code CRC32} field still exists: the hook is name-string based, so a vanilla
 * rename would otherwise silently no-op and reintroduce the race. {@link #dynamicType} throws if
 * {@code crc32} is gone but another {@code CRC32} field remains, or if {@code crc32} remains and
 * {@code addLoadedJob} does not. Build 42.21 removed the field and checksums a local {@code CRC32}
 * instead; that shape skips once with a log instead of aborting the JVM.
 *
 * <p>Registration-gated to the dedicated server ({@code StormEnv.isStormServer()}).
 */
public class SaveChunkThreadCrcRacePatch extends StormClassTransformer {

    private static final String SCRATCH = "io.pzstorm.storm.map.StormChunkSaveCrc";
    private static final String CRC32 = "java.util.zip.CRC32";
    private static final AtomicBoolean SKIP_LOGGED = new AtomicBoolean();

    public SaveChunkThreadCrcRacePatch() {
        super("zombie.network.ServerChunkLoader$SaveChunkThread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(className).resolve();
        boolean hasCrc32 = !target.getDeclaredFields().filter(ElementMatchers.named("crc32")).isEmpty();
        boolean hasOtherCrc32 =
                !target.getDeclaredFields()
                        .filter(
                                ElementMatchers.fieldType(ElementMatchers.named(CRC32))
                                        .and(ElementMatchers.not(ElementMatchers.named("crc32"))))
                        .isEmpty();
        if (!hasCrc32) {
            if (hasOtherCrc32) {
                throw new IllegalStateException(
                        "SaveChunkThreadCrcRacePatch: ServerChunkLoader$SaveChunkThread no longer"
                                + " declares field crc32 but still declares another CRC32 field —"
                                + " refusing to skip because a renamed shared checksum would"
                                + " silently no-op. Re-verify the patch against the current game"
                                + " source.");
            }
            if (SKIP_LOGGED.compareAndSet(false, true)) {
                LOGGER.error(
                        "SaveChunkThreadCrcRacePatch skipped: ServerChunkLoader$SaveChunkThread"
                                + " has no crc32 field and no CRC32 field under another name."
                                + " Vanilla checksums a local CRC32; the shared-field hook is not"
                                + " applied.");
            }
            return builder;
        }
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("addLoadedJob"))
                        .isEmpty(),
                "method addLoadedJob");

        MethodDescription replacement =
                typePool.describe(SCRATCH)
                        .resolve()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("crc"))
                        .getOnly();

        return builder.visit(
                MemberSubstitution.relaxed()
                        .field(
                                ElementMatchers.named("crc32")
                                        .and(ElementMatchers.isDeclaredBy(target)))
                        .onRead()
                        .replaceWith(replacement)
                        .on(ElementMatchers.named("addLoadedJob")));
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "SaveChunkThreadCrcRacePatch: ServerChunkLoader$SaveChunkThread no longer"
                            + " declares "
                            + member
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " embedded-checksum race behind the \"CRC mismatch\" log spam."
                            + " Re-verify the patch against the current game source.");
        }
    }
}
