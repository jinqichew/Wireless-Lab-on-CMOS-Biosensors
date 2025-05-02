package com.example.biosensorv3;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.biosensorv3.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class device_connect_list extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "device_connect_list";
    private static final String SERVICE_UUID_STRING = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String CHARACTERISTIC_UUID_TX_STRING = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
    private static final String CLIENT_CHARACTERISTIC_CONFIG_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb";

    private static final UUID SERVICE_UUID = UUID.fromString(SERVICE_UUID_STRING);
    private static final UUID CHARACTERISTIC_UUID_TX = UUID.fromString(CHARACTERISTIC_UUID_TX_STRING);
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID_STRING);

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private ListView listView;
    private ArrayList<String> deviceList = new ArrayList<>();
    private ArrayAdapter<String> arrayAdapter;
    private BluetoothGatt bluetoothGatt;
    private TextView tvReceived;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ScanCallback scanCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_device_connect_list);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        listView = findViewById(R.id.listView);
        tvReceived = findViewById(R.id.tvReceived);
        arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        listView.setAdapter(arrayAdapter);

        checkPermissions();

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String info = deviceList.get(position);
                String address = info.split(" - ")[1];
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                connectToDevice(device);
            }
        });
    }

    private void checkPermissions() {
        List<String> requiredPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!requiredPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, requiredPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            initBluetooth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission denied, exiting.", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
            }
            initBluetooth();
        }
    }

    private void initBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        startScanning();
    }

    private void startScanning() {
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                if (result.getDevice().getName() != null) {
                    String deviceInfo = result.getDevice().getName() + " - " + result.getDevice().getAddress();
                    if (!deviceList.contains(deviceInfo)) {
                        deviceList.add(deviceInfo);
                        arrayAdapter.notifyDataSetChanged();
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Scan failed with error code: " + errorCode);
            }
        };
        bluetoothLeScanner.startScan(scanCallback);
    }

    private void connectToDevice(BluetoothDevice device) {
        if (bluetoothLeScanner != null && scanCallback != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        bluetoothGatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread(() -> Toast.makeText(device_connect_list.this, "Connected", Toast.LENGTH_SHORT).show());
                    gatt.discoverServices();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    BluetoothGattService service = gatt.getService(SERVICE_UUID);
                    if (service != null) {
                        BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID_TX);
                        if (characteristic != null) {
                            gatt.setCharacteristicNotification(characteristic, true);
                            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                            }
                        }
                    }

                    Intent intent = new Intent(device_connect_list.this, MenuActivity.class);
                    intent.putExtra("DEVICE_ADDRESS", device.getAddress());
                    startActivity(intent);
                    finish();
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                byte[] data = characteristic.getValue();
                if (data == null) return;
                try {
                    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                    float current = bb.getFloat();
                    float voltage = bb.getFloat();
                    int CV_Scan_end = bb.getInt();
                    runOnUiThread(() -> tvReceived.setText("Current: " + current + "\nVoltage: " + voltage + "\nEnd_Scan: " + CV_Scan_end));

                    Intent intent = new Intent("BLE_DATA");
                    intent.putExtra("current", current);
                    intent.putExtra("voltage", voltage);
                    intent.putExtra("CV_Scan_end",CV_Scan_end);
                    LocalBroadcastManager.getInstance(device_connect_list.this).sendBroadcast(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Data parsing error: " + e.getMessage());
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        super.onDestroy();
    }
}