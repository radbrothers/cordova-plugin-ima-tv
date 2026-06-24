package com.example.imaplugin;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.VideoView;

import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cordova plugin exposing Google IMA SDK interstitial ads for Android TV.
 *
 * JS API:
 *   IMAPlugin.initialize(adTagUrl, callbacks)
 *   IMAPlugin.showAd(callbacks)
 *   IMAPlugin.destroy()
 */
public class IMASDKPlugin extends CordovaPlugin {

    private static final String TAG = "IMAPlugin";

    // JS-callable actions
    private static final String ACTION_INITIALIZE = "initialize";
    private static final String ACTION_SHOW_AD    = "showAd";
    private static final String ACTION_DESTROY    = "destroy";

    // IMA objects
    private ImaSdkFactory   sdkFactory;
    private ImaSdkSettings  sdkSettings;
    private AdsLoader       adsLoader;
    private AdsManager      adsManager;

    // Ad player
    private VideoView            adVideoView;
    private VideoAdPlayerAdapter adPlayerAdapter;
    private FrameLayout          adContainer;

    // State
    private String  adTagUrl;
    private boolean isConnectedTvFallbackImageShowing = false;

    // Persistent callback to JS (KeepCallback = true) for event streaming
    private CallbackContext eventCallbackContext;

    // ── Plugin lifecycle ──────────────────────────────────────────────────────

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {

        switch (action) {
            case ACTION_INITIALIZE:
                adTagUrl = args.getString(0);
                initialize(callbackContext);
                return true;

            case ACTION_SHOW_AD:
                showAd(callbackContext);
                return true;

            case ACTION_DESTROY:
                destroyAds();
                callbackContext.success();
                return true;

            default:
                return false;
        }
    }

    // ── initialize ────────────────────────────────────────────────────────────

    private void initialize(CallbackContext callbackContext) {
        eventCallbackContext = callbackContext;

        cordova.getActivity().runOnUiThread(() -> {
            try {
                sdkFactory = ImaSdkFactory.getInstance();
                sdkSettings = sdkFactory.createImaSdkSettings();

                // Disable PPID / set language as needed
                // sdkSettings.setLanguage("ru");

                // Initialize early for faster first load
                sdkFactory.initialize(cordova.getContext(), sdkSettings);

                // Build a transparent FrameLayout overlay that sits on top of content
                adContainer = new FrameLayout(cordova.getActivity());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                cordova.getActivity()
                       .addContentView(adContainer, lp);

                // VideoView for ad playback inside the container
                adVideoView = new VideoView(cordova.getActivity());
                adContainer.addView(adVideoView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                adContainer.setVisibility(android.view.View.GONE);

                // Adapter
                AudioManager audio = (AudioManager)
                        cordova.getActivity().getSystemService(Context.AUDIO_SERVICE);
                adPlayerAdapter = new VideoAdPlayerAdapter(adVideoView, audio);
                adPlayerAdapter.setAdEventCallback(new VideoAdPlayerAdapter.AdEventCallback() {
                    @Override public void onAdStarted()          { sendEvent("adStarted", null); }
                    @Override public void onAdCompleted()        { sendEvent("adCompleted", null); }
                    @Override public void onAdError(String msg)  { sendEvent("adError", msg); }
                    @Override public void onContentResumed()     { sendEvent("contentResumed", null); }
                });

                // AdDisplayContainer — wraps the FrameLayout
                AdDisplayContainer displayContainer =
                        ImaSdkFactory.createAdDisplayContainer(adContainer, adPlayerAdapter);

                // AdsLoader
                adsLoader = sdkFactory.createAdsLoader(
                        cordova.getContext(), sdkSettings, displayContainer);

                setupAdsLoader();

                sendEvent("initialized", null);

            } catch (Exception e) {
                Log.e(TAG, "initialize error", e);
                sendError("initialize failed: " + e.getMessage());
            }
        });
    }

    // ── setupAdsLoader ────────────────────────────────────────────────────────

    private void setupAdsLoader() {
        adsLoader.addAdErrorListener(event -> {
            Log.e(TAG, "AdsLoader error: " + event.getError().getMessage());
            sendError(event.getError().getMessage());
            hideAdContainer();
        });

        adsLoader.addAdsLoadedListener(event -> {
            adsManager = event.getAdsManager();

            adsManager.addAdErrorListener(errEvent -> {
                Log.e(TAG, "AdsManager error: " + errEvent.getError().getMessage());
                sendError(errEvent.getError().getMessage());
                adsManager.discardAdBreak();
                hideAdContainer();
            });

            adsManager.addAdEventListener(adEvent -> {
                AdEvent.AdEventType type = adEvent.getType();
                if (type != AdEvent.AdEventType.AD_PROGRESS) {
                    Log.d(TAG, "AdEvent: " + type);
                }

                switch (type) {
                    case LOADED:
                        // Start ad playback
                        adsManager.start();
                        showAdContainer();
                        break;

                    case STARTED:
                        sendEvent("adStarted", null);
                        break;

                    case CONTENT_PAUSE_REQUESTED:
                        // Interstitial: no content behind — just show container
                        showAdContainer();
                        break;

                    case CONTENT_RESUME_REQUESTED:
                        hideAdContainer();
                        sendEvent("adCompleted", null);
                        break;

                    case ALL_ADS_COMPLETED:
                        destroyAds();
                        sendEvent("allAdsCompleted", null);
                        break;

                    case SKIPPED:
                        sendEvent("adSkipped", null);
                        break;

                    case CLICKED:
                        // On Android TV: SDK handles focus internally
                        break;

                    // ── Android TV: VAST icon fallback ("Why this ad?") ──────
                    case ICON_TAPPED:
                        UiModeManager uiMode = (UiModeManager)
                                cordova.getActivity().getSystemService(Context.UI_MODE_SERVICE);
                        if (uiMode != null &&
                            uiMode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                            isConnectedTvFallbackImageShowing = true;
                        }
                        // Focus the IMA WebView so the D-pad can navigate the dialog
                        adsManager.focus();
                        break;

                    case PAUSED:
                        if (!isConnectedTvFallbackImageShowing) {
                            sendEvent("adPaused", null);
                        }
                        // If fallback image is showing, leave control to the SDK
                        break;

                    case ICON_FALLBACK_IMAGE_CLOSED:
                        // User dismissed "Why this ad?" dialog on TV
                        isConnectedTvFallbackImageShowing = false;
                        adsManager.resume();
                        break;

                    case RESUMED:
                        sendEvent("adResumed", null);
                        break;

                    default:
                        break;
                }
            });

            // Configure rendering: on TV focus skip button automatically
            AdsRenderingSettings renderingSettings =
                    ImaSdkFactory.getInstance().createAdsRenderingSettings();
            renderingSettings.setFocusSkipButtonWhenAvailable(true);
            adsManager.init(renderingSettings);
        });
    }

    // ── showAd ────────────────────────────────────────────────────────────────

    private void showAd(CallbackContext callbackContext) {
        if (adsLoader == null) {
            callbackContext.error("Plugin not initialized. Call initialize() first.");
            return;
        }

        cordova.getActivity().runOnUiThread(() -> {
            try {
                // If a previous manager exists, clean up first
                if (adsManager != null) {
                    adsManager.destroy();
                    adsManager = null;
                }

                AdsRequest request = sdkFactory.createAdsRequest();
                request.setAdTagUrl(adTagUrl);
                // Interstitial: no content video, so progress is always not-ready
                request.setContentProgressProvider(() -> VideoProgressUpdate.VIDEO_TIME_NOT_READY);

                adsLoader.requestAds(request);

                // Acknowledge JS immediately; events will fire via eventCallbackContext
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);

            } catch (Exception e) {
                Log.e(TAG, "showAd error", e);
                callbackContext.error("showAd failed: " + e.getMessage());
            }
        });
    }

    // ── destroy ───────────────────────────────────────────────────────────────

    private void destroyAds() {
        cordova.getActivity().runOnUiThread(() -> {
            if (adsManager != null) {
                adsManager.destroy();
                adsManager = null;
            }
            if (adPlayerAdapter != null) {
                adPlayerAdapter.release();
            }
            hideAdContainer();
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showAdContainer() {
        cordova.getActivity().runOnUiThread(() -> {
            if (adContainer != null) adContainer.setVisibility(android.view.View.VISIBLE);
        });
    }

    private void hideAdContainer() {
        cordova.getActivity().runOnUiThread(() -> {
            if (adContainer != null) adContainer.setVisibility(android.view.View.GONE);
        });
    }

    // ── Event helpers ─────────────────────────────────────────────────────────

    private void sendEvent(String eventName, String data) {
        if (eventCallbackContext == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("event", eventName);
            if (data != null) payload.put("data", data);
            PluginResult result = new PluginResult(PluginResult.Status.OK, payload);
            result.setKeepCallback(true);
            eventCallbackContext.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "sendEvent JSON error", e);
        }
    }

    private void sendError(String message) {
        if (eventCallbackContext == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("event", "error");
            payload.put("data", message);
            PluginResult result = new PluginResult(PluginResult.Status.ERROR, payload);
            result.setKeepCallback(true);
            eventCallbackContext.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "sendError JSON error", e);
        }
    }

    // ── Cordova lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        destroyAds();
        super.onDestroy();
    }

    @Override
    public void onPause(boolean multitasking) {
        if (adsManager != null) adsManager.pause();
        super.onPause(multitasking);
    }

    @Override
    public void onResume(boolean multitasking) {
        if (adsManager != null && !isConnectedTvFallbackImageShowing) {
            adsManager.resume();
        }
        super.onResume(multitasking);
    }
}
