package com.example.biosensorv3.fragment;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class Potentiometry extends Fragment {

    //BLE characteristics
    private static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final String CHARACTERISTIC_UUID_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";

    public BluetoothGatt mbluetoothGatt;

    //UI layout
    private TabLayout tabLayout;
    private View layoutChart, layoutConfig;
    private LineChart chartVt;
    private LineDataSet dataSetVt;
    private EditText edtPeriod;
    private Button btnSendConfig;

    //Time stamp
    private static final float TIME_STEP = 0.1f;
    private float currentTime = 0;

    private Handler exportHandler = new Handler(Looper.getMainLooper());
    private Runnable exportRunnable;
    private long exportIntervalMillis = 0;

    private MenuActivity.BLEDataListener bleDataListener;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_potentiometry, container, false);
        // 获取 MenuActivity 中的 BluetoothGatt 实例
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
        layoutConfig = view.findViewById(R.id.layout_config);

        //Adding two Tab named chart and config
        tabLayout.addTab(tabLayout.newTab().setText("Chart"));
        tabLayout.addTab(tabLayout.newTab().setText("Config"));


        layoutChart.setVisibility(View.VISIBLE);
        layoutConfig.setVisibility(View.GONE);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                layoutChart.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                layoutConfig.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }
            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        //Initiate the chart
        chartVt = view.findViewById(R.id.chartVt);
        initChart();

        // Initiate all the widget
        edtPeriod = view.findViewById(R.id.edtPeriod);
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
                updateChart(voltage);
            }
        };
        MenuActivity.setBLEDataListener(bleDataListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        MenuActivity.setBLEDataListener(null);
        if (exportHandler != null && exportRunnable != null) {
            exportHandler.removeCallbacks(exportRunnable);
        }
    }

    //The initiation of chart, including set color and XY axis
    private void initChart() {
        dataSetVt = createDataSet("Voltage", 0xFFFF0000, false);
        LineData data = new LineData(dataSetVt);
        chartVt.setData(data);
        chartVt.getDescription().setText("Voltage over Time");
        chartVt.setTouchEnabled(true);
        chartVt.setDragEnabled(true);
        chartVt.setScaleEnabled(true);
        chartVt.setPinchZoom(true);

        XAxis xAxis = chartVt.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f s", value);
            }
        });

        YAxis yAxis = chartVt.getAxisLeft();
        yAxis.setGranularity(0.1f);
        chartVt.getAxisRight().setEnabled(false);
    }

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

    //Updating the chart
    private void updateChart(float voltage) {
        currentTime += TIME_STEP;
        dataSetVt.addEntry(new Entry(currentTime, voltage));
        chartVt.getData().notifyDataChanged();
        chartVt.notifyDataSetChanged();
        chartVt.invalidate();
    }

    //Config sending: Mode, period
    private void sendConfig() {
        String periodStr = edtPeriod.getText().toString();
        if (TextUtils.isEmpty(periodStr)) {
            Toast.makeText(getContext(), "Please enter a period", Toast.LENGTH_SHORT).show();
            return;
        }
        //Config string to send
        clearAllData();
        String configStr = "1" + "," + periodStr;
        byte[] configData = configStr.getBytes();
        writeConfigData(configData);
        Toast.makeText(getContext(), "Configuration sent", Toast.LENGTH_SHORT).show();
        try {
            float periodSeconds = Float.parseFloat(periodStr);
            exportIntervalMillis = (long) (periodSeconds * 1000);
            scheduleExportTask();
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid period value", Toast.LENGTH_SHORT).show();
        }
    }

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

    private void scheduleExportTask() {
        if (exportRunnable != null) {
            exportHandler.removeCallbacks(exportRunnable);
        }
        exportRunnable = new Runnable() {
            @Override
            public void run() {
                String exportMsg = exportDataSetToCSV();
                Toast.makeText(getContext(), "Auto Export: " + exportMsg, Toast.LENGTH_LONG).show();
                clearAllData();
                exportHandler.postDelayed(this, exportIntervalMillis);
            }
        };
        exportHandler.postDelayed(exportRunnable, exportIntervalMillis);
    }

    // Export CSV file, two output: voltage and time
    private String exportDataSetToCSV() {
        if (getContext() == null) return "Context not available.";
        int count = dataSetVt.getEntryCount();
        if (count == 0) {
            return "No data to export.";
        }
        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append("Voltage,Timestamp\n");
        for (int i = 0; i < count; i++) {
            Entry entry = dataSetVt.getEntryForIndex(i);
            csvBuilder.append(entry.getY())
                    .append(",")
                    .append(entry.getX())
                    .append("\n");
        }
        // Export to certain directory
        File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        String subFolderName = new SimpleDateFormat("yyyy_MM_dd_HH", Locale.getDefault()).format(new Date());
        File exportDir = new File(docsDir, subFolderName);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        String fileName = "dataSetPotentiometry_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date()) + ".csv";
        File file = new File(exportDir, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(csvBuilder.toString());
        } catch (IOException e) {
            e.printStackTrace();
            return "Export failed: " + e.getMessage();
        }
        String exportTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        return "Exported file: " + fileName + "\nTime: " + exportTime;
    }

    private void clearAllData() {
        if (chartVt != null && dataSetVt != null) {
            dataSetVt.clear();
            chartVt.clear();
            initChart();
            currentTime = 0;
        }
    }
}