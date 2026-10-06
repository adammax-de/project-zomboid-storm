package io.pzstorm.storm.advice.serverload;

import io.pzstorm.storm.patch.performance.VehicleZoneDeduplicator;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.iso.zones.Zone;

public final class VehicleZoneDedupAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.FieldValue("vehiclesZones") ArrayList<Zone> zones) {
        return VehicleZoneDeduplicator.compact(zones);
    }
}
