package hd.engine.mat;

import hd.engine.api.Capability;
import hd.engine.api.ClassView;
import hd.engine.api.EngineException;
import hd.engine.api.FieldType;
import hd.engine.api.GcRoot;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;
import hd.engine.api.SnapshotInfo;
import hd.engine.api.ThreadView;
import hd.engine.api.capability.FastGcRootPaths;
import hd.engine.api.capability.FastHistogram;
import hd.engine.api.capability.Oql;
import hd.engine.api.capability.RetainedSetSize;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/** {@link Snapshot} over a MAT {@link ISnapshot}. MAT's int object ids never leave this package. */
final class MatSnapshot implements Snapshot {

    private final Path dump;
    private final ISnapshot mat;
    private final MatEngine engine;
    private volatile boolean closed;

    MatSnapshot(Path dump, ISnapshot mat, MatEngine engine) {
        this.dump = dump;
        this.mat = mat;
        this.engine = engine;
    }

    ISnapshot mat() {
        return mat;
    }

    // ---- id mapping

    int id(long address) {
        try {
            return mat.mapAddressToId(address);
        } catch (SnapshotException e) {
            throw EngineException.notFound(address);
        }
    }

    long addr(int id) {
        try {
            return mat.mapIdToAddress(id);
        } catch (SnapshotException e) {
            throw new EngineException(EngineException.Code.INTERNAL, "bad object id " + id, e);
        }
    }

    LongStream addrs(int[] ids) {
        return Arrays.stream(ids).mapToLong(this::addr);
    }

    IObject matObject(long address) {
        int id = id(address);
        return call(() -> mat.getObject(id));
    }

    // ---- Snapshot

    @Override
    public SnapshotInfo info() {
        org.eclipse.mat.snapshot.SnapshotInfo i = mat.getSnapshotInfo();
        Boolean compressed = null;
        Object p = i.getProperty("$useCompressedOops");
        if (p instanceof Boolean b) {
            compressed = b;
        }
        return new SnapshotInfo(dump, engine.id(), i.getNumberOfObjects(), i.getNumberOfClasses(), i.getUsedHeapSize(),
                i.getIdentifierSize(), compressed, i.getJvmInfo(),
                i.getCreationDate() == null ? -1 : i.getCreationDate().getTime());
    }

    @Override
    public ObjectView object(long address) {
        return new MatObjectView(this, matObject(address));
    }

    @Override
    public ClassView clazz(long classAddress) {
        IObject o = matObject(classAddress);
        if (!(o instanceof IClass c)) {
            throw new EngineException(EngineException.Code.OBJECT_NOT_FOUND,
                    hd.engine.api.Addresses.hex(classAddress) + " is not a class");
        }
        return toClassView(c);
    }

    @Override
    public Stream<ClassView> classes() {
        Collection<IClass> all = call(mat::getClasses);
        return all.stream().map(this::toClassView);
    }

    @Override
    public List<ClassView> classesByName(String name) {
        Collection<IClass> found = call(() -> mat.getClassesByName(name, false));
        return found == null ? List.of() : found.stream().map(this::toClassView).toList();
    }

    @Override
    public LongStream instancesOf(long classAddress) {
        IObject o = matObject(classAddress);
        if (!(o instanceof IClass c)) {
            throw new EngineException(EngineException.Code.OBJECT_NOT_FOUND,
                    hd.engine.api.Addresses.hex(classAddress) + " is not a class");
        }
        return addrs(call(c::getObjectIds));
    }

    @Override
    public long classOf(long address) {
        int id = id(address);
        return call(() -> mat.getClassOf(id)).getObjectAddress();
    }

    @Override
    public LongStream outbound(long address) {
        int id = id(address);
        return addrs(call(() -> mat.getOutboundReferentIds(id)));
    }

    @Override
    public LongStream inbound(long address) {
        int id = id(address);
        return addrs(call(() -> mat.getInboundRefererIds(id)));
    }

    @Override
    public long shallowSize(long address) {
        int id = id(address);
        return call(() -> mat.getHeapSize(id));
    }

    @Override
    public long retainedSize(long address) {
        int id = id(address);
        return call(() -> mat.getRetainedHeapSize(id));
    }

    @Override
    public long immediateDominator(long address) {
        int id = id(address);
        int dom = call(() -> mat.getImmediateDominatorId(id));
        return dom < 0 ? ROOT : addr(dom);
    }

    @Override
    public LongStream dominated(long address) {
        int id = address == ROOT ? -1 : id(address);
        return addrs(call(() -> mat.getImmediateDominatedIds(id)));
    }

    @Override
    public Stream<GcRoot> gcRoots() {
        int[] roots = call(mat::getGCRoots);
        return Arrays.stream(roots).mapToObj(id -> {
            GCRootInfo[] infos = call(() -> mat.getGCRootInfo(id));
            return new GcRoot(addr(id), rootTypes(infos), threadOf(infos));
        });
    }

    @Override
    public List<GcRoot.Type> rootTypes(long address) {
        int id = id(address);
        GCRootInfo[] infos = call(() -> mat.getGCRootInfo(id));
        return infos == null ? List.of() : rootTypes(infos);
    }

    @Override
    public Stream<ThreadView> threads() {
        int[] roots = call(mat::getGCRoots);
        return Arrays.stream(roots)
                .filter(id -> {
                    GCRootInfo[] infos = call(() -> mat.getGCRootInfo(id));
                    return infos != null && Arrays.stream(infos).anyMatch(i -> i.getType() == GCRootInfo.Type.THREAD_OBJ);
                })
                .mapToObj(id -> {
                    IThreadStack stack = call(() -> mat.getThreadStack(id));
                    List<ThreadView.Frame> frames = new ArrayList<>();
                    if (stack != null) {
                        for (IStackFrame f : stack.getStackFrames()) {
                            frames.add(toFrame(f));
                        }
                    }
                    return new ThreadView(addr(id), List.copyOf(frames));
                });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends Capability> Optional<C> capability(Class<C> type) {
        Object c = null;
        if (type == FastHistogram.class) {
            c = new MatCapabilities.Histogram(this);
        } else if (type == FastGcRootPaths.class) {
            c = new MatCapabilities.GcRootPaths(this);
        } else if (type == RetainedSetSize.class) {
            c = new MatCapabilities.RetainedSet(this);
        } else if (type == Oql.class) {
            c = new MatCapabilities.MatOql(this);
        }
        return Optional.ofNullable((C) c);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            SnapshotFactory.dispose(mat);
        }
    }

    // ---- conversions

    ClassView toClassView(IClass c) {
        IClass sup = c.getSuperClass();
        List<ClassView.FieldDecl> fields = new ArrayList<>();
        for (FieldDescriptor fd : c.getFieldDescriptors()) {
            fields.add(new ClassView.FieldDecl(fd.getName(), fieldType(fd.getType())));
        }
        long instanceSize = c.isArrayType() ? -1 : c.getHeapSizePerInstance();
        return new ClassView(c.getObjectAddress(), c.getName(), sup == null ? 0L : sup.getObjectAddress(),
                c.getClassLoaderAddress(), instanceSize, c.getNumberOfObjects(), c.isArrayType(), List.copyOf(fields));
    }

    private static final Pattern FRAME = Pattern.compile("^at (\\S+?)(\\(.*?\\)\\S*)? \\((.*)\\)$");

    private ThreadView.Frame toFrame(IStackFrame f) {
        // MAT text: "at java.lang.Object.wait0(J)V (Object.java(Native Method))" or "at a.B.m()V (B.java:42)"
        String text = f.getText();
        String method = text;
        String location = null;
        Matcher m = FRAME.matcher(text == null ? "" : text);
        if (m.matches()) {
            method = m.group(1);
            location = m.group(3);
            if (location.endsWith("(Native Method)")) {
                location = "Native Method";
            } else if (location.endsWith("(Unknown Source)")) {
                location = null;
            }
        }
        long[] locals = addrs(f.getLocalObjectsIds()).toArray();
        return new ThreadView.Frame(method, location, locals);
    }

    private long threadOf(GCRootInfo[] infos) {
        for (GCRootInfo i : infos) {
            int t = i.getType();
            if ((t == GCRootInfo.Type.JAVA_LOCAL || t == GCRootInfo.Type.NATIVE_LOCAL
                    || t == GCRootInfo.Type.THREAD_BLOCK || t == GCRootInfo.Type.JAVA_STACK_FRAME
                    || t == GCRootInfo.Type.NATIVE_STACK) && i.getContextAddress() != 0) {
                return i.getContextAddress();
            }
        }
        return 0L;
    }

    private static List<GcRoot.Type> rootTypes(GCRootInfo[] infos) {
        List<GcRoot.Type> types = new ArrayList<>();
        for (GCRootInfo i : infos) {
            GcRoot.Type t = switch (i.getType()) {
                case GCRootInfo.Type.SYSTEM_CLASS -> GcRoot.Type.SYSTEM_CLASS;
                case GCRootInfo.Type.THREAD_OBJ -> GcRoot.Type.THREAD_OBJECT;
                case GCRootInfo.Type.JAVA_LOCAL, GCRootInfo.Type.JAVA_STACK_FRAME -> GcRoot.Type.JAVA_LOCAL;
                case GCRootInfo.Type.NATIVE_STACK -> GcRoot.Type.NATIVE_STACK;
                case GCRootInfo.Type.THREAD_BLOCK -> GcRoot.Type.THREAD_BLOCK;
                case GCRootInfo.Type.BUSY_MONITOR -> GcRoot.Type.BUSY_MONITOR;
                case GCRootInfo.Type.NATIVE_STATIC -> GcRoot.Type.JNI_GLOBAL;
                case GCRootInfo.Type.NATIVE_LOCAL -> GcRoot.Type.JNI_LOCAL;
                case GCRootInfo.Type.FINALIZABLE -> GcRoot.Type.FINALIZABLE;
                case GCRootInfo.Type.UNFINALIZED -> GcRoot.Type.UNFINALIZED;
                case GCRootInfo.Type.UNREACHABLE -> GcRoot.Type.UNREACHABLE;
                default -> GcRoot.Type.UNKNOWN;
            };
            if (!types.contains(t)) {
                types.add(t);
            }
        }
        return List.copyOf(types);
    }

    static FieldType fieldType(int matType) {
        return switch (matType) {
            case IObject.Type.OBJECT -> FieldType.OBJECT;
            case IObject.Type.BOOLEAN -> FieldType.BOOLEAN;
            case IObject.Type.CHAR -> FieldType.CHAR;
            case IObject.Type.FLOAT -> FieldType.FLOAT;
            case IObject.Type.DOUBLE -> FieldType.DOUBLE;
            case IObject.Type.BYTE -> FieldType.BYTE;
            case IObject.Type.SHORT -> FieldType.SHORT;
            case IObject.Type.INT -> FieldType.INT;
            case IObject.Type.LONG -> FieldType.LONG;
            default -> throw new IllegalArgumentException("unknown MAT type " + matType);
        };
    }

    // ---- MAT exception plumbing

    interface MatCall<T> {
        T call() throws SnapshotException;
    }

    static <T> T call(MatCall<T> c) {
        try {
            return c.call();
        } catch (SnapshotException e) {
            throw new EngineException(EngineException.Code.INTERNAL, "MAT: " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            throw new EngineException(EngineException.Code.ENGINE_OOM, "out of memory in MAT", e);
        }
    }
}
