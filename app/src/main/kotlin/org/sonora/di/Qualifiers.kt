// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.di

import javax.inject.Qualifier

/** The dispatcher for blocking work such as Keystore and disk access; tests substitute their own. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
