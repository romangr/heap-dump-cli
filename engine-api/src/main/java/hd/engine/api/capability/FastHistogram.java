package hd.engine.api.capability;

import hd.engine.api.Cancel;
import hd.engine.api.Capability;

import java.util.List;

/** Native per-class histogram (count and shallow size per class). */
public interface FastHistogram extends Capability {

    List<Row> histogram(Cancel cancel);

    record Row(long classAddress, String className, long loaderAddress, long count, long shallow) {}
}
