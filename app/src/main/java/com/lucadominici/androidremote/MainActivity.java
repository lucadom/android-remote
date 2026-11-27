package com.lucadominici.androidremote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;
    private static final int REQUEST_DISCOVERABLE = 3;

    private TextView statusView;
    private LinearLayout deviceList;
    private BluetoothAdapter bluetoothAdapter;
    private RemoteService remoteService;
    private boolean bound;
    private boolean remoteEnabled = true;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            remoteService = ((RemoteService.LocalBinder) binder).getService();
            remoteService.setListener(() -> {
                setStatus(remoteService.getStatus());
                if (remoteService.isStopped()) {
                    remoteEnabled = false;
                    unbindRemote();
                }
                refreshDevices();
            });
            setStatus(remoteService.getStatus());
            refreshDevices();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remoteService = null;
            setStatus("Remote service disconnected.");
            refreshDevices();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createLayout();
        requestBluetoothPermissionsOrInitialize();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (remoteEnabled && bluetoothAdapter != null && hasBluetoothPermissions()
                && bluetoothAdapter.isEnabled()) {
            startRemote();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
    }

    @Override
    protected void onStop() {
        unbindRemote();
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (remoteService != null) {
                    remoteService.sendSlide(keyCode == KeyEvent.KEYCODE_VOLUME_UP);
                } else {
                    setStatus("Start the remote and connect a PC first.");
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasBluetoothPermissions()) {
                initializeBluetooth();
            } else {
                setStatus("Nearby Bluetooth permissions are required.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                startRemote();
                refreshDevices();
            } else {
                setStatus("Turn on Bluetooth to use the remote.");
            }
        } else if (requestCode == REQUEST_DISCOVERABLE) {
            setStatus(resultCode > 0
                    ? "Phone is discoverable. Add it from Windows Bluetooth settings."
                    : "Bluetooth discoverability was not enabled.");
        }
    }

    private void createLayout() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Android Remote");
        title.setTextSize(26);
        content.addView(title, matchWrap());

        TextView instructions = new TextView(this);
        instructions.setText(
                "Pair this phone from Windows Bluetooth settings. Once connected, "
                        + "volume up advances and volume down goes back, even with the screen off "
                        + "when Android routes volume keys to the remote. Stop the remote when done.");
        instructions.setTextSize(16);
        instructions.setPadding(0, dp(12), 0, dp(16));
        content.addView(instructions, matchWrap());

        Button discoverableButton = new Button(this);
        discoverableButton.setText("Make phone discoverable");
        discoverableButton.setOnClickListener(view -> makeDiscoverable());
        content.addView(discoverableButton, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setPadding(0, dp(16), 0, dp(12));
        content.addView(statusView, matchWrap());

        TextView pairedTitle = new TextView(this);
        pairedTitle.setText("Paired devices");
        pairedTitle.setTextSize(18);
        content.addView(pairedTitle, matchWrap());

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(deviceList, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("Start remote");
        startButton.setOnClickListener(view -> {
            remoteEnabled = true;
            requestBluetoothPermissionsOrInitialize();
        });
        content.addView(startButton, matchWrap());

        Button stopButton = new Button(this);
        stopButton.setText("Stop remote");
        stopButton.setOnClickListener(view -> {
            remoteEnabled = false;
            unbindRemote();
            stopService(new Intent(this, RemoteService.class));
            setStatus("Remote stopped.");
            refreshDevices();
        });
        content.addView(stopButton, matchWrap());

        Button settingsButton = new Button(this);
        settingsButton.setText("Open Bluetooth settings");
        settingsButton.setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        content.addView(settingsButton, matchWrap());

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);
        setStatus("Starting Bluetooth...");
    }

    private void requestBluetoothPermissionsOrInitialize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
                getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply();
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
                return;
            }
        }
        initializeBluetooth();
    }

    private void initializeBluetooth() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        if (bluetoothAdapter == null) {
            setStatus("This phone does not support Bluetooth.");
            return;
        }
        try {
            if (!bluetoothAdapter.isEnabled()) {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
                return;
            }
        } catch (SecurityException exception) {
            setStatus("Nearby Bluetooth permissions are required.");
            return;
        }
        startRemote();
        refreshDevices();
    }

    private void startRemote() {
        if (bound) {
            return;
        }
        Intent intent = new Intent(this, RemoteService.class);
        startForegroundService(intent);
        bound = bindService(intent, serviceConnection, 0);
        if (!bound) {
            setStatus("Could not bind to the remote service.");
        }
    }

    private void unbindRemote() {
        if (bound) {
            if (remoteService != null) {
                remoteService.setListener(null);
                remoteService = null;
            }
            unbindService(serviceConnection);
            bound = false;
        }
    }

    private void makeDiscoverable() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissionsOrInitialize();
            return;
        }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try {
            startActivityForResult(intent, REQUEST_DISCOVERABLE);
        } catch (SecurityException exception) {
            setStatus("Nearby Bluetooth permissions are required.");
        }
    }

    private void refreshDevices() {
        if (deviceList == null || bluetoothAdapter == null || !hasBluetoothPermissions()) {
            return;
        }
        deviceList.removeAllViews();
        Set<BluetoothDevice> pairedDevices;
        try {
            pairedDevices = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException exception) {
            setStatus("Bluetooth permission is needed to list paired devices.");
            return;
        }
        if (pairedDevices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No paired devices. Pair the phone from Windows Bluetooth settings.");
            empty.setPadding(0, dp(8), 0, dp(8));
            deviceList.addView(empty, matchWrap());
            return;
        }
        for (BluetoothDevice device : pairedDevices) {
            Button deviceButton = new Button(this);
            boolean connected = remoteService != null && remoteService.isConnected(device);
            deviceButton.setText((connected ? "Disconnect: " : "Connect: ") + deviceName(device));
            deviceButton.setOnClickListener(view -> {
                if (remoteService == null) {
                    setStatus("Start the remote first.");
                } else {
                    remoteService.toggleConnection(device);
                }
            });
            deviceList.addView(deviceButton, matchWrap());
        }
    }

    private boolean hasBluetoothPermissions() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED);
    }

    private String deviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? device.getAddress() : name;
        } catch (SecurityException exception) {
            setStatus("Nearby Bluetooth permissions are required.");
            return "Unknown device";
        }
    }

    private void setStatus(String message) {
        if (statusView != null) {
            statusView.setText(message);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
