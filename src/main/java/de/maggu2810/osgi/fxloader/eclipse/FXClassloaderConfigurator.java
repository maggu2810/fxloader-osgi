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

import org.eclipse.osgi.internal.hookregistry.HookConfigurator;
import org.eclipse.osgi.internal.hookregistry.HookRegistry;

/**
 * Hook configurator for classloading.
 */
public class FXClassloaderConfigurator implements HookConfigurator {

    /**
     * Debug switch
     */
    public static final boolean DEBUG = Boolean.getBoolean("fxloader.osgi.eclipse.hook.debug"); //$NON-NLS-1$

    private static boolean isGreaterOrEqual11() {
        final String version = System.getProperty("java.version", ""); //$NON-NLS-1$//$NON-NLS-2$
        final String[] parts = version.split("\\D"); //$NON-NLS-1$
        boolean rv = false;
        try {
            rv = Integer.parseInt(parts[0]) >= 11;
        } catch (final Throwable e) {
            // TODO: handle exception
        }
        return Boolean.valueOf(rv);
    }

    @Override
    public void addHooks(final HookRegistry hookRegistry) {
        if (!isGreaterOrEqual11()) {
            if (DEBUG) {
                System.err.println("The FXClassloader hook requires Java >= 11");
            }
            return;
        }

        hookRegistry.addClassLoaderHook(new FXClassLoader());
    }

}
