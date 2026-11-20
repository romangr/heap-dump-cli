package hd.analysis.detect;

import hd.analysis.Heap;
import hd.analysis.JdkLayouts;
import hd.analysis.Size;
import hd.engine.api.Addresses;
import hd.engine.api.ClassView;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Strings with identical content stored many times. Contents are hashed, never reported.
 * Opt-in: reads every string in the heap. Memory use grows with the number of distinct contents
 * (about 40 bytes each, capped at {@link #MAX_DISTINCT}).
 */
public final class DupStringsDetector implements Detector {

    static final int MIN_COPIES = 10;
    static final int MAX_DISTINCT = 50_000_000;
    static final int MAX_BYTES_HASHED = 1 << 20;

    @Override
    public String id() {
        return "dup-strings";
    }

    @Override
    public String description() {
        return "duplicate string contents (opt-in: reads every string)";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public List<Finding> run(DetectContext ctx) {
        Heap heap = ctx.heap();
        Snapshot s = heap.snapshot();
        ClassView string = s.classesByName("java.lang.String").stream().findFirst().orElse(null);
        if (string == null) return List.of();

        Groups groups = new Groups(1 << 16);
        long scanned = 0;
        long total = string.instanceCount();
        boolean capped = false;
        for (long a : s.instancesOf(string.address()).toArray()) {
            if ((++scanned & 0xfff) == 0) {
                ctx.cancel().check();
                ctx.progress().update("hashing strings", (double) scanned / Math.max(1, total));
            }
            ObjectView str = heap.object(a);
            long value = str.refField("value");
            if (value == Addresses.NULL) continue;
            ObjectView arr = heap.object(value);
            if (arr.kind() != ObjectKind.PRIMITIVE_ARRAY || arr.length() == 0) continue;
            long hash = hash(arr, JdkLayouts.intOf(str.field("coder")));
            if (!groups.add(hash, a, s.retainedSize(a), arr.length())) {
                capped = true;
                break;
            }
        }

        // collect groups with enough copies; waste = everything except the largest copy
        List<long[]> dups = new ArrayList<>(); // [count, waste, sample, length]
        long totalWaste = 0;
        for (int i = 0; i < groups.capacity(); i++) {
            if (groups.count[i] < MIN_COPIES) continue;
            long waste = groups.retainedSum[i] - groups.maxRetained[i];
            dups.add(new long[]{groups.count[i], waste, groups.sample[i], groups.length[i]});
            totalWaste += waste;
        }
        if (totalWaste < ctx.threshold()) return List.of();
        dups.sort((x, y) -> Long.compare(y[1], x[1]));

        StringBuilder top = new StringBuilder();
        for (long[] d : dups.subList(0, Math.min(5, dups.size()))) {
            if (!top.isEmpty()) top.append("; ");
            top.append(String.format("%,d copies of a %d-element string wasting %s (sample %s)",
                    d[0], d[3], Size.human(d[1]), Addresses.hex(d[2])));
        }
        long copies = dups.stream().mapToLong(d -> d[0]).sum();
        return List.of(new Finding(id(), Finding.bySize(totalWaste, heap.usedHeap()),
                String.format("%,d duplicate strings waste %s", copies, Size.human(totalWaste)),
                String.format("%,d distinct contents occur at least %d times each, wasting %s in total. Largest: %s. "
                                + "Contents are not shown; inspect a sample with hd object --show-values if needed. "
                                + "Consider String.intern(), deduplication (-XX:+UseStringDeduplication with G1) or caching parsed values.%s",
                        dups.size(), MIN_COPIES, Size.human(totalWaste), top,
                        capped ? " Note: stopped after " + MAX_DISTINCT + " distinct strings; results are partial." : ""),
                new Finding.Evidence(dups.stream().limit(5).map(d -> Addresses.hex(d[2])).toList(), copies, heap.size(totalWaste)),
                List.of("hd path-to-roots {session} " + Addresses.hex(dups.get(0)[2]))));
    }

    /** 64-bit FNV-1a over the backing array (first 1 MiB) plus coder and length. */
    static long hash(ObjectView arr, OptionalInt coder) {
        long h = 0xcbf29ce484222325L;
        h = (h ^ coder.orElse(-1)) * 0x100000001b3L;
        h = (h ^ arr.length()) * 0x100000001b3L;
        Object data = arr.readPrimitives(0, Math.min(arr.length(), MAX_BYTES_HASHED));
        if (data instanceof byte[] b) {
            for (byte x : b) h = (h ^ (x & 0xff)) * 0x100000001b3L;
        } else if (data instanceof char[] c) {
            for (char x : c) h = (h ^ x) * 0x100000001b3L;
        }
        return h;
    }

    /** Open-addressing map: content hash → copies, retained sum, max retained, sample address. */
    static final class Groups {
        long[] keys;
        int[] count;
        long[] retainedSum;
        long[] maxRetained;
        long[] sample;
        int[] length;
        int size;

        Groups(int capacity) {
            alloc(capacity);
        }

        int capacity() {
            return keys.length;
        }

        private void alloc(int capacity) {
            keys = new long[capacity];
            count = new int[capacity];
            retainedSum = new long[capacity];
            maxRetained = new long[capacity];
            sample = new long[capacity];
            length = new int[capacity];
        }

        /** @return false when the distinct-content cap is reached */
        boolean add(long hash, long addr, long retained, int len) {
            if (size >= MAX_DISTINCT) return false;
            if (size * 2 >= keys.length) grow();
            int i = slot(hash);
            if (count[i] == 0) {
                keys[i] = hash;
                sample[i] = addr;
                length[i] = len;
                size++;
            }
            count[i]++;
            retainedSum[i] += retained;
            if (retained > maxRetained[i]) maxRetained[i] = retained;
            return true;
        }

        private int slot(long hash) {
            int mask = keys.length - 1;
            int i = (int) (hash ^ (hash >>> 32)) & mask;
            while (count[i] != 0 && keys[i] != hash) i = (i + 1) & mask;
            return i;
        }

        private void grow() {
            long[] k = keys, rs = retainedSum, mr = maxRetained, sa = sample;
            int[] c = count, l = length;
            alloc(keys.length * 2);
            for (int j = 0; j < k.length; j++) {
                if (c[j] == 0) continue;
                int i = slot(k[j]);
                keys[i] = k[j];
                count[i] = c[j];
                retainedSum[i] = rs[j];
                maxRetained[i] = mr[j];
                sample[i] = sa[j];
                length[i] = l[j];
            }
        }
    }
}
