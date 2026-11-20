package hd.engine.mat;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.RegistryFactory;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.dynamichelpers.ExtensionTracker;
import org.eclipse.core.runtime.spi.RegistryContributor;
import org.eclipse.core.runtime.spi.RegistryStrategy;
import org.eclipse.core.runtime.ContributorFactorySimple;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Boots MAT's core plugins on a plain classpath, without OSGi:
 * builds a non-OSGi Eclipse extension registry from each MAT JAR's plugin.xml and wires the plugin singletons.
 */
final class MatBootstrap {

    private static boolean started;

    private MatBootstrap() {}

    private static final java.util.Set<String> REQUIRED = java.util.Set.of(
            "org.eclipse.mat.report", "org.eclipse.mat.api", "org.eclipse.mat.parser", "org.eclipse.mat.hprof");

    static synchronized void start() {
        if (started) {
            return;
        }
        try {
            doStart();
        } catch (Exception | LinkageError e) {
            throw new hd.engine.api.EngineException(hd.engine.api.EngineException.Code.INTERNAL,
                    "cannot start MAT: " + e, e);
        }
        started = true;
    }

    private static void doStart() throws Exception {
        java.util.Set<String> contributed = new java.util.HashSet<>();
        Object masterToken = new Object();
        ClassLoader loader = MatBootstrap.class.getClassLoader();

        RegistryStrategy strategy = new RegistryStrategy(null, null) {
            @Override
            public Object createExecutableExtension(RegistryContributor contributor, String className,
                                                    String overriddenContributorName) throws CoreException {
                try {
                    return Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException | LinkageError e) {
                    throw new CoreException(new Status(IStatus.ERROR, contributor.getActualName(),
                            "Cannot create " + className, e));
                }
            }
        };
        IExtensionRegistry registry = RegistryFactory.createRegistry(strategy, masterToken, null);

        Enumeration<URL> pluginXmls = loader.getResources("plugin.xml");
        while (pluginXmls.hasMoreElements()) {
            URL url = pluginXmls.nextElement();
            if (!"jar".equals(url.getProtocol())) {
                continue;
            }
            JarURLConnection conn = (JarURLConnection) url.openConnection();
            conn.setUseCaches(false);
            try (JarFile jar = conn.getJarFile()) {
                Manifest mf = jar.getManifest();
                String name = mf == null ? null : mf.getMainAttributes().getValue("Bundle-SymbolicName");
                if (name == null || !name.startsWith("org.eclipse.mat.")) {
                    continue;
                }
                name = name.split(";")[0].trim();
                ResourceBundle props = null;
                var propsEntry = jar.getEntry("plugin.properties");
                if (propsEntry != null) {
                    try (InputStream in = jar.getInputStream(propsEntry)) {
                        props = new PropertyResourceBundle(in);
                    }
                }
                ResourceBundle translations = new DefaultingBundle(props);
                IContributor contributor = ContributorFactorySimple.createContributor(name);
                try (InputStream in = jar.getInputStream(jar.getEntry("plugin.xml"))) {
                    if (registry.addContribution(in, contributor, false, url.toString(), translations, masterToken)) {
                        contributed.add(name);
                    }
                }
            }
        }
        if (!contributed.containsAll(REQUIRED)) {
            throw new IllegalStateException("MAT plugins missing from classpath: expected " + REQUIRED + ", found "
                    + contributed + " (run scripts/install-mat.sh)");
        }
        RegistryFactory.setDefaultRegistryProvider(() -> registry);
        installPlatformServices();

        // Wire plugin singletons the way their OSGi activators would, without calling Plugin.start(BundleContext).
        wirePlugin("org.eclipse.mat.report.internal.ReportPlugin", registry, false);
        wirePlugin("org.eclipse.mat.internal.MATPlugin", registry, false);
        wirePlugin("org.eclipse.mat.parser.internal.ParserPlugin", registry, true);
        Object hprof = wirePlugin("org.eclipse.mat.hprof.HprofPlugin", registry, false);
        set(org.eclipse.core.runtime.Plugin.class, hprof, "bundle", bundle("org.eclipse.mat.hprof"));
    }

    private static Object wirePlugin(String className, IExtensionRegistry registry, boolean parserRegistry) throws Exception {
        Class<?> c = Class.forName(className);
        Object plugin = c.getDeclaredConstructor().newInstance();
        set(c, null, "plugin", plugin);
        ExtensionTracker tracker = null;
        if (hasField(c, "tracker")) {
            tracker = new ExtensionTracker(registry);
            set(c, plugin, "tracker", tracker);
        }
        if (parserRegistry) {
            Class<?> reg = Class.forName("org.eclipse.mat.parser.internal.util.ParserRegistry");
            Object r = reg.getConstructor(org.eclipse.core.runtime.dynamichelpers.IExtensionTracker.class).newInstance(tracker);
            set(c, plugin, "registry", r);
        }
        return plugin;
    }

    /** MAT preference overrides (qualifier/key → value); everything else returns the caller's default. */
    static final java.util.Map<String, String> PREFERENCES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * MAT calls Platform.getContentTypeManager() and Platform.getPreferencesService() unconditionally; both are OSGi
     * services (null here). Content types are only an optional hint (parsers are matched by file extension), so we
     * install a manager that knows none. Preferences return the caller's default unless overridden in PREFERENCES.
     */
    private static void installPlatformServices() throws Exception {
        Object noTypes = proxy(org.eclipse.core.runtime.content.IContentTypeManager.class, (m, a) -> null);
        Object prefs = proxy(org.eclipse.core.runtime.preferences.IPreferencesService.class, (m, a) -> {
            // getString/getBoolean/getInt/getLong/getFloat/getDouble(qualifier, key, default, contexts)
            if (m.getName().startsWith("get") && a != null && a.length == 4 && a[0] instanceof String q && a[1] instanceof String k) {
                String v = PREFERENCES.get(q + "/" + k);
                if (v == null) {
                    return a[2];
                }
                Class<?> rt = m.getReturnType();
                if (rt == boolean.class) return Boolean.parseBoolean(v);
                if (rt == int.class) return Integer.parseInt(v);
                if (rt == long.class) return Long.parseLong(v);
                if (rt == float.class) return Float.parseFloat(v);
                if (rt == double.class) return Double.parseDouble(v);
                return v;
            }
            return null;
        });
        // Platform.inDebugMode() etc. read framework properties from the platform's BundleContext
        org.osgi.framework.BundleContext platformCtx = proxy(org.osgi.framework.BundleContext.class,
                (m, a) -> "getProperty".equals(m.getName()) ? System.getProperty((String) a[0]) : null);
        set(org.eclipse.core.internal.runtime.PlatformActivator.class, null, "context", platformCtx);

        Object platform = org.eclipse.core.internal.runtime.InternalPlatform.getDefault();
        set(platform.getClass(), platform, "contentTracker", tracker(noTypes));
        set(platform.getClass(), platform, "preferencesTracker", tracker(prefs));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static org.osgi.util.tracker.ServiceTracker tracker(Object service) {
        org.osgi.framework.BundleContext ctx = proxy(org.osgi.framework.BundleContext.class, (m, a) -> null);
        return new org.osgi.util.tracker.ServiceTracker(ctx, service.getClass().getInterfaces()[0].getName(), null) {
            @Override
            public Object getService() {
                return service;
            }
        };
    }

    /** Minimal OSGi Bundle: only the symbolic name is real. */
    private static org.osgi.framework.Bundle bundle(String symbolicName) {
        return proxy(org.osgi.framework.Bundle.class, (m, a) -> "getSymbolicName".equals(m.getName()) ? symbolicName : null);
    }

    /** Dynamic proxy with sane defaults: empty arrays, false/0 for primitives, identity equals/hashCode. */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<java.lang.reflect.Method, Object[], Object> handler) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(MatBootstrap.class.getClassLoader(), new Class<?>[]{type},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "hashCode": if (a == null) return System.identityHashCode(p); break;
                        case "equals": if (a != null && a.length == 1) return p == a[0]; break;
                        case "toString": if (a == null) return type.getSimpleName() + "@standalone"; break;
                        default: break;
                    }
                    Object r = handler.apply(m, a);
                    if (r != null) return r;
                    Class<?> rt = m.getReturnType();
                    if (rt.isArray()) return java.lang.reflect.Array.newInstance(rt.getComponentType(), 0);
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt.isPrimitive() && rt != void.class) return 0;
                    return null;
                });
    }

    /**
     * plugin.xml uses Eclipse's "%key Default text" syntax; the non-OSGi registry passes "key Default text" as the key.
     * Resolve the key part, falling back to the default text (or the key itself).
     */
    private static final class DefaultingBundle extends ResourceBundle {
        private final ResourceBundle delegate;

        DefaultingBundle(ResourceBundle delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Object handleGetObject(String key) {
            String k = key;
            String fallback = key;
            int sp = key.indexOf(' ');
            if (sp > 0) {
                k = key.substring(0, sp);
                fallback = key.substring(sp + 1);
            }
            if (delegate != null && delegate.containsKey(k)) {
                return delegate.getString(k);
            }
            return fallback;
        }

        @Override
        public Enumeration<String> getKeys() {
            return delegate == null ? java.util.Collections.emptyEnumeration() : delegate.getKeys();
        }
    }

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static void set(Class<?> c, Object target, String field, Object value) throws Exception {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
