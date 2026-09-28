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
 * One Android user or profile on this device, reduced to what the manager displays.
 *
 * <p>Named to say which side of the boundary it is on. It was called {@code UserInfo}, which is
 * also the name of the platform's hidden {@code android.content.pm.UserInfo} that the daemon
 * converts <i>from</i> - the conversion has both types in scope at once, told apart by nothing but
 * an import line, and the manager had to write this one out fully qualified wherever it appeared.
 * </p>
 *
 * <p>Two fields, deliberately. The platform type also carries flags, a creation time, a profile
 * group and an icon path, none of which the manager reads and all of which would then have to be
 * kept in step with whatever the platform does to them next.</p>
 */
parcelable DeviceUser {
    /**
     * The user id, as everything about scope and package visibility is keyed on.
     *
     * <p>0 is the device owner. Not contiguous and not bounded by the number of users: profiles get
     * their own ids, and a device that has had one removed leaves a gap.</p>
     */
    int id;

    /**
     * What the platform calls this user, shown as-is.
     *
     * <p>Whatever the user or the manufacturer named it, so it is display text and nothing may be
     * parsed out of it. Neither unique nor stable - two profiles may carry one name, and a user can
     * be renamed. {@link #id} is the identity.</p>
     */
    String name;
}
