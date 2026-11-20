package hd.engine.mat;

import hd.engine.api.Cancel;
import hd.engine.api.EngineException;
import hd.engine.api.HeapEngine;
import hd.engine.api.OpenOptions;
import hd.engine.api.Progress;
import hd.engine.api.Snapshot;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.util.IProgressListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** {@link HeapEngine} backed by Eclipse Memory Analyzer's core, running without OSGi. */
public final class MatEngine implements HeapEngine {

    static final String MAT_VERSION = "1.17.0";

    @Override
    public String id() {
        return "mat";
    }

    @Override
    public String version() {
        return "mat-" + MAT_VERSION + "/adapter-1";
    }

    @Override
    public boolean hasIndex(Path dump, OpenOptions options) {
        return IndexLayout.isComplete(dump.toAbsolutePath(), options.indexDir(), version());
    }

    @Override
    public Snapshot open(Path dump, OpenOptions options, Progress progress, Cancel cancel) {
        Path file = dump.toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            throw new EngineException(EngineException.Code.IO, "not a file: " + file);
        }
        MatBootstrap.start();
        Path target = IndexLayout.prepare(file, options.indexDir(), version());

        Map<String, String> args = new HashMap<>();
        if (options.keepUnreachable()) {
            args.put("keep_unreachable_objects", "true");
        }
        IProgressListener listener = new MatProgress(progress, cancel);
        try {
            ISnapshot snapshot = SnapshotFactory.openSnapshot(target.toFile(), args, listener);
            IndexLayout.markComplete(target, version());
            return new MatSnapshot(file, snapshot, this);
        } catch (IProgressListener.OperationCanceledException e) {
            throw new EngineException(EngineException.Code.CANCELLED, "open cancelled", e);
        } catch (OutOfMemoryError e) {
            throw new EngineException(EngineException.Code.ENGINE_OOM,
                    "out of memory while opening " + file + "; increase the daemon -Xmx", e);
        } catch (SnapshotException e) {
            throw new EngineException(EngineException.Code.CORRUPT_DUMP, "cannot open " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw new EngineException(EngineException.Code.IO, io.getMessage(), e);
            }
            throw new EngineException(EngineException.Code.INTERNAL, "MAT failed opening " + file + ": " + e, e);
        }
    }
}
