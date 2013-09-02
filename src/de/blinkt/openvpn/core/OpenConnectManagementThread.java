package de.blinkt.openvpn.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.ParcelFileDescriptor;

import org.infradead.libopenconnect.LibOpenConnect;
import org.jetbrains.annotations.NotNull;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.OpenVPN.ConnectionStatus;

public class OpenConnectManagementThread implements Runnable, OpenVPNManagement {

	public static Context context;
	private Handler mHandler;
	private VpnProfile mProfile;
	private OpenVpnService mOpenVPNService;
	private SharedPreferences mPrefs;

	LibOpenConnect mOC;
	private boolean mInitDone = false;

    public OpenConnectManagementThread(VpnProfile profile, OpenVpnService openVpnService) {
		mHandler = new Handler();
		mProfile = profile;
		mOpenVPNService = openVpnService;
		mPrefs = context.getSharedPreferences(mProfile.getUUID().toString(), Context.MODE_PRIVATE);
	}

    public boolean openManagementInterface(@NotNull Context c) {
    	return true;
    }

	private abstract class UiTask implements Runnable {
		abstract Object fn();

		private Object result;
		private boolean done = false;
		private Object lock = new Object();

		@Override
		public void run() {
			synchronized (lock) {
				result = fn();
				done = true;
				lock.notifyAll();
			}
		}

		public Object go() {
			mHandler.post(this);
			synchronized (lock) {
				while (!done) {
					try {
						lock.wait();
					} catch (InterruptedException e) {
					}
				}
			}
			return result;
		}
	}

    private String getStringPref(final String key) {
		UiTask r = new UiTask() {
			public Object fn() {
				return mPrefs.getString(key, "");
			}
		};
		return (String)r.go();
    }

	private class AndroidOC extends LibOpenConnect {
		public int onValidatePeerCert(String msg) {
			OpenVPN.logMessage(0, "", "CALLBACK: onValidatePeerCert");
			return 0;
		}

		public int onWriteNewConfig(byte[] buf) {
			OpenVPN.logMessage(0, "", "CALLBACK: onWriteNewConfig");
			return 0;
		}

		public int onProcessAuthForm(LibOpenConnect.AuthForm authForm) {
			OpenVPN.logMessage(0, "", "CALLBACK: onProcessAuthForm");
			for (FormOpt fo : authForm.opts) {
				if (fo.type == OC_FORM_OPT_TEXT) {
					OpenVPN.logMessage(0, "", "USER: " + mProfile.mUsername);
					fo.setValue(mProfile.mUsername);
				} else if (fo.type == OC_FORM_OPT_PASSWORD) {
					OpenVPN.logMessage(0, "", "PASS: ****");
					fo.setValue(mProfile.mPassword);
				}
			}
			return AUTH_FORM_PARSED;
		}

		public void onProgress(int level, String msg) {
			OpenVPN.logMessage(0, "", "PROGRESS: " + msg.trim());
		}

		public void onProtectSocket(int fd) {
			if (mOpenVPNService.protect(fd) != true) {
				OpenVPN.logMessage(0, "", "Error protecting fd " + fd);
			}
		}
	}

	private synchronized void initNative() {
		if (!mInitDone) {
			System.loadLibrary("openconnect");
		}
	}

	@Override
	public void run() {
		initNative();

		mOC = new AndroidOC();

		mOpenVPNService.updateState("USER_VPN_PASSWORD", "", R.string.state_user_vpn_password,
				ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT);

		if (mOC.parseURL(getStringPref("server_address")) != 0 ||
			mOC.obtainCookie() != 0 ||
			mOC.makeCSTPConnection() != 0) {

			mOpenVPNService.updateState("AUTH_FAILED", "",
					R.string.state_auth_failed, ConnectionStatus.LEVEL_AUTH_FAILED);
			return;
		}

		LibOpenConnect.IPInfo ip = mOC.getIPInfo();
		mOpenVPNService.setLocalIP(ip.addr, ip.netmask, ip.MTU, "");

		for (String s : ip.DNS) {
			mOpenVPNService.addDNS(s);
		}

		if (ip.splitIncludes.isEmpty()) {
			mOpenVPNService.addRoute("0.0.0.0", "0.0.0.0");
		} else {
			for (String s : ip.splitIncludes) {
				String ss[] = s.split("/");
				mOpenVPNService.addRoute(ss[0], ss[1]);
			}
			for (String s : ip.DNS) {
				mOpenVPNService.addRoute(s, "255.255.255.255");
			}
		}

		ParcelFileDescriptor pfd = mOpenVPNService.openTun();
		if (pfd == null || mOC.setupTunFD(pfd.getFd()) != 0) {
			mOpenVPNService.updateState("NOPROCESS", "",
					R.string.state_noprocess, ConnectionStatus.LEVEL_NOTCONNECTED);
			return;
		}

		mOpenVPNService.updateState("CONNECTED", "",
				R.string.state_connected, ConnectionStatus.LEVEL_CONNECTED);

		mOC.setupDTLS(60);
		mOC.mainloop(300, LibOpenConnect.RECONNECT_INTERVAL_MIN);

		mOpenVPNService.updateState("NOPROCESS", "",
				R.string.state_noprocess, ConnectionStatus.LEVEL_NOTCONNECTED);
	}

	public void reconnect() {
		OpenVPN.logMessage(0, "", "RECONNECT");
	}

	@Override
	public void pause (pauseReason reason) {
		OpenVPN.logMessage(0, "", "PAUSE");
	}

	@Override
	public void resume() {
		OpenVPN.logMessage(0, "", "RESUME");
	}

	@Override
	public boolean stopVPN() {
		OpenVPN.logMessage(0, "", "STOP");
		mOC.cancel();
		return true;
	}
}
