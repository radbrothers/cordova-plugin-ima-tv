/**
 * IMAPlugin — Cordova JavaScript API
 * Google IMA SDK interstitial ads for Android TV
 *
 * Usage:
 *   IMAPlugin.initialize(adTagUrl, onEvent, onError);
 *   IMAPlugin.showAd();
 *   IMAPlugin.destroy();
 *
 * Events fired to onEvent(eventName):
 *   "initialized"        — SDK ready
 *   "adStarted"          — ad playback began
 *   "adCompleted"        — ad finished (also fires after skip)
 *   "adSkipped"          — user skipped the ad
 *   "adPaused"           — ad paused
 *   "adResumed"          — ad resumed after pause
 *   "allAdsCompleted"    — all ads in the pod are done
 *   "error"              — an error occurred (second arg = message)
 */

var exec = require('cordova/exec');

var IMAPlugin = {

    /**
     * Initialize the IMA SDK.
     *
     * @param {string}   adTagUrl  VAST ad tag URL
     * @param {Function} onEvent   Called on every event: function(eventName, data)
     * @param {Function} onError   Called on errors:      function(errorMessage)
     */
    initialize: function (adTagUrl, onEvent, onError) {
        if (!adTagUrl) {
            if (onError) onError('adTagUrl is required');
            return;
        }

        exec(
            function (payload) {
                // payload = { event: "...", data: "..." }
                if (payload && payload.event) {
                    if (payload.event === 'error') {
                        if (onError) onError(payload.data || 'Unknown IMA error');
                    } else {
                        if (onEvent) onEvent(payload.event, payload.data || undefined);
                    }
                }
            },
            function (err) {
                var message = (err && typeof err === 'object')
                    ? (err.message || JSON.stringify(err))
                    : (err || 'Unknown IMA error');
                if (onError) onError(message);
            },
            'IMASDKPlugin',
            'initialize',
            [adTagUrl]
        );
    },

    /**
     * Request and display an interstitial ad.
     * Events are delivered to the callbacks registered in initialize().
     *
     * @param {Function} [onError]  Optional per-call error callback
     */
    showAd: function (onError) {
        exec(
            null,
            function (err) {
                if (onError) onError(err);
            },
            'IMASDKPlugin',
            'showAd',
            []
        );
    },

    /**
     * Release IMA resources. Call when leaving the screen that shows ads.
     */
    destroy: function () {
        exec(null, null, 'IMASDKPlugin', 'destroy', []);
    }
};

module.exports = IMAPlugin;
