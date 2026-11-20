package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.engine.api.Cancel;
import hd.engine.api.EngineException;
import hd.engine.api.capability.Oql;

import java.util.ArrayList;
import java.util.List;

/**
 * Passes an OQL query to the engine. Object cells become {@link ObjRef}s; string cells are redacted unless values
 * are shown (OQL can select arbitrary string contents).
 */
public final class OqlView {

    public record Result(String query, List<String> columns, Page<List<Object>> rows) {}

    private OqlView() {}

    public static Result run(Heap heap, String query, Limits limits, Redaction redaction, Cancel cancel) {
        Oql oql = heap.snapshot().capability(Oql.class)
                .orElseThrow(() -> EngineException.unsupported(Oql.class));
        Oql.Result r = oql.query(query, limits.offset() + limits.limit(), cancel);
        List<List<Object>> rows = new ArrayList<>();
        for (int i = Math.min(limits.offset(), r.rows().size()); i < r.rows().size(); i++) {
            List<Object> cells = new ArrayList<>();
            for (Object c : r.rows().get(i)) {
                if (c instanceof Oql.ObjectRef ref) {
                    cells.add(ObjRef.of(heap, ref.address(), redaction));
                } else if (c instanceof String str && !redaction.showValues()) {
                    cells.add(new Redaction.Redacted(str.length()));
                } else {
                    cells.add(c);
                }
            }
            rows.add(cells);
        }
        return new Result(query, r.columns(), new Page<>(rows, r.totalRows(), limits.offset(), limits.limit()));
    }
}
