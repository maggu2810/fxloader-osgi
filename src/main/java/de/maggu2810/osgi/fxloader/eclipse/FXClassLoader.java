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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import de.maggu2810.osgi.fxloader.eclipse.jpms.ConfigurationWrapper;
import de.maggu2810.osgi.fxloader.eclipse.jpms.JavaModuleLayerModification;
import de.maggu2810.osgi.fxloader.eclipse.jpms.ModuleFinderWrapper;
import de.maggu2810.osgi.fxloader.eclipse.jpms.ModuleLayerWrapper;
import de.maggu2810.osgi.fxloader.eclipse.jpms.ModuleLayerWrapper.ControllerWrapper;
import de.maggu2810.osgi.fxloader.eclipse.jpms.ModuleWrapper;

/**
 * Hook to overwrite OSGis default classloading
 */
public class FXClassLoader extends ClassLoaderHook {
    private static final String FX_SYMBOLIC_NAME = "org.eclipse.fx.javafx"; //$NON-NLS-1$
    private static final String SWT_SYMBOLIC_NAME = "org.eclipse.swt"; //$NON-NLS-1$

    private final AtomicBoolean boostrappingModules = new AtomicBoolean();
    private URLClassLoader classLoader;
    // private boolean swtAvailable;
    private BundleContext frameworkContext;
    private ClassLoader j9Classloader;
    private ModuleLayerWrapper moduleLayer;
    private ClassLoader j11Classloader;
    private static Boolean IS_EQUAL_GREATER_11;
    private static Boolean IS_JAVA_8;
    private static Map<String, ServiceTracker<Object, URLConverter>> urlTrackers = new HashMap<>();
    private Set<String> j11ModulePackages;
    private boolean reentrance;

    static boolean isJDK8() {
        if (IS_JAVA_8 != null) {
            return IS_JAVA_8.booleanValue();
        }
        final boolean rv = System.getProperty("java.version", "").startsWith("1.8"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IS_JAVA_8 = Boolean.valueOf(rv);
        return rv;
    }

    private static boolean isEqualGreaterJDK11() {
        if (IS_EQUAL_GREATER_11 != null) {
            return IS_EQUAL_GREATER_11.booleanValue();
        }
        final String version = System.getProperty("java.version", ""); //$NON-NLS-1$//$NON-NLS-2$
        final String[] parts = version.split("\\D"); //$NON-NLS-1$
        boolean rv = false;
        try {
            rv = Integer.parseInt(parts[0]) >= 11;
        } catch (final Throwable e) {
            // TODO: handle exception
        }
        IS_EQUAL_GREATER_11 = Boolean.valueOf(rv);
        return rv;
    }

    @SuppressWarnings("resource")
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
            // this is pre java 9
            if (isJDK8()) {
                if ((name.startsWith("javafx") //$NON-NLS-1$
                        || name.startsWith("netscape.javascript") //$NON-NLS-1$
                        || name.startsWith("com.sun.glass.events") //$NON-NLS-1$
                        || name.startsWith("com.sun.glass.ui") //$NON-NLS-1$
                        || name.startsWith("com.sun.javafx") //$NON-NLS-1$
                        || name.startsWith("com.sun.media.jfxmedia") //$NON-NLS-1$
                        || name.startsWith("com.sun.media.jfxmediaimpl") //$NON-NLS-1$
                        || name.startsWith("com.sun.openpisces") //$NON-NLS-1$
                        || name.startsWith("com.sun.pisces") //$NON-NLS-1$
                        || name.startsWith("com.sun.prism") //$NON-NLS-1$
                        || name.startsWith("com.sun.scenario") //$NON-NLS-1$
                        || name.startsWith("com.sun.webkit") //$NON-NLS-1$
                ) && !moduleClassLoader.getBundle().getSymbolicName().equals("org.eclipse.swt")) { //$NON-NLS-1$
                    final URLClassLoader fxClassloader = getFXClassloader();
                    if (fxClassloader != null) {
                        return fxClassloader.loadClass(name);
                    } else {
                        throw new ClassNotFoundException(
                                "Unable to locate JavaFX. Please make sure you have a JDK with JavaFX installed eg on Linux you require an Oracle JDK"); //$NON-NLS-1$
                    }
                }
            } else if (isEqualGreaterJDK11()) {
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
            } else {
                // We only need special things for javafx.embed because osgi-bundles on java9
                // have the ExtClassloader as its parent
                if (name.startsWith("javafx.embed")) { //$NON-NLS-1$
                    synchronized (this) {
                        if (this.j9Classloader == null) {
                            try {
                                this.j9Classloader = getModuleLayer().findLoader("javafx.swt"); //$NON-NLS-1$

                                try {
                                    this.j9Classloader.loadClass("javafx.application.Platform") //$NON-NLS-1$
                                            .getDeclaredMethod("setImplicitExit", boolean.class) //$NON-NLS-1$
                                            .invoke(null, Boolean.FALSE);
                                } catch (final Throwable e) {
                                    e.printStackTrace();
                                }
                            } catch (final Throwable e) {
                                throw new ClassNotFoundException("Could not find class '" + name + "'", e); //$NON-NLS-1$//$NON-NLS-2$
                            }
                        }
                        return this.j9Classloader.loadClass(name);
                    }
                }
            }

            return super.postFindClass(name, moduleClassLoader);
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
                    final ModuleLayerWrapper layer = getModuleLayer();
                    final Set<ModuleWrapper> modules = layer.modules();
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

    private synchronized ModuleLayerWrapper getModuleLayer() throws Throwable {
        if (isEqualGreaterJDK11()) {
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
        } else {
            if (this.moduleLayer == null) {
                final Path path = Paths.get(System.getProperty("java.home")).resolve("lib").resolve("javafx-swt.jar"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

                ClassLoader parentClassloader = getSWTClassloader(this.frameworkContext);
                if (parentClassloader == null) {
                    parentClassloader = getClass().getClassLoader();
                }

                this.moduleLayer = initModuleLayer(parentClassloader,
                        Collections.singletonList(new FXProviderBundle("javafx.swt", path)), //$NON-NLS-1$
                        JavaModuleLayerModification.empty());
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

        final String addReads = System.getProperty("efxclipse.osgi.hook.add-reads"); //$NON-NLS-1$
        final String addOpens = System.getProperty("efxclipse.osgi.hook.add-opens"); //$NON-NLS-1$
        final String addExports = System.getProperty("efxclipse.osgi.hook.add-exports"); //$NON-NLS-1$

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

    private static ModuleLayerWrapper initModuleLayer(final ClassLoader parentClassloader,
            final List<FXProviderBundle> bundles, final JavaModuleLayerModification modifications) throws Throwable {
        try {
            if (Boolean.getBoolean("efxclipse.osgi.hook.advanced-modules") || !modifications.isEmpty()) { //$NON-NLS-1$
                return advancedModuleLayerBoostrap(parentClassloader, bundles, modifications);
            } else {
                return defaultModuleLayerBootstrap(parentClassloader, bundles);
            }
        } catch (final Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    private static ModuleLayerWrapper advancedModuleLayerBoostrap(final ClassLoader parentClassloader,
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
            // Method is defined on Java-9 onwards
            @SuppressWarnings("unused")
            protected java.lang.Class<?> findClass(final String moduleName, final String name) {
                try {
                    return findClass(name);
                } catch (final ClassNotFoundException e) {
                    /* intentional empty */}
                return null;
            }

            // Method is defined on Java-9 onwards
            @SuppressWarnings("unused")
            protected URL findResource(final String moduleName, final String name) throws IOException {
                return findResource(name);
            }
        };

        final ModuleFinderWrapper fxModuleFinder = ModuleFinderWrapper.of(paths);
        final ModuleFinderWrapper empty = ModuleFinderWrapper.of();
        final ModuleLayerWrapper bootLayer = ModuleLayerWrapper.boot();
        final ConfigurationWrapper configuration = bootLayer.configuration();
        final ConfigurationWrapper newConfiguration = configuration.resolve(fxModuleFinder, empty, modules);
        final Function<String, ClassLoader> clComputer = s -> c;
        final ControllerWrapper moduleLayerController = ModuleLayerWrapper.defineModules(newConfiguration,
                Arrays.asList(bootLayer), clComputer);
        modifications.applyConfigurations(moduleLayerController);

        return moduleLayerController.layer();
    }

    private static ModuleLayerWrapper defaultModuleLayerBootstrap(final ClassLoader parentClassloader,
            final List<FXProviderBundle> bundles) throws Throwable {
        final Path[] paths = bundles.stream().map(p -> p.path).toArray(i -> new Path[i]);
        final Set<String> modules = bundles.stream().map(p -> p.module).collect(Collectors.toSet());

        if (FXClassloaderConfigurator.DEBUG) {
            for (final FXProviderBundle b : bundles) {
                System.err.println("FXClassLoader#defaultModuleLayerBootstrap" + b.module + " => " + b.path); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        final ModuleFinderWrapper finder = ModuleFinderWrapper.of(paths);
        final ModuleLayerWrapper boot = ModuleLayerWrapper.boot();
        final ConfigurationWrapper configuration = boot.configuration();
        final ModuleFinderWrapper of = ModuleFinderWrapper.of();
        final ConfigurationWrapper cf = configuration.resolve(finder, of, modules);
        final ModuleLayerWrapper layer = boot.defineModulesWithOneLoader(cf, parentClassloader);

        return layer;
    }

    private URLClassLoader getFXClassloader() {
        if (this.classLoader == null) {
            final ClassLoader swtClassloader = getSWTClassloader(this.frameworkContext);
            // this.swtAvailable = swtClassloader != null;
            this.classLoader = createJREBundledClassloader(
                    swtClassloader == null ? ClassLoader.getSystemClassLoader() : swtClassloader,
                    swtClassloader != null);
        }
        return this.classLoader;
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

    private static URLClassLoader createJREBundledClassloader(final ClassLoader parent, final boolean swtAvailable) {
        if (FXClassloaderConfigurator.DEBUG) {
            System.err.println("FXClassLoader#createJREBundledClassloader - Started"); //$NON-NLS-1$
        }

        try {
            File javaHome;
            try {
                javaHome = new File(System.getProperty("java.home")).getCanonicalFile(); //$NON-NLS-1$
            } catch (final IOException e) {
                throw new IllegalStateException("Unable to locate java home", e); //$NON-NLS-1$
            }
            if (!javaHome.exists()) {
                throw new IllegalStateException("The java home '" //$NON-NLS-1$
                        + javaHome.getAbsolutePath() + "' does not exits"); //$NON-NLS-1$
            }

            // Java 8 and maybe one day Java 7
            File jarFile = new File(new File(new File(javaHome.getAbsolutePath(), "lib"), "ext"), "jfxrt.jar"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#createJREBundledClassloader - Assumed location (Java 8/Java 7): " //$NON-NLS-1$
                        + jarFile.getAbsolutePath());
            }

            if (jarFile.exists()) {
                // if SWT is available we need to construct a new
                // URL-Classloader with SWT
                // bundles classloader as the parent
                if (swtAvailable) {
                    if (FXClassloaderConfigurator.DEBUG) {
                        System.err.println(
                                "FXClassLoader#createJREBundledClassloader - SWT is available use different loading strategy"); //$NON-NLS-1$
                    }

                    // Since JDK8b113 the swt stuff is in its own jar
                    final File swtFX = new File(new File(javaHome.getAbsolutePath(), "lib"), "jfxswt.jar"); //$NON-NLS-1$//$NON-NLS-2$

                    if (FXClassloaderConfigurator.DEBUG) {
                        System.err.println(
                                "FXClassLoader#createJREBundledClassloader - Searching for SWT-FX integration at " //$NON-NLS-1$
                                        + swtFX.getAbsolutePath());
                    }

                    if (swtFX.exists()) {
                        if (FXClassloaderConfigurator.DEBUG) {
                            System.err.println("FXClassLoader#createJREBundledClassloader - Found SWT/FX"); //$NON-NLS-1$
                        }

                        final ClassLoader extClassLoader = ClassLoader.getSystemClassLoader().getParent();
                        if (extClassLoader.getClass().getName().equals("sun.misc.Launcher$ExtClassLoader")) { //$NON-NLS-1$
                            if (FXClassloaderConfigurator.DEBUG) {
                                System.err.println(
                                        "FXClassLoader#createJREBundledClassloader - Delegate to system classloader"); //$NON-NLS-1$
                            }

                            return new URLClassLoader(new URL[] { swtFX.getCanonicalFile().toURI().toURL() },
                                    new SWTFXClassloader(parent, extClassLoader));
                        }

                        if (FXClassloaderConfigurator.DEBUG) {
                            System.err.println("FXClassLoader#createJREBundledClassloader - Using an URL classloader"); //$NON-NLS-1$
                        }

                        return new URLClassLoader(new URL[] { jarFile.getCanonicalFile().toURI().toURL(),
                                swtFX.getCanonicalFile().toURI().toURL() }, parent);
                    } else {
                        if (FXClassloaderConfigurator.DEBUG) {
                            System.err.println(
                                    "FXClassLoader#createJREBundledClassloader - Assume that SWT-FX part of jfxrt.jar"); //$NON-NLS-1$
                        }

                        final URL url = jarFile.getCanonicalFile().toURI().toURL();
                        return new URLClassLoader(new URL[] { url }, parent);
                    }
                } else {
                    // we should be able to delegate to the
                    // URL-Extension-Classloader, which is essential for
                    // ScenicView
                    // which installs an JMX-Component
                    try {
                        final ClassLoader extClassLoader = ClassLoader.getSystemClassLoader().getParent();
                        if (extClassLoader.getClass().getName().equals("sun.misc.Launcher$ExtClassLoader")) { //$NON-NLS-1$
                            if (FXClassloaderConfigurator.DEBUG) {
                                System.err.println(
                                        "FXClassLoader#createJREBundledClassloader - Delegate to system classloader"); //$NON-NLS-1$
                            }
                            return new URLClassLoader(new URL[] {}, extClassLoader);
                        }
                    } catch (final Throwable t) {
                        t.printStackTrace();
                    }

                    if (FXClassloaderConfigurator.DEBUG) {
                        System.err.println("FXClassLoader#createJREBundledClassloader - Using an URL classloader"); //$NON-NLS-1$
                    }

                    final URL url = jarFile.getCanonicalFile().toURI().toURL();
                    final URLClassLoader cl = new URLClassLoader(new URL[] { url }, parent);
                    return cl;
                }
            }

            // Java 7
            jarFile = new File(new File(javaHome.getAbsolutePath(), "lib"), //$NON-NLS-1$
                    "jfxrt.jar"); //$NON-NLS-1$
            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#createJREBundledClassloader - Assumed location (Java 7): " //$NON-NLS-1$
                        + jarFile.getAbsolutePath());
            }

            if (jarFile.exists()) {
                final URL url = jarFile.getCanonicalFile().toURI().toURL();
                return new URLClassLoader(new URL[] { url }, parent);
            } else {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader#createJREBundledClassloader - File does not exist."); //$NON-NLS-1$
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
        } finally {
            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader#createJREBundledClassloader - Ended"); //$NON-NLS-1$
            }
        }

        return null;
    }

    static class EmptyEnumeration implements Enumeration<URL> {
        static Enumeration<URL> INSTANCE = new EmptyEnumeration();

        @Override
        public boolean hasMoreElements() {
            return false;
        }

        @Override
        public URL nextElement() {
            return null;
        }

    }

    static class SWTFXClassloader extends ClassLoader {
        private final ClassLoader lastResortLoader;
        private final ClassLoader primaryLoader;
        private Boolean checkFlag = null;

        public SWTFXClassloader(final ClassLoader lastResortLoader, final ClassLoader primaryLoader) {
            this.lastResortLoader = lastResortLoader;
            this.primaryLoader = primaryLoader;
            if (FXClassloaderConfigurator.DEBUG) {
                System.err.println("FXClassLoader.SWTFXClassloader#init - Primary Loader " + primaryLoader); //$NON-NLS-1$
                System.err.println("FXClassLoader.SWTFXClassloader#init - Lastresort Loader " + lastResortLoader); //$NON-NLS-1$
            }
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            // We can never load stuff from there and this would lead to a loop
            if (name.startsWith("javafx.embed.swt")) { //$NON-NLS-1$
                if (this.checkFlag == null) {
                    this.checkFlag = Boolean.TRUE;

                    // On JDK-8 we need to check for GTK3
                    if (isJDK8()) {
                        boolean supportsGtk3 = false;
                        final String version = System.getProperty("java.version", ""); //$NON-NLS-1$//$NON-NLS-2$

                        final Matcher matcher = Pattern.compile(".+_(\\d+).*").matcher(version); //$NON-NLS-1$
                        if (matcher.matches() && matcher.groupCount() > 0) {
                            supportsGtk3 = Integer.parseInt(matcher.group(1)) >= 202;
                        }

                        if (!supportsGtk3) {
                            Boolean isGTK3 = Boolean.FALSE;
                            try {

                                if (FXClassloaderConfigurator.DEBUG) {
                                    System.err.println(
                                            "FXModuleClassloader#findLocalClass - Someone is trying to load FXCanvas. Need to check for GTK3"); //$NON-NLS-1$
                                }

                                // Check for GTK3
                                final String value = (String) loadClass("org.eclipse.swt.SWT") //$NON-NLS-1$
                                        .getDeclaredMethod("getPlatform") //$NON-NLS-1$
                                        .invoke(null);
                                if ("gtk".equals(value)) { //$NON-NLS-1$
                                    if (FXClassloaderConfigurator.DEBUG) {
                                        System.err.println(
                                                "FXModuleClassloader#findLocalClass - We are on GTK need to take a closer look"); //$NON-NLS-1$
                                    }

                                    // Constants moved with Photon
                                    try {
                                        isGTK3 = (Boolean) loadClass("org.eclipse.swt.internal.gtk.GTK") //$NON-NLS-1$
                                                .getDeclaredField("GTK3") //$NON-NLS-1$
                                                .get(null);
                                    } catch (final Throwable t) {
                                        isGTK3 = (Boolean) loadClass("org.eclipse.swt.internal.gtk.OS") //$NON-NLS-1$
                                                .getDeclaredField("GTK3") //$NON-NLS-1$
                                                .get(null);
                                    }
                                } else {
                                    if (FXClassloaderConfigurator.DEBUG) {
                                        System.err.println("FXModuleClassloader#findLocalClass - OS is '" + value //$NON-NLS-1$
                                                + "' no need to get upset all is fine"); //$NON-NLS-1$
                                    }
                                }
                            } catch (final Throwable e) {
                                System.err.println("FXModuleClassloader#findLocalClass - Failed to check for Gtk3"); //$NON-NLS-1$
                                e.printStackTrace();
                            }

                            if (isGTK3.booleanValue()) {
                                if (FXClassloaderConfigurator.DEBUG) {
                                    System.err.println(
                                            "FXModuleClassloader#findLocalClass - We are on GTK3 - too bad need to disable JavaFX for now else we'll crash the JVM"); //$NON-NLS-1$
                                }
                                throw new ClassNotFoundException(
                                        "SWT is running with GTK3 but JavaFX is linked against GTK2"); //$NON-NLS-1$
                            }
                        }

                    }

                    try {
                        loadClass("javafx.application.Platform").getDeclaredMethod("setImplicitExit", boolean.class) //$NON-NLS-1$ //$NON-NLS-2$
                                .invoke(null, Boolean.FALSE);
                    } catch (final Throwable e) {
                        e.printStackTrace();
                    }
                }
                return super.findClass(name);
            }
            try {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader.SWTFXClassloader#findClass - Loading " + name + " with primary"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                final Class<?> loadClass = this.primaryLoader.loadClass(name);
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader.SWTFXClassloader#findClass - Result " + name + " " + loadClass); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return loadClass;
            } catch (final ClassNotFoundException c) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("FXClassLoader.SWTFXClassloader#findClass - ClassNotFoundException thrown"); //$NON-NLS-1$
                    System.err.println(
                            "FXClassLoader.SWTFXClassloader#findClass - Loading " + name + " with last resort"); //$NON-NLS-1$ //$NON-NLS-2$
                }

                try {
                    final Class<?> loadClass = this.lastResortLoader.loadClass(name);
                    if (FXClassloaderConfigurator.DEBUG) {
                        System.err
                                .println("FXClassLoader.SWTFXClassloader#findClass - Result " + name + " " + loadClass); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    return loadClass;
                } catch (final ClassNotFoundException tmp) {
                    if (FXClassloaderConfigurator.DEBUG) {
                        System.err.println(
                                "FXClassLoader.SWTFXClassloader#findClass - Even last resort was unable to load " //$NON-NLS-1$
                                        + name);
                    }
                    throw c;
                }
            }
        }

        @Override
        protected URL findResource(final String name) {
            URL url = this.primaryLoader.getResource(name);
            if (url == null) {
                url = this.lastResortLoader.getResource(name);
            }
            return url;
        }

        @Override
        protected Enumeration<URL> findResources(final String name) throws IOException {
            final Enumeration<URL> en1 = this.primaryLoader.getResources(name);
            final Enumeration<URL> en2 = this.lastResortLoader.getResources(name);

            return new Enumeration<URL>() {
                @Override
                public boolean hasMoreElements() {
                    if (en1.hasMoreElements()) {
                        return true;
                    }
                    return en2.hasMoreElements();
                }

                @Override
                public URL nextElement() {
                    if (!en1.hasMoreElements()) {
                        return en2.nextElement();
                    }
                    return en1.nextElement();
                }
            };
        }

        @Override
        public URL getResource(final String name) {
            URL url = this.primaryLoader.getResource(name);
            if (url == null) {
                url = this.lastResortLoader.getResource(name);
            }
            return url;
        }

        @SuppressWarnings("resource")
        @Override
        public InputStream getResourceAsStream(final String name) {
            InputStream in = this.primaryLoader.getResourceAsStream(name);
            if (in == null) {
                in = this.lastResortLoader.getResourceAsStream(name);
            }
            return in;
        }

        @Override
        public Enumeration<URL> getResources(final String name) throws IOException {
            final Enumeration<URL> en1 = this.primaryLoader.getResources(name);
            final Enumeration<URL> en2 = this.lastResortLoader.getResources(name);

            return new Enumeration<URL>() {
                @Override
                public boolean hasMoreElements() {
                    if (en1.hasMoreElements()) {
                        return true;
                    }
                    return en2.hasMoreElements();
                }

                @Override
                public URL nextElement() {
                    if (!en1.hasMoreElements()) {
                        return en2.nextElement();
                    }
                    return en1.nextElement();
                }
            };
        }

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            try {
                return this.primaryLoader.loadClass(name);
            } catch (final ClassNotFoundException c) {
                try {
                    return this.lastResortLoader.loadClass(name);
                } catch (final ClassNotFoundException tmp) {
                    throw c;
                }
            }
        }
    }
}
