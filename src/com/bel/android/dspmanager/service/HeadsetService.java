package com.bel.android.dspmanager.service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.provider.Settings;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.bel.android.dspmanager.activity.DSPManager;

/**
 * <p>This calls listen to events that affect DSP function and responds to them.</p>
 * <ol>
 * <li>new audio session declarations</li>
 * <li>headset plug / unplug events</li>
 * <li>preference update events.</li>
 * </ol>
 *
 * @author alankila
 */
public class HeadsetService extends Service {
	/**
	 * Helper class representing the full complement of effects attached to one
	 * audio session.
	 *
	 * @author alankila
	 */
	protected static class EffectSet {
		private static final UUID EFFECT_TYPE_VOLUME = UUID
				.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b");
		private static final UUID EFFECT_TYPE_NULL = UUID
				.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

		/** Session-specific dynamic range compressor */
		public final AudioEffect mCompression;
		/** Session-specific equalizer */
		private final Equalizer mEqualizer;
		/** Session-specific bassboost */
		private final BassBoost mBassBoost;
		/** Session-specific virtualizer */
		private final Virtualizer mVirtualizer;

		protected EffectSet(int sessionId) {
			try {
				/*
				 * AudioEffect constructor is not part of SDK. We use reflection
				 * to access it.
				 */
				mCompression = AudioEffect.class.getConstructor(UUID.class,
						UUID.class, Integer.TYPE, Integer.TYPE).newInstance(
						EFFECT_TYPE_VOLUME, EFFECT_TYPE_NULL, 0, sessionId);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			mEqualizer = new Equalizer(0, sessionId);
			mBassBoost = new BassBoost(0, sessionId);
			mVirtualizer = new Virtualizer(0, sessionId);
		}

		protected void release() {
			mCompression.release();
			mEqualizer.release();
			mBassBoost.release();
			mVirtualizer.release();
		}

		/**
		 * Proxies call to AudioEffect.setParameter(byte[], byte[]) which is
		 * available via reflection.
		 *
		 * @param audioEffect
		 * @param parameter
		 * @param value
		 */
		private static void setParameter(AudioEffect audioEffect, int parameter, short value) {
			try {
				byte[] arguments = new byte[] {
						(byte) (parameter), (byte) (parameter >> 8),
						(byte) (parameter >> 16), (byte) (parameter >> 24)
				};
				byte[] result = new byte[] {
						(byte) (value), (byte) (value >> 8)
				};

				Method setParameter = AudioEffect.class.getMethod(
						"setParameter", byte[].class, byte[].class);
				int returnValue = (Integer) setParameter.invoke(audioEffect,
						arguments, result);

				if (returnValue != 0) {
					Log.e(TAG,
							String.format(
									"Invalid argument error in setParameter(%d, (short) %d) == %d",
									parameter, value, returnValue));
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	protected static final String TAG = HeadsetService.class.getSimpleName();

	public class LocalBinder extends Binder {
		public HeadsetService getService() {
			return HeadsetService.this;
		}
	}

	private final LocalBinder mBinder = new LocalBinder();

	/** Known audio sessions and their associated audioeffect suites. */
	protected final Map<Integer, EffectSet> mAudioSessions = new HashMap<Integer, EffectSet>();

	/** Is a wired headset plugged in? */
	protected boolean mUseHeadset;

	/** Is bluetooth headset plugged in? */
	protected boolean mUseBluetooth;

	/** Has DSPManager assumed control of equalizer levels? */
	private float[] mOverriddenEqualizerLevels;

	/**
	 * Receive new broadcast intents for adding DSP to session
	 */
    private final BroadcastReceiver mAudioSessionReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
			if (action.equals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)) {
				Log.i(TAG, String.format("New audio session: %d", sessionId));
				if (! mAudioSessions.containsKey(sessionId)) {
					mAudioSessions.put(sessionId, new EffectSet(sessionId));
				}
			}
			if (action.equals(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)) {
				Log.i(TAG, String.format("Audio session removed: %d", sessionId));
				EffectSet gone = mAudioSessions.remove(sessionId);
				if (gone != null) {
					gone.release();
				}
			}
			updateDsp();
		}
	};

	/**
	 * Update audio parameters when preferences have been updated.
	 */
	private final BroadcastReceiver mPreferenceUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.i(TAG, "Preferences updated.");
			updateDsp();
		}
	};

	/**
	 * This code listens for changes in bluetooth and headset events. It is
	 * adapted from google's own MusicFX application, so it's presumably the
	 * most correct design there is for this problem.
	 */
	private final BroadcastReceiver mRoutingReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			final String action = intent.getAction();
			final boolean prevUseHeadset = mUseHeadset;
			final boolean prevUseBluetooth = mUseBluetooth;
			final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
			if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
				mUseHeadset = intent.getIntExtra("state", 0) == 1;
				boolean launchPlayer = Settings.System.getInt(getContentResolver(),
					Settings.System.HEADSET_CONNECT_PLAYER, 0) != 0;
				if (mUseHeadset && launchPlayer) {
					Intent playerIntent = new Intent(Intent.ACTION_MAIN);
					playerIntent.addCategory(Intent.CATEGORY_APP_MUSIC);
					playerIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(playerIntent);
				}
			} else if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
				final int deviceClass = ((BluetoothDevice) intent
						.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)).getBluetoothClass()
						.getDeviceClass();
				if ((deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES)
						|| (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)) {
					mUseBluetooth = true;
				}
			} else if (action.equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
				mUseBluetooth = audioManager.isBluetoothA2dpOn();
				mUseHeadset = audioManager.isWiredHeadsetOn();
			} else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
				final BluetoothDevice device =
						((BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
				if (device == null || device.getBluetoothClass() == null) {
					return;
				}
				final int deviceClass = device.getBluetoothClass().getDeviceClass();
				if ((deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES)
						|| (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)) {
					mUseBluetooth = false;
				}
			}

            Log.i(TAG, "Headset=" + mUseHeadset + "; Bluetooth=" + mUseBluetooth);
			if (prevUseHeadset != mUseHeadset
					|| prevUseBluetooth != mUseBluetooth) {
				updateDsp();
			}
        }
    };

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i(TAG, "Starting service.");

		IntentFilter audioFilter = new IntentFilter();
		audioFilter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
		audioFilter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
		registerReceiver(mAudioSessionReceiver, audioFilter);

		final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
		intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
		intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
		intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
		registerReceiver(mRoutingReceiver, intentFilter);

		registerReceiver(mPreferenceUpdateReceiver,
				new IntentFilter(DSPManager.ACTION_UPDATE_PREFERENCES));
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.i(TAG, "Stopping service.");

		unregisterReceiver(mAudioSessionReceiver);
		unregisterReceiver(mRoutingReceiver);
		unregisterReceiver(mPreferenceUpdateReceiver);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	/**
	 * Gain temporary control over the global equalizer.
	 * Used by DSPManager when testing a new equalizer setting.
	 *
	 * @param levels
	 */
	public void setEqualizerLevels(float[] levels) {
		mOverriddenEqualizerLevels = levels;
		updateDsp();
	}

	/**
	 * There appears to be no way to find out what the current actual audio routing is.
	 * For instance, if a wired headset is plugged in, the following objects/classes are involved:</p>
	 * <ol>
	 * <li>wiredaccessoryobserver</li>
	 * <li>audioservice</li>
	 * <li>audiosystem</li>
	 * <li>audiopolicyservice</li>
	 * <li>audiopolicymanager</li>
	 * </ol>
	 * <p>Once the decision of new routing has been made by the policy manager, it is relayed to
	 * audiopolicyservice, which waits for some time to let application buffers drain, and then
	 * informs it to hardware. The full chain is:</p>
	 * <ol>
	 * <li>audiopolicymanager</li>
	 * <li>audiopolicyservice</li>
	 * <li>audiosystem</li>
	 * <li>audioflinger</li>
	 * <li>audioeffect (if any)</li>
	 * </ol>
	 * <p>However, the decision does not appear to be relayed to java layer, so we must
	 * make a guess about what the audio output routing is.</p>
	 *
	 * @return string token that identifies configuration to use
	 */
	public String getAudioOutputRouting() {
		if (mUseBluetooth) {
			return "bluetooth";
		}
		if (mUseHeadset) {
			return "headset";
		}
		return "speaker";
	}

	/**
	 * Push new configuration to audio stack.
	 */
	protected void updateDsp() {
		final String mode = getAudioOutputRouting();
		SharedPreferences preferences = getSharedPreferences(DSPManager.SHARED_PREFERENCES_BASENAME + "." + mode, 0);
		Log.i(TAG, "Selected configuration: " + mode);

		for (Integer sessionId : new ArrayList<Integer>(mAudioSessions.keySet())) {
			try {
				updateDsp(preferences, mAudioSessions.get(sessionId));
			}
			catch (Exception e) {
				Log.w(TAG, String.format("Trouble trying to manage session %d, removing...", sessionId), e);
				mAudioSessions.remove(sessionId);
			}
		}
	}

	private void updateDsp(SharedPreferences preferences, EffectSet session) {
		session.mCompression.setEnabled(preferences.getBoolean("dsp.compression.enable", false));
		EffectSet.setParameter(session.mCompression, 0, Short.valueOf(preferences.getString("dsp.compression.mode", "0")));

		session.mBassBoost.setEnabled(preferences.getBoolean("dsp.bass.enable", false));
		session.mBassBoost.setStrength(Short.valueOf(preferences.getString("dsp.bass.mode", "0")));

		/* Equalizer state is in a single string preference with all values separated by ; */
		session.mEqualizer.setEnabled(preferences.getBoolean("dsp.tone.enable", false));
		if (mOverriddenEqualizerLevels != null) {
			for (short i = 0; i < mOverriddenEqualizerLevels.length; i ++) {
				session.mEqualizer.setBandLevel(i, (short) Math.round(Float.valueOf(mOverriddenEqualizerLevels[i]) * 100));
			}
		} else {
			String[] levels = preferences.getString("dsp.tone.eq.custom", "0;0;0;0;0").split(";");
			for (short i = 0; i < levels.length; i ++) {
				session.mEqualizer.setBandLevel(i, (short) Math.round(Float.valueOf(levels[i]) * 100));
			}
		}
		EffectSet.setParameter(session.mEqualizer, 1000, Short.valueOf(preferences.getString("dsp.tone.loudness", "10000")));

		session.mVirtualizer.setEnabled(preferences.getBoolean("dsp.headphone.enable", false));
		session.mVirtualizer.setStrength(Short.valueOf(preferences.getString("dsp.headphone.mode", "0")));
	}
}
