package com.example.imaplugin;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.widget.VideoView;

import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Adapts Android VideoView to IMA SDK's VideoAdPlayer interface.
 * Handles ad video playback and progress tracking.
 */
public class VideoAdPlayerAdapter implements VideoAdPlayer {

    private static final String TAG = "IMAPlugin";
    private static final long POLLING_INTERVAL_MS = 250;

    private final VideoView videoView;
    private final AudioManager audioManager;
    private final List<VideoAdPlayerCallback> callbacks = new ArrayList<>();

    private Timer progressTimer;
    private int adDuration = 0;
    private int savedAdPosition = 0;
    private AdMediaInfo loadedAdMediaInfo;

    // Callback to notify the plugin about ad events
    public interface AdEventCallback {
        void onAdStarted();
        void onAdCompleted();
        void onAdError(String message);
        void onContentResumed();
    }

    private AdEventCallback adEventCallback;

    public VideoAdPlayerAdapter(VideoView videoView, AudioManager audioManager) {
        this.videoView = videoView;
        this.audioManager = audioManager;
    }

    public void setAdEventCallback(AdEventCallback callback) {
        this.adEventCallback = callback;
    }

    // ── VideoAdPlayer interface ──────────────────────────────────────────────

    @Override
    public void addCallback(VideoAdPlayerCallback callback) {
        callbacks.add(callback);
    }

    @Override
    public void removeCallback(VideoAdPlayerCallback callback) {
        callbacks.remove(callback);
    }

    @Override
    public void loadAd(AdMediaInfo adMediaInfo, AdPodInfo adPodInfo) {
        // Simple load: store the info. Preloading not required for interstitials.
        loadedAdMediaInfo = adMediaInfo;
    }

    @Override
    public void playAd(AdMediaInfo adMediaInfo) {
        videoView.setVideoURI(Uri.parse(adMediaInfo.getUrl()));
        videoView.setOnPreparedListener(mp -> {
            adDuration = mp.getDuration();
            if (savedAdPosition > 0) {
                mp.seekTo(savedAdPosition);
            }
            mp.start();
            startProgressTracking();
            if (adEventCallback != null) adEventCallback.onAdStarted();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            String msg = "MediaPlayer error: what=" + what + " extra=" + extra;
            Log.e(TAG, msg);
            notifyError(msg);
            return true;
        });
        videoView.setOnCompletionListener(mp -> {
            savedAdPosition = 0;
            notifyAdEnded();
        });
    }

    @Override
    public void pauseAd(AdMediaInfo adMediaInfo) {
        savedAdPosition = videoView.getCurrentPosition();
        videoView.pause();
        stopProgressTracking();
    }

    @Override
    public void stopAd(AdMediaInfo adMediaInfo) {
        stopProgressTracking();
        videoView.stopPlayback();
    }

    @Override
    public void release() {
        stopProgressTracking();
        callbacks.clear();
    }

    @Override
    public int getVolume() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return (max > 0) ? (current * 100 / max) : 0;
    }

    @Override
    public VideoProgressUpdate getAdProgress() {
        if (adDuration <= 0) return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
        return new VideoProgressUpdate(videoView.getCurrentPosition(), adDuration);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void startProgressTracking() {
        if (progressTimer != null) return;
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                VideoProgressUpdate update = getAdProgress();
                for (VideoAdPlayerCallback cb : callbacks) {
                    cb.onAdProgress(loadedAdMediaInfo, update);
                }
            }
        }, POLLING_INTERVAL_MS, POLLING_INTERVAL_MS);
    }

    private void stopProgressTracking() {
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
    }

    private void notifyAdEnded() {
        stopProgressTracking();
        for (VideoAdPlayerCallback cb : callbacks) {
            cb.onEnded(loadedAdMediaInfo);
        }
        if (adEventCallback != null) adEventCallback.onAdCompleted();
    }

    private void notifyError(String message) {
        for (VideoAdPlayerCallback cb : callbacks) {
            cb.onError(loadedAdMediaInfo);
        }
        if (adEventCallback != null) adEventCallback.onAdError(message);
    }

    /** Called by the host plugin when content video finishes. */
    public void notifyContentCompleted() {
        for (VideoAdPlayerCallback cb : callbacks) {
            cb.onContentComplete();
        }
    }
}
