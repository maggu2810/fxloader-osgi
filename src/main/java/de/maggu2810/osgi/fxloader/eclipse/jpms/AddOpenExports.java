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

import java.util.Optional;

@SuppressWarnings("javadoc")
public class AddOpenExports {
    public final String source;
    public final String pn;
    public final String target;

    public AddOpenExports(final String source, final String pn, final String target) {
        this.source = source;
        this.pn = pn;
        this.target = target;
    }

    /**
     * Export/open definition
     *
     * @param value $source-module/$package=$target-module
     * @return the export/open definition
     */
    public static Optional<AddOpenExports> valueOf(final String value) {
        final int modulePackageSplit = value.indexOf('/');
        final int targetSplit = value.indexOf('=');
        if (modulePackageSplit > 0 && targetSplit > modulePackageSplit) {
            return Optional.of(new AddOpenExports(value.substring(0, modulePackageSplit),
                    value.substring(modulePackageSplit + 1, targetSplit), value.substring(targetSplit + 1)));
        }
        return Optional.empty();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.pn == null ? 0 : this.pn.hashCode());
        result = prime * result + (this.source == null ? 0 : this.source.hashCode());
        result = prime * result + (this.target == null ? 0 : this.target.hashCode());
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final AddOpenExports other = (AddOpenExports) obj;
        if (this.pn == null) {
            if (other.pn != null) {
                return false;
            }
        } else if (!this.pn.equals(other.pn)) {
            return false;
        }
        if (this.source == null) {
            if (other.source != null) {
                return false;
            }
        } else if (!this.source.equals(other.source)) {
            return false;
        }
        if (this.target == null) {
            if (other.target != null) {
                return false;
            }
        } else if (!this.target.equals(other.target)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("%s/%s=%s", this.source, this.pn, this.target); //$NON-NLS-1$
    }
}