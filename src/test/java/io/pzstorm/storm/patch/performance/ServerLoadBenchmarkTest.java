package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.RoomDef;
import zombie.iso.zones.Zone;
import zombie.scripting.ScriptParser;

/** Opt-in local timing evidence; no machine-dependent timing threshold in correctness tests. */
class ServerLoadBenchmarkTest implements UnitTest {
    private static volatile Object sink;

    @Test
    void compareLoadingHotspots() throws Exception {
        assumeTrue(Boolean.getBoolean("storm.loadtest.benchmark"));
        String script = "value, /* a comment with some text */\n".repeat(10_000);
        assertEquals(ScriptParser.stripComments(script), ScriptCommentScanner.scan(script));
        measure(
                "stripComments / 10000 comments",
                () -> sink = ScriptParser.stripComments(script),
                () -> sink = ScriptCommentScanner.scan(script));

        IsoMetaCell cell = new IsoMetaCell(0, 0);
        for (int i = 0; i < 10_000; i++) cell.roomList.add(new RoomDef(i, "room"));
        measure(
                "room indices / 10000 rooms",
                () -> {
                    long total = 0;
                    for (RoomDef room : cell.roomList) total += cell.roomList.indexOf(room);
                    sink = total;
                },
                () -> {
                    RoomIdLookup.Scope scope = RoomIdLookup.begin(cell);
                    try {
                        long total = 0;
                        for (RoomDef room : cell.roomList)
                            total += RoomIdLookup.indexOf(cell.roomList, room);
                        sink = total;
                    } finally {
                        RoomIdLookup.end(scope);
                    }
                });

        ArrayList<Zone> zones = new ArrayList<>();
        for (int i = 0; i < 9690; i++) {
            Zone zone = (Zone) ServerLoadTestSupport.unsafe().allocateInstance(Zone.class);
            zone.x = i;
            zone.y = 10;
            zone.w = 2;
            zone.h = 5;
            zones.add(zone);
        }
        IsoMetaGrid grid =
                (IsoMetaGrid) ServerLoadTestSupport.unsafe().allocateInstance(IsoMetaGrid.class);
        Field field = IsoMetaGrid.class.getDeclaredField("vehiclesZones");
        field.setAccessible(true);
        field.set(grid, zones);
        measure(
                "vehicle zone check / 9690 unique zones",
                grid::checkVehiclesZones,
                () -> sink = VehicleZoneDeduplicator.compact(zones));
        assertEquals(9690, zones.size());
    }

    private static void measure(String label, Runnable nativePath, Runnable patchedPath) {
        for (int i = 0; i < 3; i++) {
            nativePath.run();
            patchedPath.run();
        }
        long[] original = new long[7], patched = new long[7];
        for (int i = 0; i < original.length; i++) {
            long start = System.nanoTime();
            nativePath.run();
            original[i] = System.nanoTime() - start;
            start = System.nanoTime();
            patchedPath.run();
            patched[i] = System.nanoTime() - start;
        }
        Arrays.sort(original);
        Arrays.sort(patched);
        System.out.printf(
                "%s: native %.3f ms, patched %.3f ms (median, includes lookup construction)%n",
                label, original[3] / 1e6, patched[3] / 1e6);
    }
}
