package hd.engine.api;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * An opened heap dump. All identities are heap addresses. Implementations must allow concurrent reads.
 * Streams may be large (hundreds of millions of elements): consume them lazily.
 */
public interface Snapshot extends AutoCloseable {

    /** Virtual root of the dominator tree: {@code dominated(ROOT)} yields the top-level dominators. */
    long ROOT = -1L;

    SnapshotInfo info();

    // ---- objects and classes

    /** @throws EngineException OBJECT_NOT_FOUND */
    ObjectView object(long address);

    /** @throws EngineException OBJECT_NOT_FOUND if the address is not a class */
    ClassView clazz(long classAddress);

    Stream<ClassView> classes();

    /** All classes with this exact name (several if loaded by several class loaders). */
    List<ClassView> classesByName(String name);

    /** Instances of exactly this class (not subclasses). */
    LongStream instancesOf(long classAddress);

    /** Address of the object's class; cheaper than {@code object(address).classAddress()} on most engines. */
    default long classOf(long address) {
        return object(address).classAddress();
    }

    // ---- graph

    /** Outgoing references, including the reference to the object's class. */
    LongStream outbound(long address);

    LongStream inbound(long address);

    long shallowSize(long address);

    long retainedSize(long address);

    // ---- dominator tree

    /** Immediate dominator, or {@link #ROOT} for top-level objects. */
    long immediateDominator(long address);

    /** Objects immediately dominated by {@code address} (use {@link #ROOT} for the top level). Unordered. */
    LongStream dominated(long address);

    // ---- roots and threads

    Stream<GcRoot> gcRoots();

    /** GC root types of this object; empty if it is not a root. */
    List<GcRoot.Type> rootTypes(long address);

    /** Threads with stack data; empty if the dump has none. */
    Stream<ThreadView> threads();

    // ---- optional capabilities

    <C extends Capability> Optional<C> capability(Class<C> type);

    @Override
    void close();
}
