package hd.analysis.detect;

import hd.analysis.Heap;
import hd.analysis.JdkLayouts;
import hd.engine.api.Addresses;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Several class loaders of the same type that define the same set of classes: the signature of a redeploy leak,
 * where old application/plugin loaders stay reachable.
 */
public final class ClassLoaderDetector implements Detector {

    @Override
    public String id() {
        return "classloader";
    }

    @Override
    public String description() {
        return "duplicate class loaders defining the same classes (redeploy / plugin reload leaks)";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public List<Finding> run(DetectContext ctx) {
        Heap heap = ctx.heap();
        Snapshot s = heap.snapshot();
        Map<Long, TreeSet<String>> defined = new HashMap<>(); // loader → class names
        s.classes().forEach(c -> {
            if (c.loaderAddress() != Addresses.NULL) {
                defined.computeIfAbsent(c.loaderAddress(), k -> new TreeSet<>()).add(c.name());
            }
        });
        // group loaders by (loader class, defined class names)
        Map<String, List<Long>> groups = new HashMap<>();
        for (var e : defined.entrySet()) {
            ctx.cancel().check();
            String loaderClass;
            try {
                loaderClass = heap.object(e.getKey()).className();
            } catch (RuntimeException ex) {
                continue;
            }
            String key = loaderClass + "\n" + String.join(",", e.getValue());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey());
        }
        List<Finding> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<Long> loaders = e.getValue();
            if (loaders.size() < 2) continue;
            String loaderClass = e.getKey().substring(0, e.getKey().indexOf('\n'));
            TreeSet<String> classes = defined.get(loaders.get(0));
            loaders.sort(Comparator.comparingLong(s::retainedSize).reversed());
            long total = loaders.stream().mapToLong(s::retainedSize).sum();
            List<String> names = new ArrayList<>();
            for (long l : loaders.subList(0, Math.min(5, loaders.size()))) {
                String n = JdkLayouts.metadataString(heap, heap.object(l).refField("name"));
                names.add(n == null ? Addresses.hex(l) : "\"" + n + "\"");
            }
            String sample = String.join(", ", classes.stream().limit(3).toList()) + (classes.size() > 3 ? ", …" : "");
            Finding.Severity sev = Finding.bySize(total, heap.usedHeap());
            if (sev == Finding.Severity.INFO) sev = Finding.Severity.LOW; // duplicates are suspicious regardless of size
            out.add(new Finding(id(), sev,
                    loaders.size() + " " + loaderClass + " instances define the same " + classes.size() + " class(es)",
                    String.format("%d class loaders of type %s (%s) each define the same %d class(es) (%s). "
                                    + "Usually only one should be alive; the others are likely leaked by a redeploy or reload. "
                                    + "Together they retain at least %s. Check what keeps the older ones reachable.",
                            loaders.size(), loaderClass, String.join(", ", names), classes.size(), sample,
                            heap.size(total).human()),
                    new Finding.Evidence(loaders.stream().limit(5).map(Addresses::hex).toList(), loaders.size(), heap.size(total)),
                    loaders.stream().limit(2).map(l -> "hd path-to-roots {session} " + Addresses.hex(l)).toList()));
        }
        return out;
    }
}
