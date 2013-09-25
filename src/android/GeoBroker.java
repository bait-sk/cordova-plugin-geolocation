/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.core;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import android.os.Handler;

import android.util.Log;

/*
 * This class is the interface to the Geolocation.  It's bound to the geo object.
 *
 * This class only starts and stops various GeoListeners, which consist of a GPS and a Network Listener
 */

public class GeoBroker extends CordovaPlugin {
    private GPSListener gpsListener;
    private NetworkListener networkListener;
    private LocationManager locationManager;  

    private Location bestLocation = new Location("noprovider");
    private Handler handler = new Handler();
    private boolean appIsActive = true;
    private boolean bestWatcher = false;

    private Runnable callLocationUpdateTask = new Runnable() {
        public void run() {
            if (appIsActive){
                networkListener.startBestWatching();
                gpsListener.startBestWatching();
            }
        }
    };

    /**
     * Constructor.
     */
    public GeoBroker() {
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action        The action to execute.
     * @param args      JSONArry of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return          True if the action was valid, or false if not.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d("[Cordova GeoBroker]", "execute: " + action);
        if (this.locationManager == null) {
            this.locationManager = (LocationManager) this.cordova.getActivity().getSystemService(Context.LOCATION_SERVICE);
            this.networkListener = new NetworkListener(this.locationManager, this);
            this.gpsListener = new GPSListener(this.locationManager, this);
        }

        if ( locationManager.isProviderEnabled( LocationManager.GPS_PROVIDER ) ||
                locationManager.isProviderEnabled( LocationManager.NETWORK_PROVIDER )) {

            if (action.equals("getLocation")) {
                boolean enableHighAccuracy = args.getBoolean(0);
                int maximumAge = args.getInt(1);
                Location last = this.locationManager.getLastKnownLocation((enableHighAccuracy ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER));
                // Check if we can use lastKnownLocation to get a quick reading and use less battery
                if (last != null && (System.currentTimeMillis() - last.getTime()) <= maximumAge) {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, this.returnLocationJSON(last));
                    callbackContext.sendPluginResult(result);
                } else {
                    this.getCurrentLocation(callbackContext, enableHighAccuracy, args.optInt(2, 60000));
                }
            }
            else if (action.equals("addWatch")) {
                String id = args.getString(0);
                boolean enableHighAccuracy = args.getBoolean(1);
                this.addWatch(id, callbackContext, enableHighAccuracy);
            }
            else if(action.equals("watchBestPosition")){
                this.bestWatcher = true;
                this.watchBestPosition(callbackContext);
            }
            else if (action.equals("clearWatch")) {
                String id = args.getString(0);
                this.clearWatch(id);
            }
            else if (action.equals("forceStop")) {
                this.forceStop();
            }
            else {
                return false;
            }
        } else {
            PluginResult.Status status = PluginResult.Status.NO_RESULT;
            String message = "Location API is not available for this device.";
            PluginResult result = new PluginResult(status, message);
            callbackContext.sendPluginResult(result);
        }
        return true;
    }

    private void forceStop(){
        this.gpsListener.forceStop();
        this.networkListener.forceStop();
    }

    private void clearWatch(String id) {
        this.gpsListener.clearWatch(id);
        this.networkListener.clearWatch(id);
    }

    private void watchBestPosition(CallbackContext callbackContext) {
        Log.d("[Cordova GeoBroker]", "watchBestPosition()");
        this.networkListener.startBestWatching(callbackContext);
        this.gpsListener.startBestWatching(callbackContext);
    }

    private void getCurrentLocation(CallbackContext callbackContext, boolean enableHighAccuracy, int timeout) {
        if (enableHighAccuracy) {
            this.gpsListener.addCallback(callbackContext, timeout);
        } else {
            this.networkListener.addCallback(callbackContext, timeout);
        }
    }

    private void addWatch(String timerId, CallbackContext callbackContext, boolean enableHighAccuracy) {
        if (enableHighAccuracy) {
            this.gpsListener.addWatch(timerId, callbackContext);
        } else {
            this.networkListener.addWatch(timerId, callbackContext);
        }
    }

    /**
     * Called when the activity is to be shut down.
     * Stop listener.
     */
    public void onDestroy() {
        if (this.networkListener != null) {
            this.networkListener.destroy();
            this.networkListener = null;
        }
        if (this.gpsListener != null) {
            this.gpsListener.destroy();
            this.gpsListener = null;
        }
    }

    /**
     * Called when the view navigates.
     * Stop the listeners.
     */
    public void onReset() {
        this.onDestroy();
    }

    public JSONObject returnLocationJSON(Location loc) {
        JSONObject o = new JSONObject();

        try {
            o.put("latitude", loc.getLatitude());
            o.put("longitude", loc.getLongitude());
            o.put("altitude", (loc.hasAltitude() ? loc.getAltitude() : null));
            o.put("accuracy", loc.getAccuracy());
            o.put("heading", (loc.hasBearing() ? (loc.hasSpeed() ? loc.getBearing() : null) : null));
            o.put("velocity", loc.getSpeed());
            o.put("timestamp", loc.getTime());
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return o;
    }

    public void isBestLocation(Location location, CallbackContext callbackContext){
        Log.d("[Cordova GeoBroker]", "isBestLocation - checking best location");
        Location bestLocation = this.bestLocation;
        if (bestLocation == null) {
            // A new location is always better than no location
            this.hasBestLocation(location, callbackContext, "isBestLocation - no best location");
            return;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - bestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > 60000;
        boolean isSignificantlyOlder = timeDelta < -60000;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) {
            this.hasBestLocation(location, callbackContext, "isBestLocation - best location is too old");
            return;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - bestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;


        boolean isFromSameProvider = false;
        if (location.getProvider() == bestLocation.getProvider()) {
            isFromSameProvider = true;
        }

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            this.hasBestLocation(location, callbackContext, "isBestLocation - is accurate enough");
            return;
        } else if (isNewer && !isLessAccurate) {
            this.hasBestLocation(location, callbackContext, "isBestLocation - is newer and more or same accurate");
            return;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            this.hasBestLocation(location, callbackContext, "isBestLocation - is newer and not significantly less acurate and from same provider");
            return;
        }

        Log.d("[Cordova GeoBroker]", "isBestLocation - new location not good enough");
    }

    public void hasBestLocation(Location location, CallbackContext callbackContext, String log){
        this.networkListener.stopBestWatching();
        this.gpsListener.stopBestWatching();

        this.bestLocation = location;
        Log.d("[Cordova GeoBroker]", log);
        this.win(location, callbackContext, true);

        this.handler.postDelayed(callLocationUpdateTask, 30000);
    }

    public void win(Location loc, CallbackContext callbackContext, boolean keepCallback) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, this.returnLocationJSON(loc));
        result.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(result);
    }

    /**
     * Location failed.  Send error back to JavaScript.
     * 
     * @param code          The error code
     * @param msg           The error message
     * @throws JSONException 
     */
    public void fail(int code, String msg, CallbackContext callbackContext, boolean keepCallback) {
        JSONObject obj = new JSONObject();
        String backup = null;
        try {
            obj.put("code", code);
            obj.put("message", msg);
        } catch (JSONException e) {
            obj = null;
            backup = "{'code':" + code + ",'message':'" + msg.replaceAll("'", "\'") + "'}";
        }
        PluginResult result;
        if (obj != null) {
            result = new PluginResult(PluginResult.Status.ERROR, obj);
        } else {
            result = new PluginResult(PluginResult.Status.ERROR, backup);
        }

        result.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(result);
    }

    public boolean isGlobalListener(CordovaLocationListener listener)
    {
        if (gpsListener != null && networkListener != null)
        {
            return gpsListener.equals(listener) || networkListener.equals(listener);
        }
        else
            return false;
    }

    @Override
    public void onPause(boolean multitasking) {
        Log.d("[Cordova GeoBroker]", "onPause");
        this.appIsActive = false;
        if (this.bestWatcher) {
            this.networkListener.stopBestWatching();
            this.gpsListener.stopBestWatching();
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        Log.d("[Cordova GeoBroker]", "onResume");
        this.appIsActive = true;
        if (this.bestWatcher) {
            this.networkListener.startBestWatching();
            this.gpsListener.startBestWatching();
        }
    }

}
