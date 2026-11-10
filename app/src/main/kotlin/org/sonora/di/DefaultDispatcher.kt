// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.di

import javax.inject.Qualifier

/** The dispatcher for CPU-bound work such as filtering and text processing; tests substitute their own. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
