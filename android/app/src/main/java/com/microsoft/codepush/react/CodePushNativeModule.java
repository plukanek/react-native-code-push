package com.microsoft.codepush.react;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactRootView;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.ChoreographerCompat;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileFilter;
import java.util.jar.JarFile;

public class CodePushNativeModule extends ReactContextBaseJavaModule {
    private String mBinaryContentsHash = null;
    private String mClientUniqueId = null;
    private LifecycleEventListener mLifecycleEventListener = null;
    private int mMinimumBackgroundDuration = 0;

    private CodePush mCodePush;
    private SettingsManager mSettingsManager;
    private CodePushTelemetryManager mTelemetryManager;
    private CodePushUpdateManager mUpdateManager;

    public CodePushNativeModule(ReactApplicationContext reactContext, CodePush codePush, CodePushUpdateManager codePushUpdateManager, CodePushTelemetryManager codePushTelemetryManager, SettingsManager settingsManager) {
        super(reactContext);

        mCodePush = codePush;
        mSettingsManager = settingsManager;
        mTelemetryManager = codePushTelemetryManager;
        mUpdateManager = codePushUpdateManager;

        // Initialize module state while we have a reference to the current context.
        mBinaryContentsHash = CodePushUpdateUtils.getHashForBinaryContents(reactContext, mCodePush.isDebugMode());
        mClientUniqueId = Settings.Secure.getString(reactContext.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();

        constants.put("codePushInstallModeImmediate", CodePushInstallMode.IMMEDIATE.getValue());
        constants.put("codePushInstallModeOnNextRestart", CodePushInstallMode.ON_NEXT_RESTART.getValue());
        constants.put("codePushInstallModeOnNextResume", CodePushInstallMode.ON_NEXT_RESUME.getValue());
        constants.put("codePushInstallModeOnNextSuspend", CodePushInstallMode.ON_NEXT_SUSPEND.getValue());

        constants.put("codePushUpdateStateRunning", CodePushUpdateState.RUNNING.getValue());
        constants.put("codePushUpdateStatePending", CodePushUpdateState.PENDING.getValue());
        constants.put("codePushUpdateStateLatest", CodePushUpdateState.LATEST.getValue());

        return constants;
    }

    @Override
    public String getName() {
        return "CodePush";
    }

    private void loadBundleLegacy() {
        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            // The currentActivity can be null if it is backgrounded / destroyed, so we simply
            // no-op to prevent any null pointer exceptions.
            return;
        }
        mCodePush.invalidateCurrentInstance();

        currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentActivity.recreate();
            }
        });
    }

    // Use reflection to find and set the appropriate fields on ReactInstanceManager. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactInstanceManager instanceManager, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = instanceManager.getClass().getDeclaredField("mBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(instanceManager, latestJSBundleLoader);
        } catch (Exception e) {
            CodePushUtils.log("Unable to set JSBundle - CodePush may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    private void loadBundle() {
        clearLifecycleEventListener();
        mCodePush.clearDebugCacheIfNeeded();
        try {
            // #1) Get the ReactInstanceManager instance, which is what includes the
            //     logic to reload the current React context.
            final ReactInstanceManager instanceManager = resolveInstanceManager();
            if (instanceManager == null) {
                return;
            }

            String latestJSBundleFile = mCodePush.getJSBundleFileInternal(mCodePush.getAssetsBundleFileName());

            // #2) Update the locally stored JS bundle file path
            setJSBundle(instanceManager, latestJSBundleFile);

            // #3) Get the context creation method and fire it on the UI thread (which RN enforces)
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // We don't need to resetReactRootViews anymore 
                        // due the issue https://github.com/facebook/react-native/issues/14533
                        // has been fixed in RN 0.46.0
                        //resetReactRootViews(instanceManager);

                        instanceManager.recreateReactContextInBackground();
                        mCodePush.initializeUpdateAfterRestart();
                    } catch (Exception e) {
                        // The recreation method threw an unknown exception
                        // so just simply fallback to restarting the Activity (if it exists)
                        loadBundleLegacy();
                    }
                }
            });

        } catch (Exception e) {
            // Our reflection logic failed somewhere
            // so fall back to restarting the Activity (if it exists)
            loadBundleLegacy();
        }
    }

    // This workaround has been implemented in order to fix https://github.com/facebook/react-native/issues/14533
    // resetReactRootViews allows to call recreateReactContextInBackground without any exceptions
    // This fix also relates to https://github.com/Microsoft/react-native-code-push/issues/878
    private void resetReactRootViews(ReactInstanceManager instanceManager) throws NoSuchFieldException, IllegalAccessException {
        Field mAttachedRootViewsField = instanceManager.getClass().getDeclaredField("mAttachedRootViews");
        mAttachedRootViewsField.setAccessible(true);
        List<ReactRootView> mAttachedRootViews = (List<ReactRootView>) mAttachedRootViewsField.get(instanceManager);
        for (ReactRootView reactRootView : mAttachedRootViews) {
            reactRootView.removeAllViews();
            reactRootView.setId(View.NO_ID);
        }
        mAttachedRootViewsField.set(instanceManager, mAttachedRootViews);
    }

    private void clearLifecycleEventListener() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListener != null) {
            getReactApplicationContext().removeLifecycleEventListener(mLifecycleEventListener);
            mLifecycleEventListener = null;
        }
    }

    // Use reflection to find the ReactInstanceManager. See #556 for a proposal for a less brittle way to approach this.
    private ReactInstanceManager resolveInstanceManager() throws NoSuchFieldException, IllegalAccessException {
        ReactInstanceManager instanceManager = CodePush.getReactInstanceManager();
        if (instanceManager != null) {
            return instanceManager;
        }

        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        instanceManager = reactApplication.getReactNativeHost().getReactInstanceManager();

        return instanceManager;
    }

    @ReactMethod
    public void downloadUpdate(final ReadableMap updatePackage, final boolean notifyProgress, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    JSONObject mutableUpdatePackage = CodePushUtils.convertReadableToJsonObject(updatePackage);
                    CodePushUtils.setJSONValueForKey(mutableUpdatePackage, CodePushConstants.BINARY_MODIFIED_TIME_KEY, "" + mCodePush.getBinaryResourcesModifiedTime());
                    mUpdateManager.downloadPackage(mutableUpdatePackage, mCodePush.getAssetsBundleFileName(), new DownloadProgressCallback() {
                        private boolean hasScheduledNextFrame = false;
                        private DownloadProgress latestDownloadProgress = null;

                        @Override
                        public void call(DownloadProgress downloadProgress) {
                            if (!notifyProgress) {
                                return;
                            }

                            latestDownloadProgress = downloadProgress;
                            // If the download is completed, synchronously send the last event.
                            if (latestDownloadProgress.isCompleted()) {
                                dispatchDownloadProgressEvent();
                                return;
                            }

                            if (hasScheduledNextFrame) {
                                return;
                            }

                            hasScheduledNextFrame = true;
                            getReactApplicationContext().runOnUiQueueThread(new Runnable() {
                                @Override
                                public void run() {
                                    ReactChoreographer.getInstance().postFrameCallback(ReactChoreographer.CallbackType.TIMERS_EVENTS, new ChoreographerCompat.FrameCallback() {
                                        @Override
                                        public void doFrame(long frameTimeNanos) {
                                            if (!latestDownloadProgress.isCompleted()) {
                                                dispatchDownloadProgressEvent();
                                            }

                                            hasScheduledNextFrame = false;
                                        }
                                    });
                                }
                            });
                        }

                        public void dispatchDownloadProgressEvent() {
                            getReactApplicationContext()
                                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                    .emit(CodePushConstants.DOWNLOAD_PROGRESS_EVENT_NAME, latestDownloadProgress.createWritableMap());
                        }
                    }, mCodePush.getPublicKey());

                    JSONObject newPackage = mUpdateManager.getPackage(CodePushUtils.tryGetString(updatePackage, CodePushConstants.PACKAGE_HASH_KEY));
                    promise.resolve(CodePushUtils.convertJsonObjectToWritable(newPackage));
                } catch (IOException e) {
                    e.printStackTrace();
                    promise.reject(e);
                } catch (CodePushInvalidUpdateException e) {
                    e.printStackTrace();
                    mSettingsManager.saveFailedUpdate(CodePushUtils.convertReadableToJsonObject(updatePackage));
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getConfiguration(Promise promise) {
        WritableMap configMap = Arguments.createMap();
        configMap.putString("appVersion", mCodePush.getAppVersion());
        configMap.putString("clientUniqueId", mClientUniqueId);
        configMap.putString("deploymentKey", mCodePush.getDeploymentKey());
        configMap.putString("serverUrl", mCodePush.getServerUrl());

        // The binary hash may be null in debug builds
        if (mBinaryContentsHash != null) {
            configMap.putString(CodePushConstants.PACKAGE_HASH_KEY, mBinaryContentsHash);
        }

        promise.resolve(configMap);
    }

    @ReactMethod
    public void getUpdateMetadata(final int updateState, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                JSONObject currentPackage = mUpdateManager.getCurrentPackage();
                if (currentPackage == null) {

                    CodePushUtils.log("getUpdateMetadata is null for updateState:"  + updateState );
                    promise.resolve(null);
                    return null;
                }
                CodePushUtils.log("getUpdateMetadata:" + currentPackage.toString() + " state:" + updateState);

                Boolean currentUpdateIsPending = false;

                if (currentPackage.has(CodePushConstants.PACKAGE_HASH_KEY)) {
                    String currentHash = currentPackage.optString(CodePushConstants.PACKAGE_HASH_KEY, null);
                    currentUpdateIsPending = mSettingsManager.isPendingUpdate(currentHash);
                }

                if (updateState == CodePushUpdateState.PENDING.getValue() && !currentUpdateIsPending) {
                    // The caller wanted a pending update
                    // but there isn't currently one.
                    promise.resolve(null);
                } else if (updateState == CodePushUpdateState.RUNNING.getValue() && currentUpdateIsPending) {
                    // The caller wants the running update, but the current
                    // one is pending, so we need to grab the previous.
                    JSONObject previousPackage = mUpdateManager.getPreviousPackage();

                    if (previousPackage == null) {
                        promise.resolve(null);
                        return null;
                    }

                    promise.resolve(CodePushUtils.convertJsonObjectToWritable(previousPackage));
                } else {
                    // The current package satisfies the request:
                    // 1) Caller wanted a pending, and there is a pending update
                    // 2) Caller wanted the running update, and there isn't a pending
                    // 3) Caller wants the latest update, regardless if it's pending or not
                    if (mCodePush.isRunningBinaryVersion()) {
                        // This only matters in Debug builds. Since we do not clear "outdated" updates,
                        // we need to indicate to the JS side that somehow we have a current update on
                        // disk that is not actually running.
                        CodePushUtils.setJSONValueForKey(currentPackage, "_isDebugOnly", true);
                    }

                    // Enable differentiating pending vs. non-pending updates
                    CodePushUtils.setJSONValueForKey(currentPackage, "isPending", currentUpdateIsPending);
                    promise.resolve(CodePushUtils.convertJsonObjectToWritable(currentPackage));
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getNewStatusReport(final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                if (mCodePush.needToReportRollback()) {
                    mCodePush.setNeedToReportRollback(false);
                    JSONArray failedUpdates = mSettingsManager.getFailedUpdates();
                    if (failedUpdates != null && failedUpdates.length() > 0) {
                        try {
                            JSONObject lastFailedPackageJSON = failedUpdates.getJSONObject(failedUpdates.length() - 1);
                            WritableMap lastFailedPackage = CodePushUtils.convertJsonObjectToWritable(lastFailedPackageJSON);
                            WritableMap failedStatusReport = mTelemetryManager.getRollbackReport(lastFailedPackage);
                            if (failedStatusReport != null) {
                                promise.resolve(failedStatusReport);
                                return null;
                            }
                        } catch (JSONException e) {
                            throw new CodePushUnknownException("Unable to read failed updates information stored in SharedPreferences.", e);
                        }
                    }
                } else if (mCodePush.didUpdate()) {
                    JSONObject currentPackage = mUpdateManager.getCurrentPackage();
                    if (currentPackage != null) {
                        WritableMap newPackageStatusReport = mTelemetryManager.getUpdateReport(CodePushUtils.convertJsonObjectToWritable(currentPackage));
                        if (newPackageStatusReport != null) {
                            promise.resolve(newPackageStatusReport);
                            return null;
                        }
                    }
                } else if (mCodePush.isRunningBinaryVersion()) {
                    WritableMap newAppVersionStatusReport = mTelemetryManager.getBinaryUpdateReport(mCodePush.getAppVersion());
                    if (newAppVersionStatusReport != null) {
                        promise.resolve(newAppVersionStatusReport);
                        return null;
                    }
                } else {
                    WritableMap retryStatusReport = mTelemetryManager.getRetryStatusReport();
                    if (retryStatusReport != null) {
                        promise.resolve(retryStatusReport);
                        return null;
                    }
                }

                promise.resolve("");
                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void installUpdate(final ReadableMap updatePackage, final int installMode, final int minimumBackgroundDuration, final Promise promise) {
        AsyncTask<Void, Void, File> asyncTask = new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... params) {

                // install on package receiver
                String pendingHash = CodePushUtils.tryGetString(updatePackage, CodePushConstants.PACKAGE_HASH_KEY);

                String binaryPath = CodePushUtils.tryGetString(updatePackage, CodePushConstants.BINARY_PATH_KEY);
                if (binaryPath != null && !binaryPath.isEmpty()) {
                    String majorPath = mUpdateManager.getPackageFolderPath(pendingHash);
                    mSettingsManager.savePendingUpdate(pendingHash ,true);
                    //}
                    File bundleDirectory = new File(majorPath, "CodePush");
                    if (!bundleDirectory.exists()) {
                        CodePushUtils.log("Bunde path does not exist");
                        promise.resolve("");
                        return null;
                    }
                    File[] files = bundleDirectory.listFiles(new APKFileFilter());
                    File binary = null;
                    for (File f : files) {
                        binary = f;
                        break;
                    }

                    if (binary != null) {
                        promise.resolve("");
                        return binary;
                    }

                }


                // only on minor
                mUpdateManager.installPackage(CodePushUtils.convertReadableToJsonObject(updatePackage), mSettingsManager.isPendingUpdate(null));
                if (pendingHash == null) {
                    throw new CodePushUnknownException("Update package to be installed has no hash.");
                } else {
                    mSettingsManager.savePendingUpdate(pendingHash, /* isLoading */false);
                }

                if (installMode == CodePushInstallMode.ON_NEXT_RESUME.getValue() ||
                        // We also add the resume listener if the installMode is IMMEDIATE, because
                        // if the current activity is backgrounded, we want to reload the bundle when
                        // it comes back into the foreground.
                        installMode == CodePushInstallMode.IMMEDIATE.getValue() ||
                        installMode == CodePushInstallMode.ON_NEXT_SUSPEND.getValue()) {

                    // Store the minimum duration on the native module as an instance
                    // variable instead of relying on a closure below, so that any
                    // subsequent resume-based installs could override it.
                    CodePushNativeModule.this.mMinimumBackgroundDuration = minimumBackgroundDuration;

                    if (mLifecycleEventListener == null) {
                        // Ensure we do not add the listener twice.
                        mLifecycleEventListener = new LifecycleEventListener() {
                            private Date lastPausedDate = null;
                            private Handler appSuspendHandler = new Handler(Looper.getMainLooper());
                            private Runnable loadBundleRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    CodePushUtils.log("Loading bundle on suspend");
                                    loadBundle();
                                }
                            };

                            @Override
                            public void onHostResume() {
                                appSuspendHandler.removeCallbacks(loadBundleRunnable);
                                // As of RN 36, the resume handler fires immediately if the app is in
                                // the foreground, so explicitly wait for it to be backgrounded first
                                if (lastPausedDate != null) {
                                    long durationInBackground = (new Date().getTime() - lastPausedDate.getTime()) / 1000;
                                    if (installMode == CodePushInstallMode.IMMEDIATE.getValue()
                                            || durationInBackground >= CodePushNativeModule.this.mMinimumBackgroundDuration) {
                                        CodePushUtils.log("Loading bundle on resume");
                                        loadBundle();
                                    }
                                }
                            }

                            @Override
                            public void onHostPause() {
                                // Save the current time so that when the app is later
                                // resumed, we can detect how long it was in the background.
                                lastPausedDate = new Date();

                                if (installMode == CodePushInstallMode.ON_NEXT_SUSPEND.getValue() && mSettingsManager.isPendingUpdate(null)) {
                                    appSuspendHandler.postDelayed(loadBundleRunnable, minimumBackgroundDuration * 1000);
                                }
                            }

                            @Override
                            public void onHostDestroy() {
                            }

                        };

                        getReactApplicationContext().addLifecycleEventListener(mLifecycleEventListener);
                    }
                }

                promise.resolve("");

                return null;
            }

            @Override
            protected void onPostExecute(File binary) {
                if (binary == null) {
                    return;
                }
                if (binary instanceof File && binary.exists()) {

                    try {
                        new JarFile(binary);
                        File local = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        File copiedBinary = new File(local, binary.getName());
                        FileUtils.copy(binary, copiedBinary);
                        CodePushUtils.log("Install new binary");

                       // package info changes
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setDataAndType(Uri.fromFile(copiedBinary), "application/vnd.android.package-archive");

                        getCurrentActivity().startActivity(intent);
                        getCurrentActivity().finish();
                        CodePushUtils.log(((mUpdateManager.getCurrentPackage() != null)? mUpdateManager.getCurrentPackage().toString() : "No current package"));
                    } catch (Exception e) {
                        CodePushUtils.log("Error occured");
                    }

                }
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public static class PackageReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if( !context.getPackageName().equals(intent.getData().getSchemeSpecificPart())) {
                CodePushUtils.log("other package was upgraded");
                return;
            }
            CodePush.finishMajorUpdate();
        }
    }

    @ReactMethod
    public void isFailedUpdate(String packageHash, Promise promise) {
        promise.resolve(mSettingsManager.isFailedHash(packageHash));
    }

    @ReactMethod
    public void isFirstRun(String packageHash, Promise promise) {
        boolean isFirstRun = mCodePush.didUpdate()
                && packageHash != null
                && packageHash.length() > 0
                && packageHash.equals(mUpdateManager.getCurrentPackageHash());
        promise.resolve(isFirstRun);
    }

    @ReactMethod
    public void notifyApplicationReady(Promise promise) {
        mSettingsManager.removePendingUpdate();
        promise.resolve("");
    }

    @ReactMethod
    public void recordStatusReported(ReadableMap statusReport) {
        mTelemetryManager.recordStatusReported(statusReport);
    }

    @ReactMethod
    public void restartApp(boolean onlyIfUpdateIsPending, Promise promise) {
        // If this is an unconditional restart request, or there
        // is current pending update, then reload the app.
        if (!onlyIfUpdateIsPending || mSettingsManager.isPendingUpdate(null)) {
            loadBundle();
            promise.resolve(true);
            return;
        }

        promise.resolve(false);
    }

    @ReactMethod
    public void saveStatusReportForRetry(ReadableMap statusReport) {
        mTelemetryManager.saveStatusReportForRetry(statusReport);
    }

    @ReactMethod
    // Replaces the current bundle with the one downloaded from removeBundleUrl.
    // It is only to be used during tests. No-ops if the test configuration flag is not set.
    public void downloadAndReplaceCurrentBundle(String remoteBundleUrl) {
        if (mCodePush.isUsingTestConfiguration()) {
            try {
                mUpdateManager.downloadAndReplaceCurrentBundle(remoteBundleUrl, mCodePush.getAssetsBundleFileName());
            } catch (IOException e) {
                throw new CodePushUnknownException("Unable to replace current bundle", e);
            }
        }
    }


    public static class APKFileFilter implements FileFilter {

        @Override
        public boolean accept(File pathname) {
            String suffix = ".apk";
            if (pathname.getName().toLowerCase().endsWith(suffix)) {
                return true;
            }
            return false;
        }

    }
}
