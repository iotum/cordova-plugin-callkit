package com.dmarc.cordovacall;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

// Centralizes logic to get/setup/access the phone account
public class PhoneAccountManager {
    private static PhoneAccountHandle phoneAccountHandle;
    private static PhoneAccount phoneAccount;

    public static String getApplicationName(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    public static PhoneAccountHandle getPhoneAccountHandle(Context context) {
        if (PhoneAccountManager.phoneAccountHandle == null) {
            String appName = PhoneAccountManager.getApplicationName(context);
            PhoneAccountManager.phoneAccountHandle = new PhoneAccountHandle(new ComponentName(context, MyConnectionService.class), appName);
        }
        return PhoneAccountManager.phoneAccountHandle;
    }

    public static PhoneAccount getPhoneAccount(Context context) {
        if (PhoneAccountManager.phoneAccount == null) {
            String appName = PhoneAccountManager.getApplicationName(context);
            PhoneAccountManager.phoneAccount = new PhoneAccount.Builder(PhoneAccountManager.getPhoneAccountHandle(context), appName)
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build();
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            tm.registerPhoneAccount(PhoneAccountManager.phoneAccount);
        }
        return PhoneAccountManager.phoneAccount;
    }
}
