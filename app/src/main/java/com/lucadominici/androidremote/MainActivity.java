package com.lucadominici.androidremote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

    private TextView statusView;
    private LinearLayout deviceList;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private boolean appRegistered;

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (!(proxy instanceof BluetoothHidDevice)) {
                        setStatus("Bluetooth HID profile is unavailable on this phone.");
                        return;
                    }
                    hidDevice = (BluetoothHidDevice) proxy;
                    registerHidApp();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    hidDevice = null;
                    appRegistered = false;
                    connectedDevice = null;
                    setStatus("Bluetooth HID disconnected.");
                    refreshDevices();
                }
            };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            appRegistered = registered;
            runOnUiThread(() -> {
                setStatus(registered
                        ? "Ready. Pair this phone from Windows Bluetooth settings."
                        : "Bluetooth keyboard registration failed.");
                refreshDevices();
            });
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            runOnUiThread(() -> {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = device;
                    setStatus("Connected to " + deviceName(device) + ". Volume buttons control slides.");
                } else if (device.equals(connectedDevice)) {
                    connectedDevice = null;
                    setStatus(state == BluetoothProfile.STATE_DISCONNECTED
                            ? "Disconnected from " + deviceName(device) + "."
                            : "Connecting to " + deviceName(device) + "...");
                }
                refreshDevices();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createLayout();
        requestBluetoothPermissionsOrInitialize();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
    }

    @Override
    protected void onDestroy() {
        if (bluetoothAdapter != null && hidDevice != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                sendKey(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? PAGE_DOWN : PAGE_UP);
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
                openHidProfile();
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
                        + "volume up advances and volume down goes back.");
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
        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        openHidProfile();
        refreshDevices();
    }

    private void openHidProfile() {
        if (bluetoothAdapter == null) {
            return;
        }
        if (!bluetoothAdapter.getProfileProxy(
                this, profileListener, BluetoothProfile.HID_DEVICE)) {
            setStatus("This phone does not provide the Bluetooth HID Device profile.");
        }
    }

    private void registerHidApp() {
        BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                "Android Remote",
                "Bluetooth presentation remote",
                "Android Remote",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                KEYBOARD_DESCRIPTOR);
        boolean accepted = hidDevice.registerApp(settings, null, null, getMainExecutor(), hidCallback);
        if (!accepted) {
            setStatus("Could not register the Bluetooth keyboard on this phone.");
        } else {
            setStatus("Registering Bluetooth keyboard...");
        }
    }

    private void makeDiscoverable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissionsOrInitialize();
            return;
        }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(intent, REQUEST_DISCOVERABLE);
    }

    private void refreshDevices() {
        if (deviceList == null || bluetoothAdapter == null || !hasBluetoothPermission()) {
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
            boolean connected = device.equals(connectedDevice);
            deviceButton.setText((connected ? "Disconnect: " : "Connect: ") + deviceName(device));
            deviceButton.setOnClickListener(view -> toggleConnection(device));
            deviceList.addView(deviceButton, matchWrap());
        }
    }

    private void toggleConnection(BluetoothDevice device) {
        if (!appRegistered || hidDevice == null) {
            setStatus("Bluetooth keyboard is not ready yet.");
            return;
        }
        if (device.equals(connectedDevice)) {
            if (!hidDevice.disconnect(device)) {
                setStatus("Could not disconnect from " + deviceName(device) + ".");
            }
            return;
        }
        if (!hidDevice.connect(device)) {
            setStatus("Could not connect to " + deviceName(device)
                    + ". Check Bluetooth pairing on Windows and try again.");
        } else {
            setStatus("Connecting to " + deviceName(device) + "...");
        }
    }

    private void sendKey(int keyCode) {
        if (hidDevice == null || !appRegistered || connectedDevice == null) {
            setStatus("Connect a paired PC before using the volume buttons.");
            return;
        }
        byte[] pressed = new byte[] {0, 0, (byte) keyCode, 0, 0, 0, 0, 0};
        byte[] released = new byte[8];
        if (!hidDevice.sendReport(connectedDevice, 0, pressed)
                || !hidDevice.sendReport(connectedDevice, 0, released)) {
            setStatus("Could not send the slide command. Check the Bluetooth connection.");
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothPermissions() {
        return hasBluetoothPermission()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED);
    }

    private String deviceName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.trim().isEmpty() ? device.getAddress() : name;
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
