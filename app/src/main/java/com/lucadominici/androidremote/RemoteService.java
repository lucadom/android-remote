package com.lucadominici.androidremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.IBinder;

public class RemoteService extends Service {
    private static final String ACTION_STOP = "com.lucadominici.androidremote.STOP";
    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 1;
    private static final int PAGE_UP = 0x4B;
    private static final int PAGE_DOWN = 0x4E;

    private static final byte[] KEYBOARD_DESCRIPTOR = new byte[] {
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08,
            (byte) 0x81, 0x02, (byte) 0x95, 0x01, 0x75, 0x08,
            (byte) 0x81, 0x01, (byte) 0x95, 0x05, 0x75, 0x01,
            0x05, 0x08, 0x19, 0x01, 0x29, 0x05, (byte) 0x91, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x91, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00,
            (byte) 0xC0
    };

    public interface Listener {
        void onRemoteChanged();
    }

    public final class LocalBinder extends Binder {
        RemoteService getService() {
            return RemoteService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private MediaSession mediaSession;
    private Notification.Builder notificationBuilder;
    private Listener listener;
    private boolean registered;
    private boolean shuttingDown;
    private String status = "Starting Bluetooth keyboard...";

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (shuttingDown) {
                        adapter.closeProfileProxy(profile, proxy);
                        return;
                    }
                    if (!(proxy instanceof BluetoothHidDevice)) {
                        updateStatus("Bluetooth HID profile is unavailable on this phone.");
                        return;
                    }
                    hidDevice = (BluetoothHidDevice) proxy;
                    registerHidApp();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    hidDevice = null;
                    registered = false;
                    connectedDevice = null;
                    setMediaControlActive(false);
                    updateStatus("Bluetooth HID disconnected. Restart the remote.");
                }
            };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            if (shuttingDown) {
                return;
            }
            registered = isRegistered;
            if (!registered) {
                connectedDevice = null;
                setMediaControlActive(false);
            }
            updateStatus(registered
                    ? "Ready. Pair this phone from Windows Bluetooth settings."
                    : "Bluetooth keyboard registration failed or was stopped.");
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (shuttingDown) {
                return;
            }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                setMediaControlActive(true);
                updateStatus("Connected to " + deviceName(device)
                        + ". Volume buttons control slides.");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(connectedDevice)) {
                    connectedDevice = null;
                    setMediaControlActive(false);
                }
                updateStatus("Disconnected from " + deviceName(device) + ".");
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                updateStatus("Connecting to " + deviceName(device) + "...");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Presentation remote", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RemoteService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notificationBuilder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Presentation remote active")
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stop).build());
        startForeground(NOTIFICATION_ID, notificationBuilder.build());

        mediaSession = new MediaSession(this, "Android Remote");
        mediaSession.setPlaybackToRemote(new VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override
            public void onAdjustVolume(int direction) {
                getMainExecutor().execute(() -> {
                    if (direction == AudioManager.ADJUST_RAISE) {
                        sendSlide(true);
                    } else if (direction == AudioManager.ADJUST_LOWER) {
                        sendSlide(false);
                    }
                });
            }
        });
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build());

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            updateStatus("Bluetooth is unavailable or turned off. Restart the remote.");
        } else if (!adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)) {
            updateStatus("This phone does not provide the Bluetooth HID Device profile.");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    String getStatus() {
        return status;
    }

    boolean isConnected(BluetoothDevice device) {
        return device.equals(connectedDevice);
    }

    boolean isStopped() {
        return shuttingDown;
    }

    void toggleConnection(BluetoothDevice device) {
        if (!registered || hidDevice == null) {
            updateStatus("Bluetooth keyboard is not ready yet.");
            return;
        }
        try {
            if (device.equals(connectedDevice)) {
                if (!hidDevice.disconnect(device)) {
                    updateStatus("Could not disconnect from " + deviceName(device) + ".");
                }
            } else if (connectedDevice != null) {
                updateStatus("Disconnect the current PC before connecting another device.");
            } else if (!hidDevice.connect(device)) {
                updateStatus("Could not connect to " + deviceName(device)
                        + ". Check Bluetooth pairing on Windows and try again.");
            } else {
                updateStatus("Connecting to " + deviceName(device) + "...");
            }
        } catch (SecurityException exception) {
            updateStatus("Nearby Bluetooth permissions are required.");
        }
    }

    void sendSlide(boolean next) {
        if (hidDevice == null || !registered || connectedDevice == null) {
            updateStatus("Connect a paired PC before using the volume buttons.");
            return;
        }
        byte[] pressed = new byte[] {0, 0, (byte) (next ? PAGE_DOWN : PAGE_UP), 0, 0, 0, 0, 0};
        try {
            boolean pressSent = hidDevice.sendReport(connectedDevice, 0, pressed);
            boolean releaseSent = hidDevice.sendReport(connectedDevice, 0, new byte[8]);
            if (!pressSent || !releaseSent) {
                updateStatus("Could not send the slide command. Check the Bluetooth connection.");
            }
        } catch (SecurityException exception) {
            updateStatus("Nearby Bluetooth permissions are required.");
        }
    }

    private void registerHidApp() {
        BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                "Android Remote", "Bluetooth presentation remote", "Android Remote",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, KEYBOARD_DESCRIPTOR);
        try {
            if (!hidDevice.registerApp(settings, null, null, getMainExecutor(), hidCallback)) {
                updateStatus("Could not register the Bluetooth keyboard on this phone.");
            } else {
                updateStatus("Registering Bluetooth keyboard...");
            }
        } catch (SecurityException exception) {
            updateStatus("Nearby Bluetooth permissions are required.");
        }
    }

    private void setMediaControlActive(boolean active) {
        if (mediaSession != null) {
            mediaSession.setActive(active);
        }
    }

    private void updateStatus(String message) {
        status = message;
        if (!shuttingDown && notificationBuilder != null) {
            notificationBuilder.setContentText(message);
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, notificationBuilder.build());
        }
        if (listener != null) {
            listener.onRemoteChanged();
        }
    }

    private String deviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? device.getAddress() : name;
        } catch (SecurityException exception) {
            return "unknown device (Bluetooth permission missing)";
        }
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        setMediaControlActive(false);
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (hidDevice != null) {
            try {
                if (registered) {
                    hidDevice.unregisterApp();
                }
            } catch (SecurityException exception) {
                android.util.Log.w("AndroidRemote", "Bluetooth permission revoked during shutdown", exception);
            }
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            hidDevice = null;
        }
        registered = false;
        connectedDevice = null;
        updateStatus("Remote stopped. Reopen the app to start it again.");
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
}
