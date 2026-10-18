// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.testing

/** Semantic handles for UI tests, so they keep working when the visible strings change or are translated. */
object TestTags {
    // Player
    const val MINI_PLAYER_BAR = "mini_player_bar"
    const val FULL_PLAYER_SHEET = "full_player_sheet"
    const val PLAY_PAUSE_BUTTON = "play_pause_button"
    const val SKIP_FORWARD_BUTTON = "skip_forward_button"
    const val SKIP_BACK_BUTTON = "skip_back_button"
    const val SEEK_SLIDER = "seek_slider"
    const val SPEED_CONTROL_BUTTON = "speed_control_button"
    const val SKIP_SILENCE_CHIP = "skip_silence_chip"
    const val SLEEP_TIMER_BUTTON = "sleep_timer_button"
    const val PLAYER_TOOLBAR = "player_toolbar"
    const val ELAPSED_TIME = "elapsed_time"
    const val REMAINING_TIME = "remaining_time"
    const val SHOW_NOTES = "show_notes"
    fun speedPreset(speed: Float) = "speed_preset_$speed"

    // Downloads
    const val DOWNLOADS_USAGE_SUMMARY = "downloads_usage_summary"
    const val DOWNLOADS_EVICTION_BANNER = "downloads_eviction_banner"
    const val DOWNLOADS_EVICTION_DISMISS = "downloads_eviction_dismiss"
    const val DOWNLOADS_LINK = "downloads_link"
    const val DOWNLOADS_LINK_BADGE = "downloads_link_badge"
    const val DOWNLOADS_BACK = "downloads_back"
    const val DOWNLOADS_EMPTY = "downloads_empty"
    const val DOWNLOADS_DELETE_PLAYED_BUTTON = "downloads_delete_played_button"
    const val DOWNLOADS_CONFIRM_DELETE_PLAYED = "downloads_confirm_delete_played"
    const val DOWNLOADS_DISMISS_DELETE_PLAYED = "downloads_dismiss_delete_played"
    fun downloadRow(id: Long) = "download_row_$id"
    fun downloadCancel(id: Long) = "download_cancel_$id"
    fun downloadRetry(id: Long) = "download_retry_$id"
    fun downloadDelete(id: Long) = "download_delete_$id"
    fun downloadSort(name: String) = "download_sort_$name"

    // Podcast detail
    const val PODCAST_DESCRIPTION = "podcast_description"

    // Nextcloud sync
    const val NEXTCLOUD_SERVER_INPUT = "nextcloud_server_input"
    const val NEXTCLOUD_CONNECT_BUTTON = "nextcloud_connect_button"
    const val NEXTCLOUD_POLLING_INDICATOR = "nextcloud_polling_indicator"
    const val NEXTCLOUD_CANCEL_POLLING_BUTTON = "nextcloud_cancel_polling_button"
    const val NEXTCLOUD_CONNECTED_BADGE = "nextcloud_connected_badge"
    const val NEXTCLOUD_SYNC_NOW_BUTTON = "nextcloud_sync_now_button"
    const val NEXTCLOUD_ERROR_BANNER = "nextcloud_error_banner"
    const val NEXTCLOUD_RETRY_BUTTON = "nextcloud_retry_button"
}
