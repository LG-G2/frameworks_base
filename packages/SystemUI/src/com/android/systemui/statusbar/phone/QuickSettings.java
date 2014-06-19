/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013-2014 ParanoidAndroid Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.animation.ValueAnimator;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Profile;
import android.provider.Settings;
import android.security.KeyChain;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.app.MediaRouteDialogPresenter;
import com.android.internal.util.paranoid.LightbulbConstants;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.BatteryCircleMeterView;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsModel.ActivityState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.BluetoothState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.RSSIState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.State;
import com.android.systemui.statusbar.phone.QuickSettingsModel.UserState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.WifiState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
class QuickSettings {
    static final boolean DEBUG_GONE_TILES = false;
    private static final String TAG = "QuickSettings";
    public static final boolean SHOW_IME_TILE = false;
    private static final String AUTO_START = "AUTO_START";
    private static final String TOGGLE_FLASHLIGHT = "TOGGLE_FLASHLIGHT";
    
    public enum Tile {
        USER,
        BRIGHTNESS,
        SETTINGS,
        WIFI,
        RSSI,
        ROTATION,
        BATTERY,
        AIRPLANE,
        BLUETOOTH,
        LOCATION,
        IMMERSIVE,
        MOBILENETWORK,
        LIGHTBULB,
        SYNC,
        SLEEP,
        POWERMENU,
        VOLUME,
        SOUND,
        QUIETHOURS,
        NFC
    }
    
    public static final String NO_TILES = "NO_TILES";
    public static final String DELIMITER = ";";
    public static final String DEFAULT_TILES = Tile.USER + DELIMITER + Tile.BRIGHTNESS
    + DELIMITER + Tile.SETTINGS + DELIMITER + Tile.WIFI + DELIMITER + Tile.RSSI
    + DELIMITER + Tile.ROTATION + DELIMITER + Tile.BATTERY + DELIMITER + Tile.BLUETOOTH
    + DELIMITER + Tile.LOCATION + DELIMITER + Tile.MOBILENETWORK
    + DELIMITER + Tile.LIGHTBULB + DELIMITER + Tile.SYNC;
    
    private Context mContext;
    private PanelBar mBar;
    private QuickSettingsModel mModel;
    private ViewGroup mContainerView;
    
    private DevicePolicyManager mDevicePolicyManager;
    private PhoneStatusBar mStatusBarService;
    private BluetoothState mBluetoothState;
    private BluetoothAdapter mBluetoothAdapter;
    private WifiManager mWifiManager;
    private ConnectivityManager mConnectivityManager;
    
    private BluetoothController mBluetoothController;
    private RotationLockController mRotationLockController;
    private LocationController mLocationController;
    
    private AsyncTask<Void, Void, Pair<String, Drawable>> mUserInfoTask;
    private AsyncTask<Void, Void, Pair<Boolean, Boolean>> mQueryCertTask;
    
    boolean mTilesSetUp = false;
    boolean mUseDefaultAvatar = false;
    boolean mEditModeEnabled = false;
    
    private QuickSettingsTileView mBatteryTile;
    private BatteryMeterView mBattery;
    private BatteryCircleMeterView mCircleBattery;
    private boolean mBatteryHasPercent;
    
    private Handler mHandler;
    
    public QuickSettings(Context context, QuickSettingsContainerView container) {
        mDevicePolicyManager
        = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mContext = context;
        mContainerView = container;
        mModel = new QuickSettingsModel(context);
        mBluetoothState = new QuickSettingsModel.BluetoothState();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        mHandler = new Handler();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        
        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(ContactsContract.Intents.ACTION_PROFILE_CHANGED);
        profileFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mContext.registerReceiverAsUser(mProfileReceiver, UserHandle.ALL, profileFilter,
                                        null, null);
    }
    
    void setBar(PanelBar bar) {
        mBar = bar;
    }
    
    public void setService(PhoneStatusBar phoneStatusBar) {
        mStatusBarService = phoneStatusBar;
    }
    
    public PhoneStatusBar getService() {
        return mStatusBarService;
    }
    
    public void setImeWindowStatus(boolean visible) {
        mModel.onImeWindowStatusChanged(visible);
    }
    
    void setup(NetworkController networkController, BluetoothController bluetoothController,
               BatteryController batteryController, LocationController locationController,
               RotationLockController rotationLockController) {
        mBluetoothController = bluetoothController;
        mRotationLockController = rotationLockController;
        mLocationController = locationController;
        
        setupQuickSettings();
        updateResources();
        applyLocationEnabledStatus();
        
        networkController.addNetworkSignalChangedCallback(mModel);
        bluetoothController.addStateChangedCallback(mModel);
        batteryController.addStateChangedCallback(mModel);
        locationController.addSettingsChangedCallback(mModel);
        rotationLockController.addRotationLockControllerCallback(mModel);
    }
    
    private void queryForSslCaCerts() {
        mQueryCertTask = new AsyncTask<Void, Void, Pair<Boolean, Boolean>>() {
            @Override
            protected Pair<Boolean, Boolean> doInBackground(Void... params) {
                boolean hasCert = DevicePolicyManager.hasAnyCaCertsInstalled();
                boolean isManaged = mDevicePolicyManager.getDeviceOwner() != null;
                
                return Pair.create(hasCert, isManaged);
            }
            @Override
            protected void onPostExecute(Pair<Boolean, Boolean> result) {
                super.onPostExecute(result);
                boolean hasCert = result.first;
                boolean isManaged = result.second;
                mModel.setSslCaCertWarningTileInfo(hasCert, isManaged);
            }
        };
        mQueryCertTask.execute();
    }
    
    private void queryForUserInformation() {
        Context currentUserContext = null;
        UserInfo userInfo = null;
        try {
            userInfo = ActivityManagerNative.getDefault().getCurrentUser();
            currentUserContext = mContext.createPackageContextAsUser("android", 0,
                                                                     new UserHandle(userInfo.id));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't create user context", e);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get user info", e);
        }
        final int userId = userInfo.id;
        final String userName = userInfo.name;
        
        final Context context = currentUserContext;
        mUserInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
                final UserManager um = UserManager.get(mContext);
                
                // Fall back to the UserManager nickname if we can't read the name from the local
                // profile below.
                String name = userName;
                Drawable avatar = null;
                Bitmap rawAvatar = um.getUserIcon(userId);
                if (rawAvatar != null) {
                    avatar = new BitmapDrawable(mContext.getResources(), rawAvatar);
                } else {
                    avatar = mContext.getResources().getDrawable(R.drawable.ic_qs_default_user);
                    mUseDefaultAvatar = true;
                }
                
                // If it's a single-user device, get the profile name, since the nickname is not
                // usually valid
                if (um.getUsers().size() <= 1) {
                    // Try and read the display name from the local profile
                    final Cursor cursor = context.getContentResolver().query(
                                                                             Profile.CONTENT_URI, new String[] {Phone._ID, Phone.DISPLAY_NAME},
                                                                             null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(cursor.getColumnIndex(Phone.DISPLAY_NAME));
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
                return new Pair<String, Drawable>(name, avatar);
            }
            
            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                super.onPostExecute(result);
                mModel.setUserTileInfo(result.first, result.second);
                mUserInfoTask = null;
            }
        };
        mUserInfoTask.execute();
    }
    
    private void setupQuickSettings() {
        // Setup the tiles that we are going to be showing (including the temporary ones)
        LayoutInflater inflater = LayoutInflater.from(mContext);
        
        addTiles(mContainerView, inflater, false);
        addTemporaryTiles(mContainerView, inflater);
        
        queryForUserInformation();
        queryForSslCaCerts();
        mTilesSetUp = true;
    }
    
    private void startSettingsActivity(String action) {
        Intent intent = new Intent(action);
        startSettingsActivity(intent);
    }
    
    private void startSettingsActivity(Intent intent) {
        startSettingsActivity(intent, true);
    }
    
    private void collapsePanels() {
        getService().animateCollapsePanels();
    }
    
    private void startSettingsActivity(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !getService().isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        collapsePanels();
    }
    
    public void updateBattery() {
        if (mBattery == null || mCircleBattery == null || mModel == null) {
            return;
        }
        int batteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.STATUS_BAR_BATTERY, 0, UserHandle.USER_CURRENT);
        mBatteryHasPercent = batteryStyle == BatteryMeterView.BATTERY_STYLE_ICON_PERCENT
            || batteryStyle == BatteryMeterView.BATTERY_STYLE_PERCENT
            || batteryStyle == BatteryMeterView.BATTERY_STYLE_CIRCLE_PERCENT
            || batteryStyle == BatteryMeterView.BATTERY_STYLE_DOTTED_CIRCLE_PERCENT;
        mCircleBattery.updateSettings();
        mBattery.updateSettings();
        mModel.refreshBatteryTile();
    }
    
    private boolean immersiveEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
                                      Settings.System.IMMERSIVE_MODE, 0) == 1;
    }
    
    private boolean isNfcSupported() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
    }
    
    private void addTiles(ViewGroup parent, LayoutInflater inflater, boolean addMissing) {
        // Load all the customizable tiles. If not yet modified by the user, load default ones.
        // After enabled tiles are loaded, proceed to load missing tiles and set them to View.GONE.
        // If all the tiles were deleted, they are still loaded, but their visibility is changed
        String tileContainer = Settings.System.getString(mContext.getContentResolver(),
                                                         Settings.System.QUICK_SETTINGS_TILES);
        if(tileContainer == null) tileContainer = DEFAULT_TILES;
        Tile[] allTiles = Tile.values();
        String[] storedTiles = tileContainer.split(DELIMITER);
        List<String> allTilesArray = enumToStringArray(allTiles);
        List<String> storedTilesArray = Arrays.asList(storedTiles);
        
        for(String tile : addMissing ? allTilesArray : storedTilesArray) {
            boolean addTile = storedTilesArray.contains(tile);
            if(addMissing) addTile = !addTile;
            if(addTile) {
                if(Tile.USER.toString().equals(tile.toString())) { // User
                    QuickSettingsTileView userTile = (QuickSettingsTileView)
                    inflater.inflate(R.layout.quick_settings_tile, parent, false);
                    userTile.setContent(R.layout.quick_settings_tile_user, inflater);
                    userTile.setTileId(Tile.USER);
                    userTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            final UserManager um = UserManager.get(mContext);
                            if (um.getUsers(true).size() > 1) {
                                try {
                                    WindowManagerGlobal.getWindowManagerService().lockNow(null);
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Couldn't show user switcher", e);
                                }
                            } else {
                                Intent intent = ContactsContract.QuickContact
                                .composeQuickContactsIntent(mContext, v,
                                                            ContactsContract.Profile.CONTENT_URI,
                                                            ContactsContract.QuickContact.MODE_LARGE, null);
                                mContext.startActivityAsUser(intent,
                                                             new UserHandle(UserHandle.USER_CURRENT));
                            }
                        }
                    });
                    
                    userTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            startSettingsActivity(android.provider.Settings.ACTION_SYNC_SETTINGS);
                            return true;
                        }
                    });
                    
                    mModel.addUserTile(userTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView view, State state) {
                            UserState us = (UserState) state;
                            ImageView iv = (ImageView) view.findViewById(R.id.user_imageview);
                            TextView tv = (TextView) view.findViewById(R.id.user_textview);
                            tv.setText(state.label);
                            iv.setImageDrawable(us.avatar);
                            view.setContentDescription(mContext.getString(
                                                                          R.string.accessibility_quick_settings_user, state.label));
                        }
                    });
                    parent.addView(userTile);
                    if(addMissing) userTile.setVisibility(View.GONE);
                } else if(Tile.BRIGHTNESS.toString().equals(tile.toString())) { // Brightness
                    final QuickSettingsBasicTile brightnessTile
                    = new QuickSettingsBasicTile(mContext);
                    brightnessTile.setTileId(Tile.BRIGHTNESS);
                    brightnessTile.setImageResource(R.drawable.ic_qs_brightness_auto_off);
                    brightnessTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            showBrightnessDialog();
                        }
                    });
                    mModel.addBrightnessTile(brightnessTile,
                                             new QuickSettingsModel.BasicRefreshCallback(brightnessTile));
                    parent.addView(brightnessTile);
                    if(addMissing) brightnessTile.setVisibility(View.GONE);
                } else if(Tile.SETTINGS.toString().equals(tile.toString())) { // Settings tile
                    final QuickSettingsBasicTile settingsTile
                    = new QuickSettingsBasicTile(mContext);
                    settingsTile.setTileId(Tile.SETTINGS);
                    settingsTile.setImageResource(R.drawable.ic_qs_settings);
                    settingsTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startSettingsActivity(android.provider.Settings.ACTION_SETTINGS);
                        }
                    });
                    mModel.addSettingsTile(settingsTile,
                                           new QuickSettingsModel.BasicRefreshCallback(settingsTile));
                    parent.addView(settingsTile);
                    if(addMissing) settingsTile.setVisibility(View.GONE);
                } else if(Tile.WIFI.toString().equals(tile.toString())) {
                    // Wi-fi
                    final QuickSettingsTileView wifiTile = (QuickSettingsTileView)
                    inflater.inflate(R.layout.quick_settings_tile, parent, false);
                    wifiTile.setTileId(Tile.WIFI);
                    wifiTile.setContent(R.layout.quick_settings_tile_wifi, inflater);
                    wifiTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            final boolean enable =
                            (mWifiManager.getWifiState() !=
                             WifiManager.WIFI_STATE_ENABLED);
                            new AsyncTask<Void, Void, Void>() {
                                @Override
                                protected Void doInBackground(Void... args) {
                                    // Disable tethering if enabling Wifi
                                    final int wifiApState = mWifiManager.getWifiApState();
                                    if (enable &&
                                        ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                                         (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
                                            mWifiManager.setWifiApEnabled(null, false);
                                        }
                                    
                                    mWifiManager.setWifiEnabled(enable);
                                    return null;
                                }
                            }.execute();
                            wifiTile.setPressed(false);
                        }
                    });
                    
                    wifiTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            startSettingsActivity(android.provider.Settings.ACTION_WIFI_SETTINGS);
                            return true;
                        }
                    });
                    
                    mModel.addWifiTile(wifiTile, new NetworkActivityCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView view, State state) {
                            WifiState wifiState = (WifiState) state;
                            ImageView iv = (ImageView) view.findViewById(R.id.image);
                            iv.setImageResource(wifiState.iconId);
                            setActivity(view, wifiState);
                            TextView tv = (TextView) view.findViewById(R.id.text);
                            tv.setText(wifiState.label);
                            wifiTile.setContentDescription(mContext.getString(
                                                                              R.string.accessibility_quick_settings_wifi,
                                                                              wifiState.signalContentDescription,
                                                                              (wifiState.connected) ? wifiState.label : ""));
                        }
                    });
                    parent.addView(wifiTile);
                    if(addMissing) wifiTile.setVisibility(View.GONE);
                } else if(Tile.RSSI.toString().equals(tile.toString())) { // RSSI
                    if (mModel.deviceHasMobileData()) {
                        QuickSettingsTileView rssiTile = (QuickSettingsTileView)
                        inflater.inflate(R.layout.quick_settings_tile, parent, false);
                        rssiTile.setTileId(Tile.RSSI);
                        rssiTile.setContent(R.layout.quick_settings_tile_rssi, inflater);
                        rssiTile.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                // TODO: RSSI toggle
                                boolean currentState = mConnectivityManager.getMobileDataEnabled();
                                mConnectivityManager.setMobileDataEnabled(!currentState);
                            }
                        });
                        rssiTile.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                Intent intent = new Intent();
                                intent.setComponent(new ComponentName("com.android.settings",
                                                                      "com.android.settings.Settings$DataUsageSummaryActivity"));
                                startSettingsActivity(intent);
                                return true;
                            }
                        });
                        mModel.addRSSITile(rssiTile, new NetworkActivityCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView view, State state) {
                                RSSIState rssiState = (RSSIState) state;
                                ImageView iv = (ImageView) view.findViewById(R.id.rssi_image);
                                ImageView iov = (ImageView)
                                view.findViewById(R.id.rssi_overlay_image);
                                TextView tv = (TextView) view.findViewById(R.id.rssi_textview);
                                // Force refresh
                                iv.setImageDrawable(null);
                                iv.setImageResource(rssiState.signalIconId);
                                
                                if (rssiState.dataTypeIconId > 0) {
                                    iov.setImageResource(rssiState.dataTypeIconId);
                                } else if (!mModel.isMobileDataEnabled(mContext)) {
                                    iov.setImageResource(R.drawable.ic_qs_signal_data_off);
                                } else {
                                    iov.setImageDrawable(null);
                                }
                                setActivity(view, rssiState);
                                
                                tv.setText(state.label);
                                view.setContentDescription(mContext.getResources().getString(
                                                                                             R.string.accessibility_quick_settings_mobile,
                                                                                             rssiState.signalContentDescription,
                                                                                             rssiState.dataContentDescription,
                                                                                             state.label));
                            }
                        });
                        parent.addView(rssiTile);
                        if(addMissing) rssiTile.setVisibility(View.GONE);
                    }
                } else if(Tile.ROTATION.toString().equals(tile.toString())) { // Rotation Lock
                    if (mContext.getResources()
                        .getBoolean(R.bool.quick_settings_show_rotation_lock)
                        || DEBUG_GONE_TILES) {
                        final QuickSettingsBasicTile rotationLockTile
                        = new QuickSettingsBasicTile(mContext);
                        rotationLockTile.setTileId(Tile.ROTATION);
                        rotationLockTile.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                final boolean locked = mRotationLockController.isRotationLocked();
                                mRotationLockController.setRotationLocked(!locked);
                            }
                        });
                        
                        rotationLockTile.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                startSettingsActivity(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                                return true;
                            }
                        });
                        
                        mModel.addRotationLockTile(rotationLockTile, mRotationLockController,
                                                   new QuickSettingsModel.RefreshCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView view,
                                                    State state) {
                                QuickSettingsModel.RotationLockState rotationLockState =
                                (QuickSettingsModel.RotationLockState) state;
                                // can't show/hide view, so just enable/disable
                                view.setEnabled(rotationLockState.visible);
                                if (state.iconId != 0) {
                                    // needed to flush any cached IDs
                                    rotationLockTile.setImageDrawable(null);
                                    rotationLockTile.setImageResource(state.iconId);
                                }
                                if (state.label != null) {
                                    rotationLockTile.setText(state.label);
                                }
                            }
                        });
                        parent.addView(rotationLockTile);
                        if(addMissing) rotationLockTile.setVisibility(View.GONE);
                    }
                } else if(Tile.BATTERY.toString().equals(tile.toString())) { // Battery
                    mBatteryTile = (QuickSettingsTileView)
                    inflater.inflate(R.layout.quick_settings_tile, parent, false);
                    mBatteryTile.setTileId(Tile.BATTERY);
                    mBatteryTile.setContent(R.layout.quick_settings_tile_battery, inflater);
                    mBattery = (BatteryMeterView) mBatteryTile.findViewById(R.id.image);
                    mBattery.setVisibility(View.GONE);
                    mCircleBattery = (BatteryCircleMeterView) mBatteryTile.findViewById(R.id.circle_battery);
                    updateBattery();
                    mBatteryTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startSettingsActivity(Intent.ACTION_POWER_USAGE_SUMMARY);
                        }
                    });
                    mModel.addBatteryTile(mBatteryTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            QuickSettingsModel.BatteryState batteryState =
                            (QuickSettingsModel.BatteryState) state;
                            String t;
                            if (batteryState.batteryLevel == 100) {
                                t = mContext.getString(
                                                       R.string.quick_settings_battery_charged_label);
                            } else {
                                if (!mBatteryHasPercent) {
                                    t = batteryState.pluggedIn
                                        ? mContext.getString(R.string.quick_settings_battery_charging_label,
                                                batteryState.batteryLevel)
                                        : mContext.getString(R.string.status_bar_settings_battery_meter_format,
                                                batteryState.batteryLevel);
                                } else {
                                    t = batteryState.pluggedIn
                                        ? mContext.getString(R.string.quick_settings_battery_charging)
                                        : mContext.getString(R.string.quick_settings_battery_discharging);
                                }
                            }
                            ((TextView)mBatteryTile.findViewById(R.id.text)).setText(t);
                            mBatteryTile.setContentDescription(
                                                              mContext.getString(
                                                                                 R.string.accessibility_quick_settings_battery, t));
                        }
                    });
                    parent.addView(mBatteryTile);
                    if(addMissing) mBatteryTile.setVisibility(View.GONE);
                } else if(Tile.AIRPLANE.toString().equals(tile.toString())) { // Airplane Mode
                    final QuickSettingsBasicTile airplaneTile
                    = new QuickSettingsBasicTile(mContext);
                    airplaneTile.setTileId(Tile.AIRPLANE);
                    mModel.addAirplaneModeTile(airplaneTile,
                                               new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            airplaneTile.setImageResource(state.iconId);
                            
                            String airplaneState = mContext.getString(
                                                                      (state.enabled) ? R.string.accessibility_desc_on
                                                                      : R.string.accessibility_desc_off);
                            airplaneTile.setContentDescription(
                                                               mContext.getString(
                                                                                  R.string.accessibility_quick_settings_airplane,
                                                                                  airplaneState));
                            airplaneTile.setText(state.label);
                        }
                    });
                    parent.addView(airplaneTile);
                    if(addMissing) airplaneTile.setVisibility(View.GONE);
                } else if (Tile.SYNC.toString().equals(tile.toString())) { // Sync tile
                    final QuickSettingsBasicTile SyncTile
                    = new QuickSettingsBasicTile(mContext);
                    SyncTile.setTileId(Tile.SYNC);
                    SyncTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            Intent intent = new Intent("android.settings.SYNC_SETTINGS");
                            intent.addCategory(Intent.CATEGORY_DEFAULT);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startSettingsActivity(intent);
                            return true;
                        }
                    });
                    mModel.addSyncModeTile(SyncTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            SyncTile.setImageResource(state.iconId);
                            SyncTile.setText(state.label);
                        }
                    });
                    parent.addView(SyncTile);
                    if (addMissing) SyncTile.setVisibility(View.GONE);
                } else if(Tile.BLUETOOTH.toString().equals(tile.toString())) { // Bluetooth
                    if (mModel.deviceSupportsBluetooth()
                        || DEBUG_GONE_TILES) {
                        final QuickSettingsBasicTile bluetoothTile
                        = new QuickSettingsBasicTile(mContext);
                        bluetoothTile.setTileId(Tile.BLUETOOTH);
                        bluetoothTile.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mBluetoothAdapter.isEnabled()) {
                                    mBluetoothAdapter.disable();
                                } else {
                                    mBluetoothAdapter.enable();
                                }
                                bluetoothTile.setPressed(false);
                            }
                        });
                        
                        bluetoothTile.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                startSettingsActivity(
                                                      android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                                return true;
                            }
                        });
                        
                        mModel.addBluetoothTile(bluetoothTile,
                                                new QuickSettingsModel.RefreshCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView unused, State state) {
                                BluetoothState bluetoothState = (BluetoothState) state;
                                bluetoothTile.setImageResource(state.iconId);
                                bluetoothTile.setContentDescription(mContext.getString(
                                                                                       R.string.accessibility_quick_settings_bluetooth,
                                                                                       bluetoothState.stateContentDescription));
                                bluetoothTile.setText(state.label);
                            }
                        });
                        parent.addView(bluetoothTile);
                        if(addMissing) bluetoothTile.setVisibility(View.GONE);
                    }
                } else if(Tile.LOCATION.toString().equals(tile.toString())) { // Location
                    final QuickSettingsBasicTile locationTile
                    = new QuickSettingsBasicTile(mContext);
                    locationTile.setTileId(Tile.LOCATION);
                    locationTile.setImageResource(R.drawable.ic_qs_location_on);
                    locationTile.setTextResource(R.string.quick_settings_location_label);
                    locationTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean newLocationEnabledState
                            = !mLocationController.isLocationEnabled();
                            if (mLocationController.setLocationEnabled(newLocationEnabledState)
                                && newLocationEnabledState) {
                                // If we've successfully switched from location off to on, close
                                // the notifications tray to show the network location provider
                                // consent dialog.
                                Intent closeDialog
                                = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                                mContext.sendBroadcast(closeDialog);
                            }
                        }
                    });
                    
                    locationTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            startSettingsActivity(
                                                  android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            return true; // Consume click
                        }
                    });
                    
                    mModel.addLocationTile(locationTile,
                                           new QuickSettingsModel.BasicRefreshCallback(locationTile));
                    parent.addView(locationTile);
                    if(addMissing) locationTile.setVisibility(View.GONE);
                } else if(Tile.IMMERSIVE.toString().equals(tile.toString())) { // Immersive mode
                    final QuickSettingsBasicTile immersiveTile
                    = new QuickSettingsBasicTile(mContext);
                    final boolean immersiveModeOn = immersiveEnabled();
                    immersiveTile.setTileId(Tile.IMMERSIVE);
                    immersiveTile.setImageResource(immersiveModeOn
                                                   ? R.drawable.ic_qs_immersive_on
                                                   : R.drawable.ic_qs_immersive_off);
                    immersiveTile.setTextResource(immersiveModeOn
                                                  ? R.string.quick_settings_immersive_mode_label
                                                  : R.string.quick_settings_immersive_mode_off_label);
                    immersiveTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            boolean immersiveOn = immersiveEnabled();
                            immersiveTile.setImageResource(immersiveOn
                                                           ? R.drawable.ic_qs_immersive_off :
                                                           R.drawable.ic_qs_immersive_on);
                            immersiveTile.setTextResource(immersiveOn
                                                          ? R.string.quick_settings_immersive_mode_off_label :
                                                          R.string.quick_settings_immersive_mode_label);
                            Settings.System.putInt(mContext.getContentResolver(),
                                                   Settings.System.IMMERSIVE_MODE, immersiveOn ? 0 : 1);
                        }
                    });
                    parent.addView(immersiveTile);
                    if(addMissing) immersiveTile.setVisibility(View.GONE);
                } else if (Tile.MOBILENETWORK.toString().equals(tile.toString())) { // MobileNetwork
                    if (mModel.deviceHasMobileData()) {
                        final QuickSettingsBasicTile mobileNetworkTile
                        = new QuickSettingsBasicTile(mContext);
                        mobileNetworkTile.setTileId(Tile.MOBILENETWORK);
                        mobileNetworkTile.setImageResource(R.drawable.ic_qs_unexpected_network);
                        mobileNetworkTile.setTextResource(R.string.quick_settings_network_unknown);
                        mobileNetworkTile.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                mModel.toggleMobileNetworkState();
                            }
                        });
                        
                        mobileNetworkTile.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                Intent intent = new Intent(Intent.ACTION_MAIN);
                                intent.setClassName("com.android.phone", "com.android.phone.Settings");
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startSettingsActivity(intent);
                                return true;
                            }
                        });
                        
                        mModel.addMobileNetworkTile(mobileNetworkTile, new QuickSettingsModel.RefreshCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView unused, State mobileNetworkState) {
                                mobileNetworkTile.setImageResource(mobileNetworkState.iconId);
                                mobileNetworkTile.setText(mobileNetworkState.label);
                            }
                        });
                        
                        parent.addView(mobileNetworkTile);
                        if(addMissing) mobileNetworkTile.setVisibility(View.GONE);
                        mModel.refreshMobileNetworkTile();
                    }
                } else if(Tile.LIGHTBULB.toString().equals(tile.toString())) { // Lightbulb
                    final QuickSettingsBasicTile lightbulbTile
                    = new QuickSettingsBasicTile(mContext);
                    lightbulbTile.setTileId(Tile.LIGHTBULB);
                    lightbulbTile.setImageResource(R.drawable.ic_qs_lightbulb_on);
                    lightbulbTile.setTextResource(R.string.quick_settings_lightbulb_label);
                    lightbulbTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (!mModel.mLightbulbActive && !mModel.deviceHasCameraFlash()) {
                                collapsePanels();
                                startSettingsActivity(LightbulbConstants.INTENT_LAUNCH_APP);
                            } else if (mModel.mLightbulbActive) {
                                collapsePanels();
                            }
                            Intent intent = new Intent(TOGGLE_FLASHLIGHT);
                            intent.putExtra(AUTO_START, true);
                            mContext.sendBroadcast(intent);
                        }
                    });
                    
                    lightbulbTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            startSettingsActivity(LightbulbConstants.INTENT_LAUNCH_APP);
                            return true;
                        }
                    });
                    
                    mModel.addLightbulbTile(lightbulbTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            lightbulbTile.setImageResource(state.iconId);
                            lightbulbTile.setText(state.label);
                        }
                    });
                    
                    parent.addView(lightbulbTile);
                    if(addMissing) lightbulbTile.setVisibility(View.GONE);
                } else if(Tile.POWERMENU.toString().equals(tile.toString())) { // Sleep
                    
                    final QuickSettingsBasicTile powerMenuTile
                    = new QuickSettingsBasicTile(mContext);
                    powerMenuTile.setTileId(Tile.POWERMENU);
                    powerMenuTile.setImageResource(R.drawable.ic_qs_powermenu);
                    powerMenuTile.setTextResource(R.string.quick_settings_powermenu_label);
                    powerMenuTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            Intent intent = new Intent(Intent.ACTION_POWERMENU);
                            mContext.sendBroadcast(intent);
                        }
                    });
                    
                    powerMenuTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            Intent intent = new Intent(Intent.ACTION_POWERMENU);
                            mContext.sendBroadcast(intent);
                            return true;
                        }
                    });
                    parent.addView(powerMenuTile);
                    if(addMissing) powerMenuTile.setVisibility(View.GONE);
                } else if(Tile.VOLUME.toString().equals(tile.toString())) { // Volume tile
                    final QuickSettingsBasicTile volumeTile
                    = new QuickSettingsBasicTile(mContext);
                    volumeTile.setTileId(Tile.VOLUME);
                    volumeTile.setImageResource(R.drawable.ic_qs_volume);
                    volumeTile.setTextResource(R.string.quick_settings_volume_label);
                    volumeTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                            am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                        }
                    });
                    
                    volumeTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
                            return true;
                        }
                    });
                    parent.addView(volumeTile);
                    if(addMissing) volumeTile.setVisibility(View.GONE);
                } else if(Tile.SOUND.toString().equals(tile.toString())) { // Sound tile
                    final QuickSettingsBasicTile soundTile
                    = new QuickSettingsBasicTile(mContext);
                    soundTile.setTileId(Tile.SOUND);
                    soundTile.setImageResource(R.drawable.ic_qs_ringer_normal);
                    soundTile.setTextResource(R.string.quick_settings_ringer_mode_normal_label);
                    soundTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mModel.switchRingerMode();
                            mModel.refreshRingerModeTile();
                        }
                    });
                    
                    soundTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
                            return true;
                        }
                    });
                    
                    mModel.addRingerModeTile(soundTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            soundTile.setImageResource(state.iconId);
                            soundTile.setText(state.label);
                        }
                    });
                    parent.addView(soundTile);
                    if(addMissing) soundTile.setVisibility(View.GONE);
                } else if (Tile.QUIETHOURS.toString().equals(tile.toString())) { // Quiet hours tile
                    final QuickSettingsBasicTile quietHourTile
                    = new QuickSettingsBasicTile(mContext);
                    quietHourTile.setTileId(Tile.QUIETHOURS);
                    quietHourTile.setImageResource(R.drawable.ic_qs_quiet_hours_off);
                    quietHourTile.setTextResource(R.string.quick_settings_quiethours_off_label);
                    quietHourTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean checkModeOn = Settings.System.getInt(mContext
                                                                         .getContentResolver(), Settings.System.QUIET_HOURS_ENABLED, 0) == 1;
                            Settings.System.putInt(mContext.getContentResolver(),
                                                   Settings.System.QUIET_HOURS_ENABLED, checkModeOn ? 0 : 1);
                            Intent scheduleSms = new Intent();
                            scheduleSms.setAction("com.android.settings.paranoid.service.SCHEDULE_SERVICE_COMMAND");
                            mContext.sendBroadcast(scheduleSms);
                        }
                    });
                    quietHourTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            Intent intent = new Intent(Intent.ACTION_MAIN);
                            intent.setClassName("com.android.settings",
                                                "com.android.settings.Settings$QuietHoursSettingsActivity");
                            startSettingsActivity(intent);
                            return true;
                        }
                    });
                    mModel.addQuietHourTile(quietHourTile,
                                            new QuickSettingsModel.BasicRefreshCallback(quietHourTile));
                    parent.addView(quietHourTile);
                    if (addMissing) quietHourTile.setVisibility(View.GONE);
                } else if(Tile.SLEEP.toString().equals(tile.toString())) { // Sleep
                    final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                    
                    final QuickSettingsBasicTile sleepTile
                    = new QuickSettingsBasicTile(mContext);
                    sleepTile.setTileId(Tile.SLEEP);
                    sleepTile.setImageResource(R.drawable.ic_qs_sleep);
                    sleepTile.setTextResource(R.string.quick_settings_sleep_label);
                    sleepTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pm.goToSleep(SystemClock.uptimeMillis());
                        }
                    });
                    
                    sleepTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                            return true;
                        }
                    });
                    parent.addView(sleepTile);
                    if(addMissing) sleepTile.setVisibility(View.GONE);
                } else if(Tile.NFC.toString().equals(tile.toString()) && isNfcSupported()) {
                    final QuickSettingsBasicTile nfcTile = new QuickSettingsBasicTile(mContext);
                    nfcTile.setTileId(Tile.NFC);
                    NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
                    boolean nfcEnabled = nfcAdapter != null ? nfcAdapter.isEnabled() : false;
                    nfcTile.setImageResource(nfcEnabled ?
                                             R.drawable.ic_qs_nfc_on :
                                             R.drawable.ic_qs_nfc_off);
                    nfcTile.setTextResource(nfcEnabled ?
                                            R.string.quick_settings_nfc_label :
                                            R.string.quick_settings_nfc_off_label);
                    nfcTile.setOnClickListener(new View.OnClickListener() {
                        
                        @Override
                        public void onClick(View v) {
                            //We have to use that deprecated function here,
                            //because getDefaultAdapter(mContext) always returns null with the context we get on boot
                            NfcAdapter adapter = NfcAdapter.getDefaultAdapter();
                            if (adapter != null) {
                                boolean enabled = adapter.isEnabled();
                                if (enabled) {
                                    adapter.disable();
                                } else {
                                    adapter.enable();
                                }
                                enabled = !enabled;
                                nfcTile.setImageResource(enabled ?
                                                         R.drawable.ic_qs_nfc_on :
                                                         R.drawable.ic_qs_nfc_off);
                                nfcTile.setTextResource(enabled ?
                                                        R.string.quick_settings_nfc_label :
                                                        R.string.quick_settings_nfc_off_label);
                            }
                        }
                    });
                    mModel.addNfcTile(nfcTile, new QuickSettingsModel.BasicRefreshCallback(nfcTile));
                    parent.addView(nfcTile);
                    if(addMissing) nfcTile.setVisibility(View.GONE);
                }
            }
        }
        if(!addMissing) addTiles(parent, inflater, true);
    }
    
    private void addTemporaryTiles(final ViewGroup parent, final LayoutInflater inflater) {
        // Alarm tile
        final QuickSettingsBasicTile alarmTile
        = new QuickSettingsBasicTile(mContext);
        alarmTile.setTemporary(true);
        alarmTile.setImageResource(R.drawable.ic_qs_alarm_on);
        alarmTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        mModel.addAlarmTile(alarmTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State alarmState) {
                alarmTile.setText(alarmState.label);
                alarmTile.setVisibility(alarmState.enabled ? View.VISIBLE : View.GONE);
                alarmTile.setContentDescription(mContext.getString(
                                                                   R.string.accessibility_quick_settings_alarm, alarmState.label));
            }
        });
        parent.addView(alarmTile);
        
        // Usb Mode
        final QuickSettingsBasicTile usbModeTile
        = new QuickSettingsBasicTile(mContext);
        usbModeTile.setTemporary(true);
        usbModeTile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mConnectivityManager.getTetherableWifiRegexs().length != 0) {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(
                                                          "com.android.settings",
                                                          "com.android.settings.Settings$TetherSettingsActivity"));
                    startSettingsActivity(intent);
                }
                return true;
            }
        });
        
        mModel.addUsbModeTile(usbModeTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State usbState) {
                usbModeTile.setImageResource(usbState.iconId);
                usbModeTile.setText(usbState.label);
                usbModeTile.setVisibility(usbState.enabled ? View.VISIBLE : View.GONE);
            }
        });
        
        parent.addView(usbModeTile);
        
        // Remote Display
        QuickSettingsBasicTile remoteDisplayTile
        = new QuickSettingsBasicTile(mContext);
        remoteDisplayTile.setTemporary(true);
        remoteDisplayTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                
                final Dialog[] dialog = new Dialog[1];
                dialog[0] = MediaRouteDialogPresenter.createDialog(mContext,
                                                                   MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                                                                   new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog[0].dismiss();
                        startSettingsActivity(
                                              android.provider.Settings.ACTION_WIFI_DISPLAY_SETTINGS);
                    }
                });
                dialog[0].getWindow().setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
                dialog[0].show();
            }
        });
        mModel.addRemoteDisplayTile(remoteDisplayTile,
                                    new QuickSettingsModel.BasicRefreshCallback(remoteDisplayTile)
                                    .setShowWhenEnabled(true));
        parent.addView(remoteDisplayTile);
        
        if (SHOW_IME_TILE || DEBUG_GONE_TILES) {
            // IME
            final QuickSettingsBasicTile imeTile
            = new QuickSettingsBasicTile(mContext);
            imeTile.setTemporary(true);
            imeTile.setImageResource(R.drawable.ic_qs_ime);
            imeTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        collapsePanels();
                        Intent intent = new Intent(Settings.ACTION_SHOW_INPUT_METHOD_PICKER);
                        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                        pendingIntent.send();
                    } catch (Exception e) {}
                }
            });
            mModel.addImeTile(imeTile,
                              new QuickSettingsModel.BasicRefreshCallback(imeTile)
                              .setShowWhenEnabled(true));
            parent.addView(imeTile);
        }
        
        // Bug reports
        final QuickSettingsBasicTile bugreportTile
        = new QuickSettingsBasicTile(mContext);
        bugreportTile.setTemporary(true);
        bugreportTile.setImageResource(com.android.internal.R.drawable.stat_sys_adb);
        bugreportTile.setTextResource(com.android.internal.R.string.bugreport_title);
        bugreportTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                showBugreportDialog();
            }
        });
        mModel.addBugreportTile(bugreportTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                view.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(bugreportTile);
        /*
         QuickSettingsTileView mediaTile = (QuickSettingsTileView)
         inflater.inflate(R.layout.quick_settings_tile, parent, false);
         mediaTile.setContent(R.layout.quick_settings_tile_media, inflater);
         parent.addView(mediaTile);
         QuickSettingsTileView imeTile = (QuickSettingsTileView)
         inflater.inflate(R.layout.quick_settings_tile, parent, false);
         imeTile.setContent(R.layout.quick_settings_tile_ime, inflater);
         imeTile.setOnClickListener(new View.OnClickListener() {
         @Override
         public void onClick(View v) {
         parent.removeViewAt(0);
         }
         });
         parent.addView(imeTile);
         */
        
        // SSL CA Cert Warning.
        final QuickSettingsBasicTile sslCaCertWarningTile =
        new QuickSettingsBasicTile(mContext, null, R.layout.quick_settings_tile_monitoring);
        sslCaCertWarningTile.setTemporary(true);
        sslCaCertWarningTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                startSettingsActivity(Settings.ACTION_MONITORING_CERT_INFO);
            }
        });
        
        sslCaCertWarningTile.setImageResource(
                                              com.android.internal.R.drawable.indicator_input_error);
        sslCaCertWarningTile.setTextResource(R.string.ssl_ca_cert_warning);
        
        mModel.addSslCaCertWarningTile(sslCaCertWarningTile,
                                       new QuickSettingsModel.BasicRefreshCallback(sslCaCertWarningTile)
                                       .setShowWhenEnabled(true));
        parent.addView(sslCaCertWarningTile);
    }
    
    List<String> enumToStringArray(Tile[] enumData) {
        List<String> array = new ArrayList<String>();
        for(Tile tile : enumData) {
            array.add(tile.toString());
        }
        return array;
    }
    
    void updateResources() {
        Resources r = mContext.getResources();
        
        // Update the model
        mModel.refreshBatteryTile();
        
        QuickSettingsContainerView container = ((QuickSettingsContainerView)mContainerView);
        
        container.updateSpan();
        container.updateResources();
        mContainerView.requestLayout();
    }
    
    private void showBrightnessDialog() {
        Intent intent = new Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG);
        mContext.sendBroadcast(intent);
    }
    
    private void showBugreportDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setPositiveButton(com.android.internal.R.string.report, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Add a little delay before executing, to give the
                    // dialog a chance to go away before it takes a
                    // screenshot.
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                ActivityManagerNative.getDefault()
                                .requestBugReport();
                            } catch (RemoteException e) {
                            }
                        }
                    }, 500);
                }
            }
        });
        builder.setMessage(com.android.internal.R.string.bugreport_message);
        builder.setTitle(com.android.internal.R.string.bugreport_title);
        builder.setCancelable(true);
        final Dialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
        } catch (RemoteException e) {
        }
        dialog.show();
    }
    
    private void applyBluetoothStatus() {
        mModel.onBluetoothStateChange(mBluetoothState);
    }
    
    private void applyLocationEnabledStatus() {
        mModel.onLocationSettingsChanged(mLocationController.isLocationEnabled());
    }
    
    void reloadUserInfo() {
        if (mUserInfoTask != null) {
            mUserInfoTask.cancel(false);
            mUserInfoTask = null;
        }
        if (mTilesSetUp) {
            queryForUserInformation();
            queryForSslCaCerts();
        }
    }
    
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
    final String action = intent.getAction();
    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                       BluetoothAdapter.ERROR);
        mBluetoothState.enabled = (state == BluetoothAdapter.STATE_ON);
        applyBluetoothStatus();
    } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
        int status = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                                        BluetoothAdapter.STATE_DISCONNECTED);
        mBluetoothState.connected = (status == BluetoothAdapter.STATE_CONNECTED);
        applyBluetoothStatus();
    } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
        reloadUserInfo();
    } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
        if (mUseDefaultAvatar) {
            queryForUserInformation();
        }
    } else if (KeyChain.ACTION_STORAGE_CHANGED.equals(action)) {
        queryForSslCaCerts();
    }
}
};

private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
@Override
public void onReceive(Context context, Intent intent) {
final String action = intent.getAction();
if (ContactsContract.Intents.ACTION_PROFILE_CHANGED.equals(action) ||
Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
try {
final int currentUser = ActivityManagerNative.getDefault().getCurrentUser().id;
final int changedUser =
intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
if (changedUser == currentUser) {
reloadUserInfo();
}
} catch (RemoteException e) {
Log.e(TAG, "Couldn't get current user id for profile change", e);
}
}

}
};

private abstract static class NetworkActivityCallback
implements QuickSettingsModel.RefreshCallback {
    private final long mDefaultDuration = new ValueAnimator().getDuration();
    private final long mShortDuration = mDefaultDuration / 3;
    
    public void setActivity(View view, ActivityState state) {
        setVisibility(view.findViewById(R.id.activity_in), state.activityIn);
        setVisibility(view.findViewById(R.id.activity_out), state.activityOut);
    }
    
    private void setVisibility(View view, boolean visible) {
        final float newAlpha = visible ? 1 : 0;
        if (view.getAlpha() != newAlpha) {
            view.animate()
            .setDuration(visible ? mShortDuration : mDefaultDuration)
            .alpha(newAlpha)
            .start();
        }
    }
}
}