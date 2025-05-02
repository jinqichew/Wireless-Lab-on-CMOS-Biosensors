package com.example.biosensorv3;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.biosensorv3.fragment.Amperometry;
import com.example.biosensorv3.fragment.Potentiometry;
import com.example.biosensorv3.fragment.VoltageSweep;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public class MenuActivity extends AppCompatActivity {

    private static final String TAG = "MenuActivity";
    private static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CHARACTERISTIC_UUID_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private BluetoothGatt bluetoothGatt;
    private BottomNavigationView bottomNavigation;

    public BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    public interface BLEDataListener {
        void onDataReceived(float current, float voltage, int CV_Scan_end);
    }

    private static BLEDataListener bleDataListener;

    public static void setBLEDataListener(BLEDataListener listener) {
        bleDataListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);

        String deviceAddress = getIntent().getStringExtra("DEVICE_ADDRESS");
        connectToDevice(deviceAddress);

        //Default display
        showFragment(new VoltageSweep());

        // Fragment Change
        bottomNavigation = findViewById(R.id.bottomNavigation);
        bottomNavigation.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                Fragment fragment = null;
                switch (item.getItemId()) {
                    case R.id.menu_cyclic_voltammetry:
                        fragment = new VoltageSweep();
                        break;
                    case R.id.menu_amperometry:
                        fragment = new Amperometry();
                        break;
                    case R.id.menu_potentiometry:
                        fragment = new Potentiometry();
                        break;
                }
                if (fragment != null) {
                    showFragment(fragment);
                }
                return true;
            }
        });
    }

    private void showFragment(Fragment fragment) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.container, fragment);
        ft.commit();
    }

    private void connectToDevice(String deviceAddress) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported");
            return;
        }
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        if (device != null) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
            Log.d(TAG, "Connecting to: " + deviceAddress);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> Toast.makeText(MenuActivity.this, "Disconnected", Toast.LENGTH_SHORT).show());
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                Intent intent = new Intent(MenuActivity.this, Connect_BLE_Activity.class);
                startActivity(intent);
                finish();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt.getService(SERVICE_UUID) != null) {
                BluetoothGattCharacteristic characteristic = gatt.getService(SERVICE_UUID)
                        .getCharacteristic(CHARACTERISTIC_UUID_TX);
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true);
                    Log.d(TAG, "Notifications enabled");
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) return;
            try {
                float current = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                float voltage = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                int CV_Scan_end = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (bleDataListener != null) {
                    bleDataListener.onDataReceived(current, voltage, CV_Scan_end);
                }
            } catch (Exception e) {
                Log.e(TAG, "Data parsing error: " + e.getMessage());
            }
        }
    };

    public void disconnect(View view) {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
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