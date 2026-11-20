package hd.analysis;

import java.util.List;

/**
 * One page of a larger, sorted result.
 *
 * @param total total number of items before paging
 */
public record Page<T>(List<T> items, long total, int offset, int limit) {

    public static <T> Page<T> of(List<T> all, Limits limits) {
        int from = Math.min(all.size(), limits.offset());
        int to = (int) Math.min(all.size(), (long) from + limits.limit());
        return new Page<>(List.copyOf(all.subList(from, to)), all.size(), limits.offset(), limits.limit());
    }

    public boolean hasMore() {
        return offset + items.size() < total;
    }
}
