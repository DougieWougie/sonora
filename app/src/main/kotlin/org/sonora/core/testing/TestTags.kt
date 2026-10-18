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
