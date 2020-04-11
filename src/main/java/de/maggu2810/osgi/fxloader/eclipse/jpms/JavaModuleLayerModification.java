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

import java.lang.ModuleLayer.Controller;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleWiring;

import de.maggu2810.osgi.fxloader.eclipse.FXClassloaderConfigurator;

@SuppressWarnings("javadoc")
public class JavaModuleLayerModification {
    private final Set<AddReads> reads;
    private final Set<AddOpenExports> exports;
    private final Set<AddOpenExports> opens;

    private final Bundle[] bundles;

    public JavaModuleLayerModification(final Bundle[] bundles, final Set<AddReads> reads,
            final Set<AddOpenExports> exports, final Set<AddOpenExports> opens) {
        this.bundles = bundles;
        this.reads = reads;
        this.exports = exports;
        this.opens = opens;
    }

    public boolean isEmpty() {
        return this.reads.isEmpty() && this.exports.isEmpty() && this.opens.isEmpty();
    }

    public static JavaModuleLayerModification empty() {
        return new JavaModuleLayerModification(new Bundle[0], Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet());
    }

    private static Module getUnnamedModule() {
        return JavaModuleLayerModification.class.getClassLoader().getUnnamedModule();
    }

    private Module getBundleUnnamed(final String value) {
        if (value.startsWith("BUNDLE(@")) { //$NON-NLS-1$
            final String id = value.substring(8, value.length() - 1);
            final long l = Long.parseLong(id);
            for (final Bundle b : this.bundles) {
                if (b.getBundleId() == l) {
                    return b.adapt(BundleWiring.class).getClassLoader().getUnnamedModule();
                }
            }
        } else if (value.startsWith("BUNDLE(")) { //$NON-NLS-1$
            final String nameVersion = value.substring(7, value.length() - 1);
            final int idx = nameVersion.indexOf('@');
            if (idx > -1) {
                final String symbolicName = nameVersion.substring(0, idx);
                final Version version = Version.parseVersion(nameVersion.substring(idx + 1));

                for (final Bundle b : this.bundles) {
                    if (b.getSymbolicName().equals(symbolicName) && version.equals(b.getVersion())) {
                        return b.adapt(BundleWiring.class).getClassLoader().getUnnamedModule();
                    }
                }
            }
        }
        return null;
    }

    public void applyConfigurations(final Controller controller) {
        final Map<String, Module> map = new HashMap<>();
        for (final AddOpenExports e : this.exports) {
            final Module sourceModule = map.computeIfAbsent(e.source,
                    n -> controller.layer().findModule(n).orElse(null));
            Module targetModule = null;
            if (e.target.equals("ALL-UNNAMED")) { //$NON-NLS-1$
                targetModule = getUnnamedModule();
            } else if (e.target.startsWith("BUNDLE")) { //$NON-NLS-1$
                targetModule = getBundleUnnamed(e.target);
            } else {
                targetModule = map.computeIfAbsent(e.target, n -> {
                    return Stream.of(controller.layer(), ModuleLayer.boot()) //
                            .map(l -> l.findModule(n).orElse(null)) //
                            .findFirst().orElse(null);
                });
            }

            if (sourceModule == null) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Source module '" + e.source //$NON-NLS-1$
                            + "' is not dynamically loaded. Could not export '" + e.pn + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            } else if (targetModule == null) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Target module '" + e.target //$NON-NLS-1$
                            + "' is not found. Could not export '" + e.pn + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            } else {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Exporting '" + e + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                controller.addExports(sourceModule, e.pn, targetModule);
            }
        }

        for (final AddOpenExports e : this.opens) {
            final Module sourceModule = map.computeIfAbsent(e.source,
                    n -> controller.layer().findModule(n).orElse(null));
            Module targetModule = null;
            if (e.target.equals("ALL-UNNAMED")) { //$NON-NLS-1$
                targetModule = getUnnamedModule();
            } else if (e.target.startsWith("BUNDLE")) { //$NON-NLS-1$
                targetModule = getBundleUnnamed(e.target);
            } else {
                targetModule = map.computeIfAbsent(e.target, n -> {
                    return Stream.of(controller.layer(), ModuleLayer.boot()) //
                            .map(l -> l.findModule(n).orElse(null)) //
                            .findFirst().orElse(null);
                });
            }

            if (sourceModule == null) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Source module '" + e.source //$NON-NLS-1$
                            + "' is not dynamically loaded. Could not open '" + e.pn + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                }

            } else if (targetModule == null) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Target module '" + e.target //$NON-NLS-1$
                            + "' is not found. Could not open '" + e.pn + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                }

            } else {
                controller.addOpens(sourceModule, e.pn, targetModule);
            }
        }

        for (final AddReads r : this.reads) {
            final Module sourceModule = map.computeIfAbsent(r.source,
                    n -> controller.layer().findModule(n).orElse(null));
            Module targetModule = null;
            if (r.target.equals("ALL-UNNAMED")) { //$NON-NLS-1$
                targetModule = getUnnamedModule();
            } else if (r.target.startsWith("BUNDLE")) { //$NON-NLS-1$
                targetModule = getBundleUnnamed(r.target);
            } else {
                targetModule = map.computeIfAbsent(r.target, n -> {
                    return Stream.of(controller.layer(), ModuleLayer.boot()) //
                            .map(l -> l.findModule(n).orElse(null)) //
                            .findFirst().orElse(null);
                });
            }

            if (sourceModule == null) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Source module '" + r.source //$NON-NLS-1$
                            + "' is not dynamically loaded. Could not add read edge."); //$NON-NLS-1$
                }
            } else if (targetModule == null) {
                if (FXClassloaderConfigurator.DEBUG) {
                    System.err.println("JavaModuleLayerModification#applyConfigurations - Target module '" + r.target //$NON-NLS-1$
                            + "' is not found. Could not add read edge."); //$NON-NLS-1$
                }
            } else {
                controller.addReads(sourceModule, targetModule);
            }
        }
    }
}
