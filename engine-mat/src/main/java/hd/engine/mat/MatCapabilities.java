package hd.engine.mat;

import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.EngineException;
import hd.engine.api.Progress;
import hd.engine.api.capability.FastGcRootPaths;
import hd.engine.api.capability.FastHistogram;
import hd.engine.api.capability.Oql;
import hd.engine.api.capability.RetainedSetSize;
import org.eclipse.mat.snapshot.ClassHistogramRecord;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.OQLParseException;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.util.IProgressListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MAT implementations of the optional capabilities. */
final class MatCapabilities {

    private MatCapabilities() {}

    record Histogram(MatSnapshot s) implements FastHistogram {
        @Override
        public List<Row> histogram(Cancel cancel) {
            var h = guard(() -> MatSnapshot.call(() -> s.mat().getHistogram(listener(cancel))));
            List<Row> rows = new ArrayList<>();
            for (ClassHistogramRecord r : h.getClassHistogramRecords()) {
                IClass c = (IClass) MatSnapshot.call(() -> s.mat().getObject(r.getClassId()));
                rows.add(new Row(c.getObjectAddress(), c.getName(), c.getClassLoaderAddress(),
                        r.getNumberOfObjects(), r.getUsedHeapSize()));
            }
            return rows;
        }
    }

    record GcRootPaths(MatSnapshot s) implements FastGcRootPaths {

        private static final Map<RefKind, String> REF_CLASSES = new EnumMap<>(Map.of(
                RefKind.WEAK, "java.lang.ref.WeakReference",
                RefKind.SOFT, "java.lang.ref.SoftReference",
                RefKind.PHANTOM, "java.lang.ref.PhantomReference",
                RefKind.FINAL, "java.lang.ref.Finalizer"));

        @Override
        public List<long[]> paths(long address, Set<RefKind> exclude, int maxPaths, Cancel cancel) {
            int id = s.id(address);
            Map<IClass, Set<String>> excludeMap = excludeMap(exclude);
            IPathsFromGCRootsComputer computer = MatSnapshot.call(() -> s.mat().getPathsFromGCRoots(id, excludeMap));
            List<long[]> paths = new ArrayList<>();
            while (paths.size() < maxPaths) {
                cancel.check();
                int[] path = MatSnapshot.call(computer::getNextShortestPath);
                if (path == null) {
                    break;
                }
                paths.add(s.addrs(path).toArray());
            }
            return paths;
        }

        /** Referent fields of the excluded reference classes and all their subclasses. */
        private Map<IClass, Set<String>> excludeMap(Set<RefKind> exclude) {
            if (exclude == null || exclude.isEmpty()) {
                return null;
            }
            Map<IClass, Set<String>> map = new HashMap<>();
            for (RefKind kind : exclude) {
                Collection<IClass> classes = MatSnapshot.call(() -> s.mat().getClassesByName(REF_CLASSES.get(kind), true));
                if (classes == null) {
                    continue;
                }
                for (IClass c : classes) {
                    map.computeIfAbsent(c, k -> new HashSet<>()).add("referent");
                }
            }
            return map.isEmpty() ? null : map;
        }
    }

    record RetainedSet(MatSnapshot s) implements RetainedSetSize {
        @Override
        public long retainedSize(long[] addresses, Cancel cancel) {
            int[] ids = new int[addresses.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = s.id(addresses[i]);
            }
            int[] retained = guard(() -> MatSnapshot.call(() -> s.mat().getRetainedSet(ids, listener(cancel))));
            return MatSnapshot.call(() -> s.mat().getHeapSize(retained));
        }
    }

    record MatOql(MatSnapshot s) implements Oql {
        @Override
        public Result query(String query, int maxRows, Cancel cancel) {
            IOQLQuery q;
            try {
                q = SnapshotFactory.createQuery(query);
            } catch (OQLParseException e) {
                throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "OQL syntax error: " + e.getMessage(), e);
            } catch (org.eclipse.mat.SnapshotException e) {
                throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "OQL: " + e.getMessage(), e);
            }
            Object result;
            try {
                result = guard(() -> {
                    try {
                        return q.execute(s.mat(), listener(cancel));
                    } catch (org.eclipse.mat.SnapshotException e) {
                        // e.g. unknown class in FROM clause
                        throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "OQL: " + e.getMessage(), e);
                    }
                });
            } catch (EngineException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "OQL failed: " + e.getMessage(), e);
            }
            if (result == null) {
                return new Result(List.of("address"), List.of(), 0);
            }
            if (result instanceof int[] ids) {
                List<List<Object>> rows = new ArrayList<>();
                for (int i = 0; i < Math.min(ids.length, maxRows); i++) {
                    rows.add(List.of(new ObjectRef(s.addr(ids[i]))));
                }
                return new Result(List.of("address"), rows, ids.length);
            }
            if (result instanceof IResultTable table) {
                Column[] cols = table.getColumns();
                List<String> names = new ArrayList<>();
                for (Column c : cols) {
                    names.add(c.getLabel());
                }
                List<List<Object>> rows = new ArrayList<>();
                int n = Math.min(table.getRowCount(), maxRows);
                for (int r = 0; r < n; r++) {
                    cancel.check();
                    Object row = table.getRow(r);
                    List<Object> cells = new ArrayList<>(cols.length);
                    for (int c = 0; c < cols.length; c++) {
                        cells.add(cell(table.getColumnValue(row, c)));
                    }
                    rows.add(cells);
                }
                return new Result(names, rows, table.getRowCount());
            }
            return new Result(List.of("value"), List.of(List.of(cell(result))), 1);
        }

        private Object cell(Object v) {
            if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) {
                return v;
            }
            if (v instanceof IObject o) {
                return new ObjectRef(o.getObjectAddress());
            }
            if (v instanceof Character ch) {
                return String.valueOf(ch);
            }
            return String.valueOf(v);
        }
    }

    // ---- helpers

    static IProgressListener listener(Cancel cancel) {
        return new MatProgress(Progress.NONE, cancel);
    }

    interface Body<T> {
        T run();
    }

    /** Maps MAT's cancellation exception to {@link EngineException}. */
    static <T> T guard(Body<T> body) {
        try {
            return body.run();
        } catch (IProgressListener.OperationCanceledException e) {
            throw new EngineException(EngineException.Code.CANCELLED, "operation cancelled", e);
        }
    }

    static String hex(long a) {
        return Addresses.hex(a);
    }
}
