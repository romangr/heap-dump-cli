package hd.engine.api.capability;

import hd.engine.api.Cancel;
import hd.engine.api.Capability;

import java.util.List;
import java.util.Set;

/** Native shortest-paths-to-GC-roots computation. */
public interface FastGcRootPaths extends Capability {

    /**
     * @param exclude reference kinds whose referent edges are ignored
     * @return up to maxPaths paths; each path starts at {@code address} and ends at a GC root
     */
    List<long[]> paths(long address, Set<RefKind> exclude, int maxPaths, Cancel cancel);

    enum RefKind { WEAK, SOFT, PHANTOM, FINAL }
}
