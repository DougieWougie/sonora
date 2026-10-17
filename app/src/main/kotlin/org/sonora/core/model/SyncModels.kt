// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.model

enum class SyncActionType { NEW, PLAY, DOWNLOAD, DELETE }

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }
