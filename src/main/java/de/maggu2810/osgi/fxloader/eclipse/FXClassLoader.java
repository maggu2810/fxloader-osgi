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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
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
import org.osgi.framework.BundleException;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.tracker.ServiceTracker;

import de.maggu2810.osgi.fxloader.eclipse.jpms.AddOpenExports;
import de.maggu2810.osgi.fxloader.eclipse.jpms.AddReads;
import de.maggu2810.osgi.fxloader.eclipse.jpms.JavaModuleLayerModification;

/**
 * Hook to overwrite OSGis default classloading
 */
public class FXClassLoader extends ClassLoaderHook {
    private static final String FX_SYMBOLIC_NAME = "org.eclipse.fx.javafx"; //$NON-NLS-1$
    private static final String SWT_SYMBOLIC_NAME = "org.eclipse.swt"; //$NON-NLS-1$

    private final AtomicBoolean boostrappingModules = new AtomicBoolean();
    private BundleContext frameworkContext;
    private ModuleLayer moduleLayer;
    private ClassLoader j11Classloader;
    private static Map<String, ServiceTracker<Object, URLConverter>> urlTrackers = new HashMap<>();
    private Set<String> j11ModulePackages;
    private boolean reentrance;

    @Override
    public Class<?> postFindClass(final String name, final ModuleClassLoader moduleClassLoader)
            throws ClassNotFoundException {
        if (this.reentrance) {
            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#postFindClass - Loop detected returning null");
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
                    System.err.println("FXClassLoader#postFindClass - exception while loading " + e.getMessage() //$NON-NLS-1$
                            + ". Continue delegation by returning NULL");
                    e.printStackTrace();
                }
                return null;
            }
        } finally {
            this.reentrance = false;
        }

    }

    private Class<?> findClassJavaFX11(final String name, final ModuleClassLoader moduleClassLoader) throws Throwable {
        if (FXClassloaderConfigurator.DEBUG) {
            System.err.println("FXClassLoader#findClassJavaFX11 - started"); //$NON-NLS-1$
            System.err
                    .println("FXClassLoader#findClassJavaFX11 - Loading class '" + name + "' for " + moduleClassLoader); //$NON-NLS-1$//$NON-NLS-2$
        }

        synchronized (this) {
            if (this.j11ModulePackages != null && this.j11ModulePackages.isEmpty()) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader#findClassJavaFX11 - Loader is empty. Returning null."); //$NON-NLS-1$
                }

                return null;
            }
            if (this.boostrappingModules.get()) {
                // If classes are loaded why we boostrap we can just return
                System.err.println(
                        "FXClassLoader#findClassJavaFX11 - Loading '" + name + "' while we bootstrap. Returning null."); //$NON-NLS-1$//$NON-NLS-2$
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

                        if (getSWTClassloader(this.frameworkContext) != null) {
                            if (FXClassloaderConfigurator.DEBUG) {
                                System.err.println(
                                        "FXClassLoader#findClassJavaFX11 - We run inside SWT don't let the platform quit automatically"); //$NON-NLS-1$
                            }
                            try {
                                this.j11Classloader.loadClass("javafx.application.Platform") //$NON-NLS-1$
                                        .getDeclaredMethod("setImplicitExit", boolean.class) //$NON-NLS-1$
                                        .invoke(null, Boolean.FALSE);
                            } catch (final Throwable e) {
                                e.printStackTrace();
                            }
                        }
                    } else {
                        return null;
                    }
                } finally {
                    this.boostrappingModules.set(false);
                }
            }
        }

        if (FXClassloaderConfigurator.DEBUG) {
            System.err.println("FXClassLoader#findClassJavaFX11 - Using classloader " + this.j11Classloader); //$NON-NLS-1$
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
                System.err.println("FXClassLoader#findClassJavaFX11 - " + loadedClass + " - ended"); //$NON-NLS-1$
            }
        }
    }

    private synchronized ModuleLayer getModuleLayer() throws Throwable {
        if (this.moduleLayer == null) {
            final String javafxDir = System.getProperty("efxclipse.java-modules.dir"); //$NON-NLS-1$

            List<FXProviderBundle> providers = new ArrayList<>();

            if (javafxDir == null) {
                providers = getDeployedJavaModuleBundlePaths(this.frameworkContext);
            } else {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader#getModuleLayer - Use directory '" + javafxDir + "'"); //$NON-NLS-1$//$NON-NLS-2$
                }
                final String[] paths = javafxDir.split(";"); //$NON-NLS-1$
                for (final String dir : paths) {
                    final Path path = Paths.get(replaceProperties(dir));
                    if (FXClassloaderConfigurator.DEBUG) {
                        System.err.println("FXClassLoader#getModuleLayer - Inspecting path '" + path + "'"); //$NON-NLS-1$//$NON-NLS-2$
                    }
                    if (Files.exists(path)) {
                        providers = Files.list(path) //
                                .filter(p -> p.toString().endsWith(".jar")) //$NON-NLS-1$
                                .map(p -> new FXProviderBundle(
                                        p.getFileName().toString().replace(".jar", "").replace('-', '.'), p)) //$NON-NLS-1$//$NON-NLS-2$
                                .collect(Collectors.toList());
                        break;
                    }
                }
            }

            ClassLoader parentClassloader = getSWTClassloader(this.frameworkContext);
            if (parentClassloader == null) {
                parentClassloader = getClass().getClassLoader();
            }

            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#getModuleLayer - Parent Classloader: " + parentClassloader);
            }

            this.moduleLayer = initModuleLayer(parentClassloader, providers,
                    collectModifications(this.frameworkContext));

            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#getModuleLayer - Module created: " + moduleLayer);
            }
        }

        return this.moduleLayer;
    }

    private static String replaceProperties(final String path) {
        final Properties properties = System.getProperties();

        String rv = path;
        for (final Entry<Object, Object> e : properties.entrySet()) {
            String value = e.getValue() + ""; //$NON-NLS-1$
            if (System.getProperty("os.name").toLowerCase().contains("windows")) { //$NON-NLS-1$ //$NON-NLS-2$
                value = value.replace("file:/", ""); //$NON-NLS-1$//$NON-NLS-2$
            } else {
                value = value.replace("file:", ""); //$NON-NLS-1$ //$NON-NLS-2$
            }

            rv = rv.replace("$(" + e.getKey() + ")", value); //$NON-NLS-1$//$NON-NLS-2$
        }

        return rv;
    }

    private static JavaModuleLayerModification collectModifications(final BundleContext context) {
        final Set<AddReads> reads = new HashSet<>();
        final Set<AddOpenExports> opens = new HashSet<>();
        final Set<AddOpenExports> exports = new HashSet<>();

        final Bundle[] bundles = context.getBundles();
        for (final Bundle b : bundles) {
            if ((b.getState() & Bundle.RESOLVED) == Bundle.RESOLVED
                    || (b.getState() & Bundle.ACTIVE) == Bundle.ACTIVE) {
                if (b.getHeaders().get("Java-Module-AddOpens") != null) { //$NON-NLS-1$
                    opens.addAll(toOpenExports(b.getHeaders().get("Java-Module-AddOpens"), b)); //$NON-NLS-1$
                } else if (b.getHeaders().get("Java-Module-AddExports") != null) { //$NON-NLS-1$
                    exports.addAll(toOpenExports(b.getHeaders().get("Java-Module-AddExports"), b)); //$NON-NLS-1$
                } else if (b.getHeaders().get("Java-Module-AddReads") != null) { //$NON-NLS-1$
                    reads.addAll(toReads(b.getHeaders().get("Java-Module-AddReads"), b)); //$NON-NLS-1$
                }
            }
        }

        final String addReads = System.getProperty("fxloader.osgi.eclipse.hook.add-reads"); //$NON-NLS-1$
        final String addOpens = System.getProperty("fxloader.osgi.eclipse.hook.add-opens"); //$NON-NLS-1$
        final String addExports = System.getProperty("fxloader.osgi.eclipse.hook.add-exports"); //$NON-NLS-1$

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
        if (value.endsWith("=.") && bundle != null) { //$NON-NLS-1$
            return value.replace("=.", "=BUNDLE(@" + bundle.getBundleId() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return value;
    }

    private static Set<AddReads> toReads(final String value, final Bundle bundle) {
        return Stream.of(value.split(",")) //$NON-NLS-1$
                .map(v -> adaptAllUnnamed(v, bundle)).map(AddReads::valueOf) //
                .filter(Optional::isPresent) //
                .map(Optional::get) //
                .collect(Collectors.toSet());
    }

    private static Set<AddOpenExports> toOpenExports(final String value, final Bundle bundle) {
        return Stream.of(value.split(",")) //$NON-NLS-1$
                .map(v -> adaptAllUnnamed(v, bundle)).map(AddOpenExports::valueOf) //
                .filter(Optional::isPresent) //
                .map(Optional::get) //
                .collect(Collectors.toSet());
    }

    private static ModuleLayer initModuleLayer(final ClassLoader parentClassloader,
            final List<FXProviderBundle> bundles, final JavaModuleLayerModification modifications) throws Throwable {
        try {
            if (Boolean.getBoolean("fxloader.osgi.eclipse.hook.advanced-modules") || !modifications.isEmpty()) { //$NON-NLS-1$
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
        if (FXClassloaderConfigurator.DEBUG) {
            System.err.println(
                    "FXClassLoader#advancedModuleLayerBoostrap - Using advanced layer creation to apply patches"); //$NON-NLS-1$
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
                System.err.println("FXClassLoader#advancedModuleLayerBoostrap - " + b.module + " => " + b.path); //$NON-NLS-1$ //$NON-NLS-2$
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
        final Path[] paths = bundles.stream().map(p -> p.path).toArray(i -> new Path[i]);
        final Set<String> modules = bundles.stream().map(p -> p.module).collect(Collectors.toSet());

        if (FXClassloaderConfigurator.DEBUG) {
            for (final FXProviderBundle b : bundles) {
                System.err.println("FXClassLoader#defaultModuleLayerBootstrap" + b.module + " => " + b.path); //$NON-NLS-1$ //$NON-NLS-2$
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
        synchronized (urlTrackers) {
            ServiceTracker<Object, URLConverter> tracker = urlTrackers.get(protocol);
            if (tracker == null) {
                // get the right service based on the protocol
                final String FILTER_PREFIX = "(&(objectClass=" + URLConverter.class.getName() + ")(protocol="; //$NON-NLS-1$ //$NON-NLS-2$
                final String FILTER_POSTFIX = "))"; //$NON-NLS-1$
                Filter filter = null;
                try {
                    filter = ctx.createFilter(FILTER_PREFIX + protocol + FILTER_POSTFIX);
                } catch (final InvalidSyntaxException e) {
                    return null;
                }
                tracker = new ServiceTracker<>(ctx, filter, null);
                tracker.open();
                // cache it in the registry
                urlTrackers.put(protocol, tracker);
            }
            return tracker.getService();
        }
    }

    @Override
    public synchronized ModuleClassLoader createClassLoader(final ClassLoader parent,
            final EquinoxConfiguration configuration, final BundleLoader delegate, final Generation generation) {
        // FIXME Can we get rid of this?
        if (this.frameworkContext == null) {
            this.frameworkContext = generation.getBundleInfo().getStorage().getModuleContainer().getFrameworkWiring()
                    .getBundle().getBundleContext();
        }
        if (FX_SYMBOLIC_NAME.equals(generation.getRevision().getBundle().getSymbolicName())) {
            System.err.println(
                    "ERROR: Binding against 'org.eclipse.fx.javafx' had been deprecated since 2.x and has been removed in 3.x "); //$NON-NLS-1$
            // System.err.println("WARNING: You are binding against the deprecated org.eclipse.fx.javafx - please remove
            // all javafx imports"); //$NON-NLS-1$
            // URLClassLoader cl = getFXClassloader();
            // return new FXModuleClassloader(this.swtAvailable, cl, parent, configuration, delegate,
            // generation);
        }
        return super.createClassLoader(parent, configuration, delegate, generation);
    }

    private static ClassLoader getSWTClassloader(final BundleContext context) {
        if (FXClassloaderConfigurator.DEBUG) {
            System.err.println("FXClassLoader#getSWTClassloader - Fetching SWT-Classloader");
        }

        try {
            // Should we better use findProviders() see PackageAdminImpl?
            for (final Bundle b : context.getBundles()) {
                if (SWT_SYMBOLIC_NAME.equals(b.getSymbolicName())) {
                    if ((b.getState() & Bundle.INSTALLED) == 0) {
                        // Ensure the bundle is started else we are unable to
                        // extract the
                        // classloader
                        if ((b.getState() & Bundle.ACTIVE) != 0) {
                            try {
                                b.start();
                            } catch (final BundleException e) {
                                e.printStackTrace();
                            }
                        }
                        return b.adapt(BundleWiring.class).getClassLoader();
                    }

                }
            }
        } catch (final Throwable t) {
            System.err.println("Failed to access swt classloader"); //$NON-NLS-1$
            t.printStackTrace();
        } finally {
            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#getSWTClassloader - Done SWT-Classloader");
            }
        }

        return null;
    }

    private static List<FXProviderBundle> getDeployedJavaModuleBundlePaths(final BundleContext context) {
        final List<FXProviderBundle> paths = new ArrayList<>();

        if (FXClassloaderConfigurator.DEBUG) {
            System.err.println(
                    "FXClassLoader#getDeployedJavaModuleBundlePaths - Loading libraries from deployed modules"); //$NON-NLS-1$
        }
        for (final Bundle b : context.getBundles()) {
            if (((b.getState() & Bundle.RESOLVED) == Bundle.RESOLVED || (b.getState() & Bundle.ACTIVE) == Bundle.ACTIVE)
                    && b.getHeaders().get("Java-Module") != null) { //$NON-NLS-1$
                final String name = b.getHeaders().get("Java-Module"); //$NON-NLS-1$
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println(
                            "FXClassLoader#getDeployedJavaModuleBundlePaths - Found OSGi-Module with JPMS-Module '" //$NON-NLS-1$
                                    + name + "'"); //$NON-NLS-1$
                }
                URL entry = b.getEntry(name + ".jar"); //$NON-NLS-1$
                // if it is an automatic module - is used
                if (entry == null) {
                    entry = b.getEntry(name.replace('.', '-') + ".jar"); //$NON-NLS-1$
                }

                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader#getDeployedJavaModuleBundlePaths - Found Jar '" + entry + "'"); //$NON-NLS-1$//$NON-NLS-2$
                }

                if (entry != null) {
                    final URLConverter converter = getURLConverter(entry, context);
                    try {
                        final URL url = converter.toFileURL(entry);
                        if (FXClassloaderConfigurator.DEBUG) {
                            System.err
                                    .println("FXClassLoader#getDeployedJavaModuleBundlePaths - Converted URL: " + url); //$NON-NLS-1$
                        }
                        String file = url.getFile();
                        if (System.getProperty("os.name").toLowerCase().contains("windows")) { //$NON-NLS-1$ //$NON-NLS-2$
                            if (file.startsWith("/")) { //$NON-NLS-1$
                                file = file.substring(1); // remove the leading /
                            }
                        }
                        paths.add(new FXProviderBundle(name, Paths.get(file)));
                    } catch (final Throwable e) {
                        if (FXClassloaderConfigurator.DEBUG) {
                            System.err.println("Failed to load get path"); //$NON-NLS-1$
                            e.printStackTrace();
                        }
                        throw new IllegalStateException(e);
                    }
                }
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
            return "FXProviderBundle [module=" + this.module + ", path=" + this.path + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

}
