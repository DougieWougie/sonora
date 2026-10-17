// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Builds the audio sink with an explicit processor chain so silence skipping and speed changes
 * (Sonic) are always present, and [androidx.media3.exoplayer.ExoPlayer.setSkipSilenceEnabled] works.
 */
@UnstableApi
class SonoraRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        .setAudioProcessorChain(
            DefaultAudioSink.DefaultAudioProcessorChain(
                emptyArray<AudioProcessor>(),
                SilenceSkippingAudioProcessor(),
                SonicAudioProcessor(),
            ),
        )
        .build()
}
