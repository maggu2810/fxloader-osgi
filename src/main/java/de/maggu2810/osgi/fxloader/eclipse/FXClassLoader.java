/*******************************************************************************
 * Copyright (c) 2014 BestSolution.at and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Tom Schindl<tom.schindl@bestsolution.at> - initial API and implementation
 *******************************************************************************/

package de.maggu2810.osgi.fxloader.eclipse;

import java.io.IOException;
import java.lang.ModuleLayer.Controller;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.internal.hookregistry.ClassLoaderHook;
import org.eclipse.osgi.internal.loader.BundleLoader;
import org.eclipse.osgi.internal.loader.ModuleClassLoader;
import org.eclipse.osgi.service.urlconversion.URLConverter;
import org.eclipse.osgi.storage.BundleInfo.Generation;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.util.tracker.ServiceTracker;

import de.maggu2810.osgi.fxloader.eclipse.jpms.AddOpenExports;
import de.maggu2810.osgi.fxloader.eclipse.jpms.AddReads;
import de.maggu2810.osgi.fxloader.eclipse.jpms.JavaModuleLayerModification;

/**
 * Hook to overwrite OSGis default classloading.
 *
 * <ul>
 * <li>Removed pre Java 11 support.
 * <li>Removed e(fx)clipse SWT handling.
 * </ul>
 */
public class FXClassLoader extends ClassLoaderHook {

    private static void debugf(final String funcName, final String format, final Object... args) {
        System.err.printf("FXClassLoader#" + funcName + " - " + format, args);
    }

    private static final Map<String, ServiceTracker<Object, URLConverter>> URL_TRACKERS = new HashMap<>();

    private final AtomicBoolean boostrappingModules = new AtomicBoolean();
    private BundleContext frameworkContext;
    private ModuleLayer moduleLayer;
    private ClassLoader j11Classloader;
    private Set<String> j11ModulePackages;
    private boolean reentrance;

    @Override
    public synchronized ModuleClassLoader createClassLoader(final ClassLoader parent,
            final EquinoxConfiguration configuration, final BundleLoader delegate, final Generation generation) {
        // FIXME Can we get rid of this?
        if (this.frameworkContext == null) {
            this.frameworkContext = generation.getBundleInfo().getStorage().getModuleContainer().getFrameworkWiring()
                    .getBundle().getBundleContext();
        }
        return super.createClassLoader(parent, configuration, delegate, generation);
    }

    @Override
    public Class<?> postFindClass(final String name, final ModuleClassLoader moduleClassLoader)
            throws ClassNotFoundException {
        final String funcName = "postFindClass";

        if (this.reentrance) {
            if (FXClassloaderConfigurator.DEBUG) {
                debugf(funcName, "Loop detected returning null%n");
            }
            return null;
        }
        this.reentrance = true;
        try {
            // JavaFX is not part of JDK anymore need to install modules on the fly
            try {
                return findClassJavaFX11(name, moduleClassLoader);
            } catch (final Throwable e) {
                if (FXClassloaderConfigurator.DEBUG) {
                    debugf(funcName, "exception while loading %s. Continue delegation by returning NULL%n",
                            e.getMessage());
                    e.printStackTrace();
                }
                return null;
            }
        } finally {
            this.reentrance = false;
        }
    }

    private Class<?> findClassJavaFX11(final String name, final ModuleClassLoader moduleClassLoader) throws Throwable {
        final String funcName = "findClassJavaFX11";

        if (FXClassloaderConfigurator.DEBUG) {
            debugf(funcName, "started%n");
            debugf(funcName, "Loading class '%s' for %s%n", name, moduleClassLoader);
        }

        synchronized (this) {
            if (this.j11ModulePackages != null && this.j11ModulePackages.isEmpty()) {
                if (FXClassloaderConfigurator.DEBUG) {
                    debugf(funcName, "Loader is empty. Returning null.%n");
                }

                return null;
            }
            if (this.boostrappingModules.get()) {
                // If classes are loaded while we boostrap we can just return
                debugf(funcName, "Loading '%s' while we bootstrap. Returning null.%n", name);
                return null;
            }

            if (this.j11Classloader == null) {
                try {
                    this.boostrappingModules.set(true);
                    // As all modules are loaded by the same classloader using the first one is OK
                    final ModuleLayer layer = getModuleLayer();
                    final Set<Module> modules = layer.modules();
                    if (!modules.isEmpty()) {
                        this.j11Classloader = layer.findLoader(modules.iterator().next().getName());
                        this.j11ModulePackages = modules.stream().flatMap(m -> m.getPackages().stream())
                                .collect(Collectors.toSet());
                    } else {
                        return null;
                    }
                } finally {
                    this.boostrappingModules.set(false);
                }
            }
        }

        if (FXClassloaderConfigurator.DEBUG) {
            debugf(funcName, "Using classloader %s%n", this.j11Classloader);
        }

        final int lastIndexOf = name.lastIndexOf('.');
        Class<?> loadedClass = null;
        try {
            if (lastIndexOf < 0) {
                return null;
            } else if (!this.j11ModulePackages.contains(name.substring(0, lastIndexOf))) {
                return null;
            }

            return loadedClass = this.j11Classloader.loadClass(name);
        } finally {
            if (FXClassloaderConfigurator.DEBUG) {
                debugf(funcName, "%s - ended%n", loadedClass);
            }
        }
    }

    private synchronized ModuleLayer getModuleLayer() throws Throwable {
        final String funcName = "getModuleLayer";

        if (this.moduleLayer == null) {
            final List<FXProviderBundle> providers = getDeployedJavaModuleBundlePaths(this.frameworkContext);

            final ClassLoader parentClassloader = getClass().getClassLoader();

            if (FXClassloaderConfigurator.DEBUG) {
                debugf(funcName, "Parent Classloader: %s%n", parentClassloader);
            }

            this.moduleLayer = initModuleLayer(parentClassloader, providers,
                    collectModifications(this.frameworkContext));

            if (FXClassloaderConfigurator.DEBUG) {
                debugf(funcName, "Module created: %s%n", moduleLayer);
            }
        }

        return this.moduleLayer;
    }

    private static JavaModuleLayerModification collectModifications(final BundleContext context) {
        final Set<AddReads> reads = new HashSet<>();
        final Set<AddOpenExports> opens = new HashSet<>();
        final Set<AddOpenExports> exports = new HashSet<>();

        final Bundle[] bundles = context.getBundles();
        for (final Bundle b : bundles) {
            if ((b.getState() & Bundle.RESOLVED) == Bundle.RESOLVED
                    || (b.getState() & Bundle.ACTIVE) == Bundle.ACTIVE) {
                if (b.getHeaders().get("Java-Module-AddOpens") != null) {
                    opens.addAll(toOpenExports(b.getHeaders().get("Java-Module-AddOpens"), b));
                } else if (b.getHeaders().get("Java-Module-AddExports") != null) {
                    exports.addAll(toOpenExports(b.getHeaders().get("Java-Module-AddExports"), b));
                } else if (b.getHeaders().get("Java-Module-AddReads") != null) {
                    reads.addAll(toReads(b.getHeaders().get("Java-Module-AddReads"), b));
                }
            }
        }

        final String addReads = System.getProperty("fxloader.osgi.eclipse.hook.add-reads");
        final String addOpens = System.getProperty("fxloader.osgi.eclipse.hook.add-opens");
        final String addExports = System.getProperty("fxloader.osgi.eclipse.hook.add-exports");

        if (addReads != null) {
            reads.addAll(toReads(addReads, null));
        }

        if (addExports != null) {
            exports.addAll(toOpenExports(addExports, null));
        }

        if (addOpens != null) {
            opens.addAll(toOpenExports(addOpens, null));
        }

        return new JavaModuleLayerModification(context.getBundles(), reads, exports, opens);
    }

    private static String adaptAllUnnamed(final String value, final Bundle bundle) {
        if (value.endsWith("=.") && bundle != null) {
            return value.replace("=.", "=BUNDLE(@" + bundle.getBundleId() + ")");
        }
        return value;
    }

    private static Set<AddReads> toReads(final String value, final Bundle bundle) {
        return Stream.of(value.split(",")).map(v -> adaptAllUnnamed(v, bundle)).map(AddReads::valueOf) //
                .filter(Optional::isPresent) //
                .map(Optional::get) //
                .collect(Collectors.toSet());
    }

    private static Set<AddOpenExports> toOpenExports(final String value, final Bundle bundle) {
        return Stream.of(value.split(",")).map(v -> adaptAllUnnamed(v, bundle)).map(AddOpenExports::valueOf) //
                .filter(Optional::isPresent) //
                .map(Optional::get) //
                .collect(Collectors.toSet());
    }

    private static ModuleLayer initModuleLayer(final ClassLoader parentClassloader,
            final List<FXProviderBundle> bundles, final JavaModuleLayerModification modifications) throws Throwable {
        try {
            if (Boolean.getBoolean("fxloader.osgi.eclipse.hook.advanced-modules") || !modifications.isEmpty()) {
                return advancedModuleLayerBoostrap(parentClassloader, bundles, modifications);
            } else {
                return defaultModuleLayerBootstrap(parentClassloader, bundles);
            }
        } catch (final Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    private static ModuleLayer advancedModuleLayerBoostrap(final ClassLoader parentClassloader,
            final List<FXProviderBundle> bundles, final JavaModuleLayerModification modifications) throws Throwable {
        final String funcName = "advancedModuleLayerBoostrap";
        if (FXClassloaderConfigurator.DEBUG) {
            debugf(funcName, "Using advanced layer creation to apply patches%n");
        }

        final Path[] paths = bundles.stream().map(p -> p.path).toArray(i -> new Path[i]);
        final Set<String> modules = bundles.stream().map(p -> p.module).collect(Collectors.toSet());

        @SuppressWarnings("deprecation")
        final URL[] urls = Stream.of(paths).map(Path::toFile).map(f -> {
            try {
                return f.toURL();
            } catch (final Throwable t) {
                return null;
            }
        }).toArray(i -> new URL[i]);

        if (FXClassloaderConfigurator.DEBUG) {
            for (final FXProviderBundle b : bundles) {
                debugf(funcName, "%s => %s%n", b.module, b.path);
            }
        }

        final URLClassLoader c = new URLClassLoader(urls, parentClassloader) {
            @Override
            protected java.lang.Class<?> findClass(final String moduleName, final String name) {
                try {
                    return findClass(name);
                } catch (final ClassNotFoundException e) {
                    /* intentional empty */}
                return null;
            }

            @Override
            protected URL findResource(final String moduleName, final String name) throws IOException {
                return findResource(name);
            }
        };

        final ModuleFinder fxModuleFinder = ModuleFinder.of(paths);
        final ModuleFinder empty = ModuleFinder.of();
        final ModuleLayer bootLayer = ModuleLayer.boot();
        final Configuration configuration = bootLayer.configuration();
        final Configuration newConfiguration = configuration.resolve(fxModuleFinder, empty, modules);
        final Function<String, ClassLoader> clComputer = s -> c;
        final Controller moduleLayerController = ModuleLayer.defineModules(newConfiguration, Arrays.asList(bootLayer),
                clComputer);
        modifications.applyConfigurations(moduleLayerController);

        return moduleLayerController.layer();
    }

    private static ModuleLayer defaultModuleLayerBootstrap(final ClassLoader parentClassloader,
            final List<FXProviderBundle> bundles) throws Throwable {
        final String funcName = "defaultModuleLayerBootstrap";

        final Path[] paths = bundles.stream().map(p -> p.path).toArray(i -> new Path[i]);
        final Set<String> modules = bundles.stream().map(p -> p.module).collect(Collectors.toSet());

        if (FXClassloaderConfigurator.DEBUG) {
            for (final FXProviderBundle b : bundles) {
                debugf(funcName, "%s => %s%n", b.module, b.path);
            }
        }

        final ModuleFinder finder = ModuleFinder.of(paths);
        final ModuleLayer boot = ModuleLayer.boot();
        final Configuration configuration = boot.configuration();
        final ModuleFinder of = ModuleFinder.of();
        final Configuration cf = configuration.resolve(finder, of, modules);
        final ModuleLayer layer = boot.defineModulesWithOneLoader(cf, parentClassloader);

        return layer;
    }

    private static URLConverter getURLConverter(final URL url, final BundleContext ctx) {
        if (url == null || ctx == null) {
            return null;
        }

        final String protocol = url.getProtocol();
        synchronized (URL_TRACKERS) {
            // if the tracker is not already placed in our "url tracker registry", compute and cache it
            final ServiceTracker<Object, URLConverter> tracker = URL_TRACKERS.computeIfAbsent(protocol, key -> {
                // get the right service based on the protocol
                final Filter filter;
                try {
                    filter = ctx.createFilter(String
                            .format("(&(objectClass=" + URLConverter.class.getName() + ")(protocol=%s))", protocol));
                } catch (final InvalidSyntaxException e) {
                    return null;
                }
                final ServiceTracker<Object, URLConverter> trackerNew = new ServiceTracker<>(ctx, filter, null);
                trackerNew.open();
                return trackerNew;
            });
            if (tracker == null) {
                return null;
            }
            return tracker.getService();
        }
    }

    private static List<FXProviderBundle> getDeployedJavaModuleBundlePaths(final BundleContext context) {
        final String funcName = "getDeployedJavaModuleBundlePaths";
        if (FXClassloaderConfigurator.DEBUG) {
            debugf(funcName, "Loading libraries from deployed modules%n");
        }

        final List<FXProviderBundle> paths = new ArrayList<>();

        for (final Bundle b : context.getBundles()) {
            final int bundleState = b.getState();
            // skip bundles that are not resolved AND not active
            if ((bundleState & Bundle.RESOLVED) != Bundle.RESOLVED && (bundleState & Bundle.ACTIVE) != Bundle.ACTIVE) {
                continue;
            }
            final String name = b.getHeaders().get("Java-Module");
            // skip bundles that does not contain the "Java-Module" header
            if (name == null) {
                continue;
            }

            if (FXClassloaderConfigurator.DEBUG) {
                debugf(funcName, "Found OSGi-Module with JPMS-Module '%s'%n", name);
            }

            // find the JAR file inside the bundle
            URL entry = b.getEntry(name + ".jar");
            if (entry == null) {
                // if it is an automatic module - is used
                entry = b.getEntry(name.replace('.', '-') + ".jar");
                if (entry == null) {
                    if (FXClassloaderConfigurator.DEBUG) {
                        debugf(funcName, "Did not found JAR file.%n");
                    }
                    continue;
                }
            }

            if (FXClassloaderConfigurator.DEBUG) {
                debugf(funcName, "Found Jar '%s'%n", entry);
            }

            // add "module name" and "file path" to the results.
            final URLConverter converter = getURLConverter(entry, context);
            try {
                final URL url = converter.toFileURL(entry);
                if (FXClassloaderConfigurator.DEBUG) {
                    debugf(funcName, "Converted URL: %s%n", url);
                }
                String file = url.getFile();
                if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                    if (file.startsWith("/")) {
                        file = file.substring(1);
                    }
                }
                paths.add(new FXProviderBundle(name, Paths.get(file)));
            } catch (final Throwable e) {
                if (FXClassloaderConfigurator.DEBUG) {
                    debugf(funcName, "Failed to load get path%n");
                    e.printStackTrace();
                }
                throw new IllegalStateException(e);
            }
        }

        return paths;
    }

    static class FXProviderBundle {
        final String module;
        final Path path;

        public FXProviderBundle(final String module, final Path path) {
            this.module = module;
            this.path = path;
        }

        @Override
        public String toString() {
            return "FXProviderBundle [module=" + this.module + ", path=" + this.path + "]";
        }
    }

}
