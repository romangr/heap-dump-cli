package hd.engine.api.capability;

import hd.engine.api.Cancel;
import hd.engine.api.Capability;

/** Exact retained size of a set of objects (what would be freed if all of them were gone). */
public interface RetainedSetSize extends Capability {

    long retainedSize(long[] addresses, Cancel cancel);
}
