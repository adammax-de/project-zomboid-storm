package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import org.junit.jupiter.api.Test;
import zombie.iso.BuildingDef;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.RoomDef;
import zombie.iso.zones.Zone;

class MetaGridLoadTest implements UnitTest {
    private static Zone zone(int x, int y, int z, int w, int h, String name) throws Exception {
        Zone zone = (Zone) ServerLoadTestSupport.unsafe().allocateInstance(Zone.class);
        zone.x = x;
        zone.y = y;
        zone.z = z;
        zone.w = w;
        zone.h = h;
        zone.name = name;
        zone.type = name + "-type";
        return zone;
    }

    private static Object grid(Class<?> type, ArrayList<Zone> zones) throws Exception {
        Object grid = ServerLoadTestSupport.unsafe().allocateInstance(type);
        Field field = type.getDeclaredField("vehiclesZones");
        field.setAccessible(true);
        field.set(grid, zones);
        return grid;
    }

    @Test
    void vehicleZoneRemovalMatchesNativeObjectsAndOrdering() throws Exception {
        Random random = new Random(4221);
        Method woven =
                ServerLoadTestSupport.woven(new VehicleZoneDedupPatch())
                        .getMethod("checkVehiclesZones");
        for (int pass = 0; pass < 50; pass++) {
            ArrayList<Zone> input = new ArrayList<>();
            for (int i = 0; i < 100; i++)
                input.add(
                        zone(
                                random.nextInt(9) - 4,
                                random.nextInt(9) - 4,
                                random.nextInt(8),
                                1 + random.nextInt(3),
                                1 + random.nextInt(3),
                                "zone-" + i));
            Zone a = input.get(0);
            input.add(zone(a.x, a.y, a.z + 10, a.w, a.h, "different-name-type-and-floor"));
            input.add(a);
            ArrayList<Zone> expected = new ArrayList<>(input), actual = new ArrayList<>(input);
            ((IsoMetaGrid) grid(IsoMetaGrid.class, expected)).checkVehiclesZones();
            Object modified = grid(woven.getDeclaringClass(), actual);
            woven.invoke(modified);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
            // A later call rebuilds the key set after a zone edit rather than trusting stale state.
            a.x += 100;
            assertTrue(VehicleZoneDeduplicator.compact(actual));
            assertEquals(expected, actual);
        }
    }

    @Test
    void malformedListsUseTheVanillaPathWithoutPrematureMutation() throws Exception {
        Zone a = zone(1, 2, 0, 3, 4, "a");
        ArrayList<Zone> list = new ArrayList<>();
        Collections.addAll(list, a, a, null);
        assertFalse(VehicleZoneDeduplicator.compact(list));
        assertEquals(3, list.size());
        assertTrue(VehicleZoneDeduplicator.compact(new ArrayList<>()));
    }

    @Test
    void scopedRoomIndicesMatchIndexOfAndRefreshAfterEdits() {
        IsoMetaCell cell = new IsoMetaCell(-4, 10);
        RoomDef a = new RoomDef(1L, "a"), b = new RoomDef(2L, "b");
        Collections.addAll(cell.roomList, a, b, a, null);
        for (int pass = 0; pass < 2; pass++) {
            RoomIdLookup.Scope scope = RoomIdLookup.begin(cell);
            try {
                for (Object value :
                        new Object[] {a, b, null, new RoomDef(3L, "missing"), "unexpected"})
                    assertEquals(
                            cell.roomList.indexOf(value),
                            RoomIdLookup.indexOf(cell.roomList, value));
                IsoMetaCell inner = new IsoMetaCell(0, 0);
                inner.roomList.add(b);
                RoomIdLookup.Scope nested = RoomIdLookup.begin(inner);
                try {
                    assertEquals(0, RoomIdLookup.indexOf(inner.roomList, b));
                } finally {
                    RoomIdLookup.end(nested);
                }
                assertEquals(cell.roomList.indexOf(b), RoomIdLookup.indexOf(cell.roomList, b));
            } finally {
                RoomIdLookup.end(scope);
            }
            Collections.reverse(cell.roomList);
            assertEquals(cell.roomList.indexOf(a), RoomIdLookup.indexOf(cell.roomList, a));
        }
    }

    @Test
    void nativeValidationKeepsChecksAndCleansScopeAfterException() throws Exception {
        byte[] bytes = ServerLoadTestSupport.transformed(new BuildingRoomIdLookupPatch());
        byte[] original = ServerLoadTestSupport.original(new BuildingRoomIdLookupPatch());
        for (String owner :
                new String[] {
                    "zombie/iso/RoomID", "zombie/iso/BuildingID", "zombie/debug/DebugType"
                }) {
            String method = owner.endsWith("DebugType") ? "error" : "makeID";
            int nativeCalls =
                    ServerLoadTestSupport.calls(original, "checkBuildingAndRoomIDs", owner, method);
            assertTrue(nativeCalls > 0, "Expected native validation: " + owner);
            assertEquals(
                    nativeCalls,
                    ServerLoadTestSupport.calls(bytes, "checkBuildingAndRoomIDs", owner, method),
                    "Keep native validation and diagnostics: " + owner);
        }
        assertEquals(
                2,
                ServerLoadTestSupport.calls(
                        bytes,
                        "checkBuildingAndRoomIDs",
                        "io/pzstorm/storm/patch/performance/RoomIdLookup",
                        "indexOf"));
        assertEquals(
                0,
                ServerLoadTestSupport.calls(
                        bytes, "checkBuildingAndRoomIDs", "java/util/ArrayList", "indexOf"));
        Class<?> type = ServerLoadTestSupport.woven(new BuildingRoomIdLookupPatch());
        Object editor = ServerLoadTestSupport.unsafe().allocateInstance(type);
        Method validate = type.getMethod("checkBuildingAndRoomIDs", IsoMetaCell.class);
        IsoMetaCell cell = new IsoMetaCell(0, 0);
        validate.invoke(editor, cell);
        RoomDef a = new RoomDef(0L, "a"), b = new RoomDef(1L, "b");
        Collections.addAll(cell.roomList, a, b);
        cell.rooms.put(0L, a);
        cell.rooms.put(1L, b);
        BuildingDef building =
                (BuildingDef) ServerLoadTestSupport.unsafe().allocateInstance(BuildingDef.class);
        for (String fieldName : new String[] {"rooms", "emptyoutside"}) {
            Field field = BuildingDef.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(building, new ArrayList<RoomDef>());
        }
        building.id = 0L;
        building.rooms.add(a);
        building.emptyoutside.add(b);
        cell.buildings.add(building);
        validate.invoke(editor, cell);
        building.rooms.add(null);
        assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> validate.invoke(editor, cell));
        Collections.reverse(cell.roomList);
        assertEquals(
                1,
                RoomIdLookup.indexOf(cell.roomList, a),
                "Scope must be released on exceptional exit");
    }
}
