// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import android.util.Log;

import com.danikula.videocache.HttpProxyCacheServer;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.Player.Listener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.Util;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class VideoPlayer {
    private static final String FORMAT_SS = "ss";
    private static final String FORMAT_DASH = "dash";
    private static final String FORMAT_HLS = "hls";
    private static final String FORMAT_OTHER = "other";

    private ExoPlayer exoPlayer;

    private Surface surface;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;

    private QueuingEventSink eventSink;

    private final EventChannel eventChannel;

    private static final String USER_AGENT = "User-Agent";

    @VisibleForTesting
    boolean isInitialized = false;

    private final VideoPlayerOptions options;

    private DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();

    VideoPlayer(
            Context context,
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            String dataSource,
            String formatHint,
            @NonNull Map<String, String> httpHeaders,
            @Nullable Map<String, Object> bufferOptions,
            boolean enableCaching,
            @Nullable String cacheKey,
            @Nullable String cacheDirectory,
            @Nullable Long maxSingleFileCacheSize,
            @Nullable Long maxTotalCacheSize,
            VideoPlayerOptions options) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;

        final ExoPlayer exoPlayer = buildExoPlayer(context, bufferOptions);
        buildHttpDataSourceFactory(httpHeaders);
        DataSource.Factory dataSourceFactory;

        Uri uri = Uri.parse(dataSource);

        final boolean shouldUseProxyCaching = cacheDirectory != null;

        if (enableCaching && isHTTP(uri)) {
            if (shouldUseProxyCaching) {
                final HttpProxyCacheServer proxy = ProxyFactory.getProxy(context, cacheDirectory, httpHeaders,
                        cacheKey, maxTotalCacheSize);
                final String proxyUrl = proxy.getProxyUrl(dataSource);
                uri = Uri.parse(proxyUrl);
                dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
            } else {
                CacheDataSourceFactory cacheDataSourceFactory = new CacheDataSourceFactory(
                        context,
                        maxTotalCacheSize,
                        maxSingleFileCacheSize, cacheKey);
                if (!httpHeaders.isEmpty()) {
                    cacheDataSourceFactory.setHeaders(httpHeaders);
                }
                dataSourceFactory = cacheDataSourceFactory;
            }

        } else {
            dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
        }

        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint);

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        setUpVideoPlayer(exoPlayer, new QueuingEventSink());
    }

    ExoPlayer buildExoPlayer(Context context, @Nullable Map<String, Object> bufferOptions) {
        if (bufferOptions == null || bufferOptions.isEmpty()) {
            return new ExoPlayer.Builder(context).build();
        } else {

            final Number minBufferMSMap = (Number) bufferOptions.get("minBufferMS");
            final Long minBufferMS = minBufferMSMap != null ? minBufferMSMap.longValue() : DefaultLoadControl.DEFAULT_MIN_BUFFER_MS;

            final Number maxBufferMSMap = (Number) bufferOptions.get("maxBufferMS");
            final Long maxBufferMS = maxBufferMSMap != null ? maxBufferMSMap.longValue() : DefaultLoadControl.DEFAULT_MAX_BUFFER_MS;

            final Number bufferForPlaybackMSMap = (Number) bufferOptions.get("bufferForPlaybackMS");
            final Long bufferForPlaybackMS = bufferForPlaybackMSMap != null ? bufferForPlaybackMSMap.longValue()
                    : DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS;

            final Number bufferForPlaybackAfterRebufferMSMap = (Number) bufferOptions
                    .get("bufferForPlaybackAfterRebufferMS");
            final Long bufferForPlaybackAfterRebufferMS = bufferForPlaybackAfterRebufferMSMap != null
                    ? bufferForPlaybackAfterRebufferMSMap.longValue()
                    : DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS;

            final Boolean prioritizeTimeOverSizeThresholdsMap = (Boolean) bufferOptions
                    .get("prioritizeTimeOverSizeThresholds");
            final boolean prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholdsMap != null
                    ? prioritizeTimeOverSizeThresholdsMap
                    : DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS;

            final Number backwardBufferDurationMSMap = (Number) bufferOptions.get("backwardBufferDurationMS");
            final Long backwardBufferDurationMS = backwardBufferDurationMSMap != null ? backwardBufferDurationMSMap.longValue()
                    : DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS;

            final Boolean retainBackwardBufferFromKeyframeMap = (Boolean) bufferOptions
                    .get("retainBackwardBufferFromKeyframe");
            final boolean retainBackwardBufferFromKeyframe = retainBackwardBufferFromKeyframeMap != null
                    ? retainBackwardBufferFromKeyframeMap
                    : DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME;

            final Number targetBuffer = (Number) bufferOptions.get("targetBuffer");
            final Number bufferSegmentSize = (Number) bufferOptions.get("bufferSegmentSize");
            final Boolean trimOnReset = (Boolean) bufferOptions.get("trimOnReset");
            final DefaultAllocator alloc = new DefaultAllocator(trimOnReset != null ? trimOnReset : true, bufferSegmentSize != null ? bufferSegmentSize.intValue() : C.DEFAULT_BUFFER_SEGMENT_SIZE);
            if (targetBuffer != null) {
                alloc.setTargetBufferSize(targetBuffer.intValue());
            }
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            minBufferMS.intValue(),
                            maxBufferMS.intValue(),
                            bufferForPlaybackMS.intValue(),
                            bufferForPlaybackAfterRebufferMS.intValue())
                    .setTargetBufferBytes(targetBuffer != null ? targetBuffer.intValue()
                            : DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
                    .setAllocator(alloc)
                    .setBackBuffer(backwardBufferDurationMS.intValue(), retainBackwardBufferFromKeyframe)
                    .setPrioritizeTimeOverSizeThresholds(prioritizeTimeOverSizeThresholds)
                    .build();
            return new ExoPlayer.Builder(context).setLoadControl(loadControl).build();
        }

    }

    // Constructor used to directly test members of this class.
    @VisibleForTesting
    VideoPlayer(
            ExoPlayer exoPlayer,
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            VideoPlayerOptions options,
            QueuingEventSink eventSink,
            DefaultHttpDataSource.Factory httpDataSourceFactory) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;
        this.httpDataSourceFactory = httpDataSourceFactory;

        setUpVideoPlayer(exoPlayer, eventSink);
    }

    @VisibleForTesting
    public void buildHttpDataSourceFactory(@NonNull Map<String, String> httpHeaders) {
        final boolean httpHeadersNotEmpty = !httpHeaders.isEmpty();
        final String userAgent = httpHeadersNotEmpty && httpHeaders.containsKey(USER_AGENT)
                ? httpHeaders.get(USER_AGENT)
                : "ExoPlayer";

        httpDataSourceFactory.setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);

        if (httpHeadersNotEmpty) {
            httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
        }
    }

    private static boolean isHTTP(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return scheme.equals("http") || scheme.equals("https");
    }

    private MediaSource buildMediaSource(
            Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint) {
        int type;
        if (formatHint == null) {
            type = Util.inferContentType(uri);
        } else {
            switch (formatHint) {
                case FORMAT_SS:
                    type = C.CONTENT_TYPE_SS;
                    break;
                case FORMAT_DASH:
                    type = C.CONTENT_TYPE_DASH;
                    break;
                case FORMAT_HLS:
                    type = C.CONTENT_TYPE_HLS;
                    break;
                case FORMAT_OTHER:
                    type = C.CONTENT_TYPE_OTHER;
                    break;
                default:
                    type = -1;
                    break;
            }
        }
        switch (type) {
            case C.CONTENT_TYPE_SS:
                return new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private void setUpVideoPlayer(ExoPlayer exoPlayer, QueuingEventSink eventSink) {
        this.exoPlayer = exoPlayer;
        this.eventSink = eventSink;

        eventChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink sink) {
                        eventSink.setDelegate(sink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        eventSink.setDelegate(null);
                    }
                });

        surface = new Surface(textureEntry.surfaceTexture());
        exoPlayer.setVideoSurface(surface);
        setAudioAttributes(exoPlayer, options.mixWithOthers);

        exoPlayer.addListener(
                new Listener() {
                    private boolean isBuffering = false;

                    public void setBuffering(boolean buffering) {
                        if (isBuffering != buffering) {
                            isBuffering = buffering;
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", isBuffering ? "bufferingStart" : "bufferingEnd");
                            eventSink.success(event);
                        }
                    }

                    @Override
                    public void onPlaybackStateChanged(final int playbackState) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            setBuffering(true);
                            sendBufferingUpdate();
                        } else if (playbackState == Player.STATE_READY) {
                            if (!isInitialized) {
                                isInitialized = true;
                                sendInitialized();
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "completed");
                            eventSink.success(event);
                        }

                        if (playbackState != Player.STATE_BUFFERING) {
                            setBuffering(false);
                        }
                    }

                    @Override
                    public void onPlayerError(@NonNull final PlaybackException error) {
                        setBuffering(false);
                        if (eventSink != null) {
                            eventSink.error("VideoError", "Video player had error " + error, null);
                        }
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (eventSink != null) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "isPlayingStateUpdate");
                            event.put("isPlaying", isPlaying);
                            eventSink.success(event);
                        }
                    }
                });
    }

    void sendBufferingUpdate() {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
        // iOS supports a list of buffered ranges, so here is a list with a single
        // range.
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
    }

    private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
        exoPlayer.setAudioAttributes(
                new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !isMixMode);
    }

    void play() {
        exoPlayer.setPlayWhenReady(true);
    }

    void pause() {
        exoPlayer.setPlayWhenReady(false);
    }

    void setLooping(boolean value) {
        exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
        float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
        exoPlayer.setVolume(bracketedValue);
    }

    void setPlaybackSpeed(double value) {
        // We do not need to consider pitch and skipSilence for now as we do not handle
        // them and
        // therefore never diverge from the default values.
        final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

        exoPlayer.setPlaybackParameters(playbackParameters);
    }

    void seekTo(int location) {
        exoPlayer.seekTo(location);
    }

    long getPosition() {
        return exoPlayer.getCurrentPosition();
    }

    @SuppressWarnings("SuspiciousNameCombination")
    @VisibleForTesting
    void sendInitialized() {
        if (isInitialized) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "initialized");
            event.put("duration", exoPlayer.getDuration());

            if (exoPlayer.getVideoFormat() != null) {
                Format videoFormat = exoPlayer.getVideoFormat();
                int width = videoFormat.width;
                int height = videoFormat.height;
                int rotationDegrees = videoFormat.rotationDegrees;
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer.getVideoFormat().height;
                    height = exoPlayer.getVideoFormat().width;
                }
                event.put("width", width);
                event.put("height", height);

                // Rotating the video with ExoPlayer does not seem to be possible with a
                // Surface,
                // so inform the Flutter code that the widget needs to be rotated to prevent
                // upside-down playback for videos with rotationDegrees of 180 (other
                // orientations work
                // correctly without correction).
                if (rotationDegrees == 180) {
                    event.put("rotationCorrection", rotationDegrees);
                }
            }

            eventSink.success(event);
        }
    }

    void dispose() {
        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        if (surface != null) {
            surface.release();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
