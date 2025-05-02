package com.example.biosensorv3.fragment;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.biosensorv3.MenuActivity;
import com.example.biosensorv3.R;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.UUID;

public class Amperometry extends Fragment {

    //UUID of the ESP32 C3
    private static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final String CHARACTERISTIC_UUID_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    public BluetoothGatt mbluetoothGatt;
    // UI Layout
    private TabLayout tabLayout;
    private View layoutChart, layout_config;
    private LineChart chartIt;
    private LineDataSet dataSetIt;
    private EditText edtDuration, edtVoltageApplied;
    private Button btnSendConfig;

    //TimeStamp for Exporting data
    private static final float TIME_STEP = 0.1f;
    private float currentTime = 0;

    private MenuActivity.BLEDataListener bleDataListener;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_amperometry, container, false);
        MenuActivity activity = (MenuActivity) getActivity();
        if (activity != null) {
            mbluetoothGatt = activity.getBluetoothGatt();
        }
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Initiate the Table Layout
        tabLayout = view.findViewById(R.id.tabLayout);
        layoutChart = view.findViewById(R.id.layout_chart);
        layout_config = view.findViewById(R.id.layout_config);

        //Adding two Tab named chart and config
        tabLayout.addTab(tabLayout.newTab().setText("Chart"));
        tabLayout.addTab(tabLayout.newTab().setText("Config"));

        layoutChart.setVisibility(View.VISIBLE);
        layout_config.setVisibility(View.GONE);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                layoutChart.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                layout_config.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }
            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        //Initiate the chart
        chartIt = view.findViewById(R.id.chartIt);
        initChart();

        // Initiate all the widget
        edtDuration = view.findViewById(R.id.edtDuration);
        edtVoltageApplied = view.findViewById(R.id.edtVoltageApplied);
        btnSendConfig = view.findViewById(R.id.btnSendConfig);
        btnSendConfig.setOnClickListener(v -> sendConfig());
    }

    @Override
    public void onResume() {
        super.onResume();
        //register listener to get data from the receiving ble page
        bleDataListener = new MenuActivity.BLEDataListener() {
            @Override
            public void onDataReceived(float current, float voltage, int CV_Scan_end) {
                updateChart(current);
            }
        };
        MenuActivity.setBLEDataListener(bleDataListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        MenuActivity.setBLEDataListener(null);
    }

    //The initiation of chart, including set color and XY axis
    private void initChart() {
        dataSetIt = createDataSet("Current", 0xFF00FF00, false);
        LineData data = new LineData(dataSetIt);
        chartIt.setData(data);
        chartIt.getDescription().setText("Current over Time");
        chartIt.setTouchEnabled(true);
        chartIt.setDragEnabled(true);
        chartIt.setScaleEnabled(true);
        chartIt.setPinchZoom(true);

        XAxis xAxis = chartIt.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f s", value);
            }
        });

        YAxis yAxis = chartIt.getAxisLeft();
        yAxis.setGranularity(0.5f);
        chartIt.getAxisRight().setEnabled(false);
    }

    //Create dataset to store the current and time
    private LineDataSet createDataSet(String label, int color, boolean enableCircles) {
        LineDataSet set = new LineDataSet(new ArrayList<Entry>(), label);
        set.setColor(color);
        set.setCircleColor(color);
        set.setLineWidth(2f);
        set.setCircleRadius(enableCircles ? 3f : 0f);
        set.setDrawValues(false);
        set.setDrawCircles(enableCircles);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return set;
    }

    //updating the chart while receiving the data
    private void updateChart(float currentValue) {
        currentTime += TIME_STEP;
        dataSetIt.addEntry(new Entry(currentTime, currentValue));
        chartIt.getData().notifyDataChanged();
        chartIt.notifyDataSetChanged();
        chartIt.invalidate();
    }

    //Send config function, through touching the Send button
    private void sendConfig() {
        String durationStr = edtDuration.getText().toString();
        String voltageStr = edtVoltageApplied.getText().toString();
        if (TextUtils.isEmpty(durationStr) || TextUtils.isEmpty(voltageStr)) {
            Toast.makeText(getContext(), "Please enter both Duration and Voltage Applied", Toast.LENGTH_SHORT).show();
            return;
        }
        // The data send with following type：mode,duration,voltageApplied
        String configStr = "2" + "," + durationStr + "," + voltageStr;
        byte[] configData = configStr.getBytes();
        writeConfigData(configData);
        Toast.makeText(getContext(), "Configuration sent", Toast.LENGTH_SHORT).show();
    }

    //Set the value and then send the value to the biosensor device
    private void writeConfigData(byte[] configData) {
        if (mbluetoothGatt == null) {
            Toast.makeText(getContext(), "Not connected to BLE device", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothGattService service = mbluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            Toast.makeText(getContext(), "BLE service not found", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_RX));
        if (characteristic == null) {
            Toast.makeText(getContext(), "BLE characteristic not found", Toast.LENGTH_SHORT).show();
            return;
        }
        characteristic.setValue(configData);
        boolean success = mbluetoothGatt.writeCharacteristic(characteristic);
        if (!success) {
            Toast.makeText(getContext(), "Failed to send configuration", Toast.LENGTH_SHORT).show();
        }
    }
}
