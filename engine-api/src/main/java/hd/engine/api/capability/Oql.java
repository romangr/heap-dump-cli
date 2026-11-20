package hd.engine.api.capability;

import hd.engine.api.Cancel;
import hd.engine.api.Capability;

import java.util.List;

/** Engine-specific object query language (MAT OQL). */
public interface Oql extends Capability {

    /**
     * @param maxRows stop after this many rows
     * @throws hd.engine.api.EngineException INVALID_ARGUMENT for syntax errors
     */
    Result query(String query, int maxRows, Cancel cancel);

    /**
     * @param columns column names; a single column "address" if the query selects objects
     * @param rows cell values: String, Number, Boolean or {@link ObjectRef}
     * @param totalRows total number of rows (may exceed rows.size())
     */
    record Result(List<String> columns, List<List<Object>> rows, long totalRows) {}

    /** A cell that refers to an object. */
    record ObjectRef(long address) {}
}
