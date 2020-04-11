/*******************************************************************************
 * Copyright (c) 2018 BestSolution.at and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Tom Schindl<tom.schindl@bestsolution.at> - initial API and implementation
 *******************************************************************************/

package de.maggu2810.osgi.fxloader.eclipse.jpms;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@SuppressWarnings("javadoc")
public class ModuleLayerWrapper {
    private static Class<?> CLASS;
    private static Method boot;
    private static Method configuration;
    private static Method defineModules;
    private static Method defineModulesWithOneLoader;
    private static Method findLoader;
    private static Method findModule;
    private static Method modules;

    public final Object self;

    public ModuleLayerWrapper(final Object self) {
        this.self = self;
        init();
    }

    public static Class<?> CLASS() {
        init();
        return CLASS;
    }

    private static void init() {
        if (CLASS == null) {
            try {
                CLASS = ModuleLayerWrapper.class.getClassLoader().loadClass("java.lang.ModuleLayer"); //$NON-NLS-1$
                boot = CLASS.getMethod("boot"); //$NON-NLS-1$
                configuration = CLASS.getMethod("configuration"); //$NON-NLS-1$
                defineModules = CLASS.getMethod("defineModules", ConfigurationWrapper.CLASS(), List.class, //$NON-NLS-1$
                        Function.class);
                defineModulesWithOneLoader = CLASS.getMethod("defineModulesWithOneLoader", ConfigurationWrapper.CLASS(), //$NON-NLS-1$
                        ClassLoader.class);
                findLoader = CLASS.getMethod("findLoader", String.class); //$NON-NLS-1$
                findModule = CLASS.getMethod("findModule", String.class); //$NON-NLS-1$
                modules = CLASS.getMethod("modules"); //$NON-NLS-1$
            } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static ModuleLayerWrapper boot() {
        init();
        try {
            return new ModuleLayerWrapper(boot.invoke(null));
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public ConfigurationWrapper configuration() {
        init();
        try {
            return new ConfigurationWrapper(configuration.invoke(this.self));
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static ControllerWrapper defineModules(final ConfigurationWrapper cf,
            final List<ModuleLayerWrapper> parentLayers, final Function<String, ClassLoader> clf) {
        init();
        final List<Object> _parentLayers = parentLayers.stream().map(w -> w.self).collect(Collectors.toList());
        try {
            return new ControllerWrapper(defineModules.invoke(null, cf.self, _parentLayers, clf));
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public ModuleLayerWrapper defineModulesWithOneLoader(final ConfigurationWrapper cf,
            final ClassLoader parentLoader) {
        init();
        try {
            return new ModuleLayerWrapper(defineModulesWithOneLoader.invoke(this.self, cf.self, parentLoader));
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public ClassLoader findLoader(final String name) {
        init();
        try {
            return (ClassLoader) findLoader.invoke(this.self, name);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<ModuleWrapper> findModule(final String n) {
        init();
        try {
            final Optional<?> invoke = (Optional<?>) findModule.invoke(this.self, n);
            return invoke.map(o -> new ModuleWrapper(o));
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<ModuleWrapper> modules() {
        init();
        try {
            final Set<?> invoke = (Set<?>) modules.invoke(this.self);
            final Set<ModuleWrapper> rv = new HashSet<>(invoke.size());
            for (final Object o : invoke) {
                rv.add(new ModuleWrapper(o));
            }
            return rv;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static class ControllerWrapper {

        private static Class<?> CLASS; // need to derive from instance
        private static Method layer;
        private static Method addExports;
        private static Method addOpens;
        private static Method addReads;

        public final Object self;

        public ControllerWrapper(final Object self) {
            this.self = self;
            if (CLASS == null) {
                CLASS = self.getClass();
                try {
                    layer = CLASS.getMethod("layer"); //$NON-NLS-1$
                    addReads = CLASS.getMethod("addReads", ModuleWrapper.CLASS(), ModuleWrapper.CLASS()); //$NON-NLS-1$
                    addExports = CLASS.getMethod("addExports", ModuleWrapper.CLASS(), String.class, //$NON-NLS-1$
                            ModuleWrapper.CLASS());
                    addOpens = CLASS.getMethod("addOpens", ModuleWrapper.CLASS(), String.class, ModuleWrapper.CLASS()); //$NON-NLS-1$
                } catch (NoSuchMethodException | SecurityException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        public ModuleLayerWrapper layer() {
            try {
                return new ModuleLayerWrapper(layer.invoke(this.self));
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        public ControllerWrapper addExports(final ModuleWrapper source, final String pn, final ModuleWrapper target) {
            try {
                addExports.invoke(this.self, source.self, pn, target.self);
                return this;
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        public ControllerWrapper addOpens(final ModuleWrapper source, final String pn, final ModuleWrapper target) {
            try {
                addOpens.invoke(this.self, source.self, pn, target.self);
                return this;
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        public ControllerWrapper addReads(final ModuleWrapper source, final ModuleWrapper target) {
            try {
                addReads.invoke(this.self, source.self, target.self);
                return this;
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
