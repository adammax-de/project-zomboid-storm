package io.pzstorm.storm.patch.performance;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import zombie.iso.IsoMetaCell;
import zombie.iso.RoomDef;

/** A first-index lookup scoped to one native room-ID validation, never retained across edits. */
public final class RoomIdLookup {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private RoomIdLookup() {}

    public static Scope begin(Object cell) {
        Scope scope = new Scope(((IsoMetaCell) cell).roomList, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static void end(Scope scope) {
        if (scope == null) return;
        if (scope.previous == null) CURRENT.remove();
        else CURRENT.set(scope.previous);
    }

    public static int indexOf(ArrayList<?> list, Object value) {
        Scope scope = CURRENT.get();
        // Final RoomDef inherits Object.equals. Unexpected query types keep native equality.
        if (scope == null
                || scope.list != list
                || scope.size != list.size()
                || (value != null && value.getClass() != RoomDef.class)) return list.indexOf(value);
        return scope.indices.getOrDefault(value, -1);
    }

    public static final class Scope {
        private final Scope previous;
        private final ArrayList<?> list;
        private final int size;
        private final IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>();

        private Scope(ArrayList<?> list, Scope previous) {
            this.previous = previous;
            this.list = list;
            this.size = list.size();
            for (int i = 0; i < size; i++) indices.putIfAbsent(list.get(i), i);
        }
    }
}
