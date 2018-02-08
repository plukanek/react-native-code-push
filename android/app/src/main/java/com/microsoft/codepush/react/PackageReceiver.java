package com.microsoft.codepush.react;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.nimbusds.jose.JOSEObject;

import org.json.JSONException;
import org.json.JSONObject;

public class PackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
       if( !context.getPackageName().equals(intent.getData().getSchemeSpecificPart())){
           CodePushUtils.log("other package was upgraded");
           return;
        }
        //return;

        Context mContext  = context.getApplicationContext();
        CodePushUpdateManager mUpdateManager = new CodePushUpdateManager(context.getFilesDir().getAbsolutePath());
        SettingsManager mSettingsManager = new SettingsManager(mContext);
        try {
            CodePushUtils.log("finish major install:" + mSettingsManager.getPendingUpdate().toString());
            String pendingHash = mSettingsManager.getPendingUpdate().getString(CodePushConstants.PENDING_UPDATE_HASH_KEY);
            JSONObject newPackage = new JSONObject();
            newPackage.putOpt(CodePushConstants.PACKAGE_HASH_KEY , pendingHash);
            mUpdateManager.installPackage(newPackage ,  mSettingsManager.isPendingUpdate(null) );
            if (pendingHash == null) {
                throw new CodePushUnknownException("Update package to be installed has no hash.");
            } else {
                mSettingsManager.savePendingUpdate(pendingHash, /* isLoading */false);
            }
            CodePush.sIsRunningBinaryVersion = false;
        }catch (JSONException e){
            CodePushUtils.log("error finishing update:" + e.getMessage());
        }
    }
}
