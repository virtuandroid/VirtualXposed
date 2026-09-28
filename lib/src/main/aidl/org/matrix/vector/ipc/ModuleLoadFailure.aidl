/*
 * Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.matrix.vector.ipc;

/**
 * A module the user switched on that the framework could not load, and why.
 *
 * <p>The gap between the two notions of a module the daemon holds: the configuration, which is what
 * the user asked for, and the realisation - the resolved APK and parsed dex it hands to a forking
 * process. The difference used to be thrown away, so such a module simply appeared to be off,
 * having switched itself off for reasons nobody could see.</p>
 *
 * <p>Only failures are described, and absence from the list is the answer for every other module:
 * it loaded, or it is switched off and there was nothing to load.</p>
 */
parcelable ModuleLoadFailure {
    /** The module app's package name, which is the module's identity everywhere. */
    String packageName;

    /**
     * One of {@code IManagerService.MODULE_LOAD_NO_APK} and the values beside it.
     *
     * <p>Never 0. 0 is what a reader would get out of an untouched reply parcel, so leaving it
     * unclaimed keeps "the daemon did not answer" from arriving as a diagnosis. A reader that does
     * not recognise a value must say the module could not be loaded rather than name the nearest
     * reason it does know - naming one is what the pair this replaced forced its caller into, and
     * it named the wrong one.</p>
     */
    int reason;
}
