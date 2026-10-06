package io.pzstorm.storm.patch.performance;

import java.util.ArrayList;
import java.util.HashMap;
import zombie.debug.DebugType;
import zombie.iso.zones.Zone;

/** Stable, in-place compaction by vanilla's x/y/w/h key (deliberately not name, type or z). */
public final class VehicleZoneDeduplicator {
    private VehicleZoneDeduplicator() {}

    public static boolean compact(ArrayList<Zone> zones) {
        // Leave malformed lists to vanilla, including its exception/partial-removal behavior.
        if (zones == null || zones.contains(null)) return false;
        HashMap<Bounds, Zone> first = new HashMap<>();
        ArrayList<Zone> kept = new ArrayList<>(zones.size());
        ArrayList<Duplicate> duplicates = new ArrayList<>();
        for (Zone zone : zones) {
            Bounds bounds = new Bounds(zone.getX(), zone.getY(), zone.w, zone.h);
            Zone original = first.putIfAbsent(bounds, zone);
            if (original == null) kept.add(zone);
            else duplicates.add(new Duplicate(zone, original));
        }
        if (duplicates.isEmpty()) return true;
        for (Duplicate duplicate : duplicates) {
            Zone a = duplicate.removed, b = duplicate.original;
            DebugType.Vehicle.debugln(
                    "checkVehiclesZones: ERROR! Zone '"
                            + a.name
                            + "':'"
                            + a.type
                            + "' ("
                            + a.x
                            + ", "
                            + a.y
                            + ") duplicate with Zone '"
                            + b.name
                            + "':'"
                            + b.type
                            + "' ("
                            + b.x
                            + ", "
                            + b.y
                            + ")");
        }
        for (int i = 0; i < kept.size(); i++) zones.set(i, kept.get(i));
        zones.subList(kept.size(), zones.size()).clear();
        return true;
    }

    private record Bounds(int x, int y, int w, int h) {}

    private record Duplicate(Zone removed, Zone original) {}
}
