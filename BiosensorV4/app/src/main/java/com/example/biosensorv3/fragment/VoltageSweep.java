package com.example.biosensorv3.fragment;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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

public class VoltageSweep extends Fragment {

    protected int setLayoutId() {
        return R.layout.fragment_voltage_sweep;
    }

    // BLE
    private static final UUID SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CHARACTERISTIC_UUID_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final String CHARACTERISTIC_UUID_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    // parameter used for Scan
    private int Previous_CV_Scan_end = 0;
    private int Loop_No = -1;
    private int Scan_counter = 0;
    public BluetoothGatt mbluetoothGatt;
    //Define chart
    private LineChart chartIV, chartVt, chartIt;
    private LineDataSet dataSetVI, dataSetVIRising, dataSetVIFalling;
    private LineDataSet dataSetVt, dataSetIt;
    private long startTime = -1;
    private int VI_Count = 0;
    private float currentTime;
    private float previousVoltage = Float.NaN;
    private Handler handler = new Handler(Looper.getMainLooper());

    //Widget used in layout
    private EditText edtMaxVolt, edtMinVolt, edtSweepRate, edtLoops;
    private TextView tvExportInfo;
    private View layoutChart, layoutExport, layoutConfig;
    private TabLayout tabLayout;
    private MenuActivity.BLEDataListener bleDataListener;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(setLayoutId(), container, false);
        MenuActivity activity = (MenuActivity) getActivity();
        if (activity != null) {
            mbluetoothGatt = activity.getBluetoothGatt();
        }
        return view;
    }

    private BroadcastReceiver bleDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        }
    };

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        //Initiate layout
        layoutChart = view.findViewById(R.id.layout_chart);
        layoutExport = view.findViewById(R.id.layout_export);
        layoutConfig = view.findViewById(R.id.layout_config);

        Button btnSendData = view.findViewById(R.id.SendData);
        btnSendData.setOnClickListener(v -> SendData(v));

        //Initiate TabLayout
        tabLayout = view.findViewById(R.id.tabLayout);
        tabLayout.addTab(tabLayout.newTab().setText("Chart"));
        tabLayout.addTab(tabLayout.newTab().setText("ExportInfo"));
        tabLayout.addTab(tabLayout.newTab().setText("Config"));

        layoutChart.setVisibility(View.VISIBLE);
        layoutExport.setVisibility(View.GONE);
        layoutConfig.setVisibility(View.GONE);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                layoutChart.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                layoutExport.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                layoutConfig.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }
            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        //Initiate chart
        chartIV = view.findViewById(R.id.chartIV);
        chartVt = view.findViewById(R.id.chartVt);
        chartIt = view.findViewById(R.id.chartIt);
        initCharts();

        edtMaxVolt = view.findViewById(R.id.edtMaxVolt);
        edtMinVolt = view.findViewById(R.id.edtMinVolt);
        edtSweepRate = view.findViewById(R.id.edtSweepRate);
        edtLoops = view.findViewById(R.id.edtLoops);


        // 初始化导出区域
        tvExportInfo = view.findViewById(R.id.tvExportInfo);
    }

    @Override
    public void onResume() {
        super.onResume();
        bleDataListener = new MenuActivity.BLEDataListener() {
            @Override
            public void onDataReceived(float current, float voltage, int CV_Scan_end) {
                updateCharts(current, voltage);

                if(CV_Scan_end==1)
                {
                    Scan_counter ++;
                }
                if (CV_Scan_end == 0 && Previous_CV_Scan_end == 1) {
                    startNewIVScan();
                }
                if (Scan_counter==Loop_No) {
                    ExportCSV();
                    Loop_No = -1;
                    Scan_counter = 0;
                }
                Previous_CV_Scan_end = CV_Scan_end;
            }
        };
        MenuActivity.setBLEDataListener(bleDataListener);
        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(bleDataReceiver, new IntentFilter("BLE_DATA"));
    }

    @Override
    public void onPause() {
        super.onPause();
        MenuActivity.setBLEDataListener(null);
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(bleDataReceiver);
    }

    private void initCharts() {
        dataSetVI = createDataSet("Legacy VI", Color.GRAY);
        dataSetVIRising = createDataSet("Rising Phase", Color.BLUE);
        dataSetVIFalling = createDataSet("Falling Phase", Color.BLUE);
        LineData viData = new LineData(dataSetVIRising, dataSetVIFalling);
        chartIV.setData(viData);
        initVIChartConfig();

        dataSetVt = createDataSet("Voltage", Color.RED);
        chartVt.setData(new LineData(dataSetVt));
        initTimeChartConfig(chartVt, "Voltage/Time", "Voltage (V)");

        dataSetIt = createDataSet("Current", Color.GREEN);
        chartIt.setData(new LineData(dataSetIt));
        initTimeChartConfig(chartIt, "Current/Time", "Current (A)");
    }

    private LineDataSet createDataSet(String label, int color) {
        LineDataSet set = new LineDataSet(new ArrayList<Entry>(), label);
        set.setColor(color);
        set.setCircleColor(color);
        set.setLineWidth(2f);
        set.setCircleRadius(0f);
        set.setDrawValues(false);
        set.setDrawCircles(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return set;
    }

    private void initVIChartConfig() {
        chartIV.getDescription().setText("Voltage/Current");
        chartIV.setTouchEnabled(true);
        chartIV.setDragEnabled(true);
        chartIV.setScaleEnabled(true);
        chartIV.setPinchZoom(true);
        chartIV.getLegend().setEnabled(false);

        XAxis xAxis = chartIV.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(0.5f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f V", value);
            }
        });

        YAxis yAxis = chartIV.getAxisLeft();
        yAxis.setGranularity(0.1f);
        yAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f μA", value);
            }
        });
        chartIV.getAxisRight().setEnabled(false);
    }

    private void initTimeChartConfig(LineChart chart, String title, String yLabel) {
        chart.getDescription().setText(title);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f s", value);
            }
        });

        YAxis yAxis_It = chartIt.getAxisLeft();
        yAxis_It.setGranularity(0.1f);
        yAxis_It.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f μA", value);
            }
        });

        YAxis yAxis_Vt = chartVt.getAxisLeft();
        yAxis_Vt.setGranularity(0.1f);
        yAxis_Vt.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.1f V", value);
            }
        });

        YAxis yAxis = chart.getAxisLeft();
        yAxis.setGranularity(0.5f);
        chart.getAxisRight().setEnabled(false);
    }

    private void updateCharts(float current, float voltage) {
        if (startTime < 0)
        {
            startTime = System.currentTimeMillis();
        }
        currentTime = (System.currentTimeMillis()-startTime)/1000.0f;
        if (!Float.isNaN(previousVoltage)) {
            if (voltage > previousVoltage) {
                dataSetVIRising.addEntryOrdered(new Entry(voltage, current));
            } else if (voltage < previousVoltage) {
                dataSetVIFalling.addEntryOrdered(new Entry(voltage, current));
            }
        }
        previousVoltage = voltage;
        dataSetVI.addEntry(new Entry(voltage, current));
        dataSetVt.addEntry(new Entry(currentTime, voltage));
        dataSetIt.addEntry(new Entry(currentTime, current));

        adjustCombinedYAxis(chartIV, dataSetVIRising, dataSetVIFalling);
        adjustYAxis(chartVt, dataSetVt);
        adjustYAxis(chartIt, dataSetIt);

        refreshAllCharts();
    }

    private void adjustCombinedYAxis(LineChart chart, LineDataSet... dataSets) {
        YAxis yAxis = chart.getAxisLeft();
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        for (LineDataSet set : dataSets) {
            if (set.getYMin() < minY) minY = set.getYMin();
            if (set.getYMax() > maxY) maxY = set.getYMax();
        }
        yAxis.setAxisMinimum(minY);
        yAxis.setAxisMaximum(maxY);
        float padding = (maxY - minY) * 0.1f; // 设置 10% 的边距
        yAxis.setAxisMinimum(minY - padding);
        yAxis.setAxisMaximum(maxY + padding);
        float dynamicGranularity = (maxY - minY) * 0.1f;
        yAxis.setGranularity(dynamicGranularity);
    }

    private void adjustYAxis(LineChart chart, LineDataSet dataSet) {
        YAxis yAxis = chart.getAxisLeft();
        float min = dataSet.getYMin();
        float max = dataSet.getYMax();
        float padding = (max - min) * 0.1f; // 设置 10% 的边距
        yAxis.setAxisMinimum(min - padding);
        yAxis.setAxisMaximum(max + padding);
        float dynamicGranularity = (max - min) * 0.1f;
        yAxis.setGranularity(dynamicGranularity);
    }
    private void refreshAllCharts() {
        chartIV.getData().notifyDataChanged();
        chartIV.notifyDataSetChanged();
        chartIV.invalidate();

        chartVt.getData().notifyDataChanged();
        chartVt.notifyDataSetChanged();
        chartVt.invalidate();

        chartIt.getData().notifyDataChanged();
        chartIt.notifyDataSetChanged();
        chartIt.invalidate();
    }

    private void startNewIVScan() {
        VI_Count++;
        LineData ivData = chartIV.getData();
        LineDataSet rising = createDataSet("Scan " + VI_Count + " Rising", Color.BLUE);
        LineDataSet falling = createDataSet("Scan " + VI_Count + " Falling", Color.BLUE);
        ivData.addDataSet(rising);
        ivData.addDataSet(falling);
        //Setting the new dataset rising and falling to the defined one to update chart
        dataSetVIRising = rising;
        dataSetVIFalling = falling;

        chartIV.getData().notifyDataChanged();
        chartIV.notifyDataSetChanged();
        chartIV.invalidate();
    }

    private String exportDataSetVIToCSV() {
        if (getContext() == null) return null;

        //Check if the Dataset is empty, if it is empty it would not be exported.
        int count = Math.min(dataSetVI.getEntryCount(), dataSetVt.getEntryCount());
        if (count == 0) {
            return "No data to export.";
        }

        StringBuilder csvBuilder = new StringBuilder();
        csvBuilder.append("V(V),I(μA),ExportTimestamp\n");
        for (int i = 0; i < count; i++) {
            Entry entryVI = dataSetVI.getEntryForIndex(i);
            Entry entryVT = dataSetVt.getEntryForIndex(i);
            csvBuilder.append(entryVI.getX())
                    .append(",")
                    .append(entryVI.getY())
                    .append(",")
                    .append(entryVT.getX())
                    .append("\n");
        }

        // 导出到公共 Documents 目录下一个以时间戳命名的文件夹中
        File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        String subFolderName = new SimpleDateFormat("yyyy_MM_dd_HH", Locale.getDefault()).format(new Date());
        File exportDir = new File(docsDir, subFolderName);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        String fileName = "dataSetVI_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date()) + ".csv";
        File file = new File(exportDir, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(csvBuilder.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        String exportTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        return "Exported file: " + fileName + "\nTime: " + exportTime;
    }


    private void clearAllData() {
        String exportInfo = exportDataSetVIToCSV();
        if (tvExportInfo != null && exportInfo != null) {
            tvExportInfo.setText(exportInfo);
        }
        dataSetVI.clear();
        dataSetVIRising.clear();
        dataSetVIFalling.clear();
        dataSetVt.clear();
        dataSetIt.clear();

        chartIV.clear();
        chartVt.clear();
        chartIt.clear();
        initCharts();

        previousVoltage = Float.NaN;
        currentTime = 0;
        VI_Count = 0;
    }

    private void ExportCSV(){
        String exportInfo = exportDataSetVIToCSV();
        if (tvExportInfo != null && exportInfo != null) {
            tvExportInfo.setText(exportInfo);
        }
        previousVoltage = Float.NaN;
    }


    private void SendData(View view) {
        double maxVolt,minVolt,sweepRate;
        int loops;
        //first clear all data when click send

        try {
            maxVolt = Double.parseDouble(edtMaxVolt.getText().toString());
            minVolt = Double.parseDouble(edtMinVolt.getText().toString());
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid voltage input", Toast.LENGTH_SHORT).show();
            return;
        }
        if (maxVolt < -0.8 || maxVolt > 0.8 || minVolt < -0.8 || minVolt > 0.8) {
            Toast.makeText(getContext(), "Max and Min voltages must be between -0.8V and 0.8V", Toast.LENGTH_SHORT).show();
            return;
        }
        if (maxVolt <= minVolt) {
            Toast.makeText(getContext(), "Max voltage must be greater than Min voltage", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            sweepRate = Double.parseDouble(edtSweepRate.getText().toString());
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid sweep rate", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sweepRate < 0.01 || sweepRate > 0.4) {
            Toast.makeText(getContext(), "Sweep rate must be between 0.01 and 0.4 V", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            loops = Integer.parseInt(edtLoops.getText().toString());
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Invalid number of loops", Toast.LENGTH_SHORT).show();
            return;
        }
        if (loops < 1 || loops > 8) {
            Toast.makeText(getContext(), "Number of loops must be between 1 and 8", Toast.LENGTH_SHORT).show();
            return;
        }
        String configStr = "0," + minVolt + "," + maxVolt + "," + sweepRate + "," + loops;
        byte[] configData = configStr.getBytes();
        writeConfigData(configData);
        Toast.makeText(getContext(), "Configuration sent", Toast.LENGTH_SHORT).show();

        clearAllData();
        Loop_No = loops;
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
}
