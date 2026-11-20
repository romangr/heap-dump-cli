package hd.analysis;

import hd.engine.api.Addresses;
import hd.engine.api.ClassView;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot plus caches used by views and detectors (class metadata, subclass checks). One per open snapshot.
 */
public final class Heap {

    private final Snapshot snapshot;
    private final Map<Long, ClassView> classes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> subclassCache = new ConcurrentHashMap<>();
    private final long usedHeap;

    public Heap(Snapshot snapshot) {
        this.snapshot = snapshot;
        this.usedHeap = snapshot.info().usedHeap();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public long usedHeap() {
        return usedHeap;
    }

    public Size size(long bytes) {
        return Size.of(bytes, usedHeap);
    }

    public ClassView classView(long classAddress) {
        return classes.computeIfAbsent(classAddress, snapshot::clazz);
    }

    public ClassView classOf(long objectAddress) {
        return classView(snapshot.object(objectAddress).classAddress());
    }

    /** Whether the class at {@code classAddress} is {@code superName} or extends it (interfaces not covered). */
    public boolean isSubclassOf(long classAddress, String superName) {
        String key = classAddress + "/" + superName;
        Boolean cached = subclassCache.get(key);
        if (cached != null) return cached;
        boolean result = false;
        int guard = 0;
        for (long c = classAddress; c != Addresses.NULL && guard++ < 200; ) {
            ClassView cv = classView(c);
            if (cv.name().equals(superName)) {
                result = true;
                break;
            }
            c = cv.superclassAddress();
        }
        subclassCache.put(key, result);
        return result;
    }

    public boolean isInstanceOf(long objectAddress, String className) {
        return isSubclassOf(snapshot.object(objectAddress).classAddress(), className);
    }

    public ObjectView object(long address) {
        return snapshot.object(address);
    }
}
