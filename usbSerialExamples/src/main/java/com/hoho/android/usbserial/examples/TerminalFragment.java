package com.hoho.android.usbserial.examples;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private static final String TAG = "TerminalFragment";

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;

    private BroadcastReceiver broadcastReceiver;
    private Handler mainLooper;

    private boolean isOpen = true;

    private Switch autoTestSwitch;
    private Button testBtn;
    private EditText ntcEdit;
    private Button ntcBtn;
    private EditText lowTempEdit;
    private Button lowBtn;
    private EditText highTempEdit;
    private Button highBtn;
    private TextView receiveText;

    private static final byte CMD_HEAD_ONE = (byte) 0xAA;
    private static final byte CMD_HEAD_TWO = (byte) 0xA5;
    private static final byte CMD_FOOT = 0x55;
    private static final byte CMD_TEST_TEMP = 0x01;
    private static final byte CMD_NTC_CALIBRATION = 0x02;
    private static final byte CMD_LOW_TEMP_CALIBRATION = 0x03;
    private static final byte CMD_HIGH_TEMP_CALIBRATION = 0x04;
    private static final byte CMD_AUTO_TEST = 0x05;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    private boolean repeatCalibration = false;
    private String receive = "";

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(INTENT_ACTION_GRANT_USB)) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        //Toast.makeText(getActivity(), "usbPermission == UsbPermission.Unknown" + (usbPermission == UsbPermission.Unknown), Toast.LENGTH_SHORT).show();
        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted) {
            mainLooper.post(this::connect);
        }
    }

    @Override
    public void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        initView(view);
        return view;
    }

    private void initView(View view) {
        autoTestSwitch = view.findViewById(R.id.auto_test_switch);
        testBtn = view.findViewById(R.id.test_btn);
        ntcEdit = view.findViewById(R.id.ntc_edit);
        ntcBtn = view.findViewById(R.id.ntc_btn);
        lowTempEdit = view.findViewById(R.id.low_edit);
        lowBtn = view.findViewById(R.id.low_btn);
        highTempEdit = view.findViewById(R.id.high_edit);
        highBtn = view.findViewById(R.id.high_btn);

        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        testBtn.setOnClickListener((v)->{
            testTemp();
        });

        ntcBtn.setOnClickListener((v)->{
            ntcCalibration();
        });

        lowBtn.setOnClickListener((v)->{
            lowTempCalibration();
        });

        highBtn.setOnClickListener((v)->{
            highTempCalibration();
        });

        autoTestSwitch.setOnClickListener((v) -> {
            autoTest();
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    /*
     * Serial + UI
     */
    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            Executors.newSingleThreadExecutor().submit(usbIoManager);
            status("connected");
            connected = true;
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if (usbIoManager != null)
            usbIoManager.stop();
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }


    private void testTemp() {
        byte[] data = {CMD_HEAD_ONE, CMD_HEAD_TWO, 0x03, CMD_TEST_TEMP, 0x04, CMD_FOOT};
        send(data);
    }

    private void ntcCalibration() {
        double temp = Double.parseDouble(ntcEdit.getText().toString());
        calibration(temp, CMD_NTC_CALIBRATION);
    }

    private void lowTempCalibration() {
        double temp = Double.parseDouble(lowTempEdit.getText().toString());
        calibration(temp, CMD_LOW_TEMP_CALIBRATION);
    }

    private void highTempCalibration() {
        double temp = Double.parseDouble(highTempEdit.getText().toString());
        calibration(temp, CMD_HIGH_TEMP_CALIBRATION);
    }


    //实际校准方法
    private void calibration(double temp, byte cmd) {
        //校准前需要关闭自动测试
        if (isOpen) {
            repeatCalibration = true;
            autoTest();
            return;
        }

        byte low, high, sum, packageLength = 0x05;

        int a = (int)(temp * 10);

        high = (byte)(a / 256);
        low = (byte)(a % 256);

        sum = (byte) (packageLength + cmd + high + low);

        byte[] data = {CMD_HEAD_ONE, CMD_HEAD_TWO, packageLength, cmd, high, low, sum, CMD_FOOT};
        send(data);
    }

    private void autoTest() {
        byte isAuto = (byte) (!isOpen ? 0x01 : 0x00);
        byte sum = (byte) (0x04 + CMD_AUTO_TEST + isAuto);
        byte[] data = {CMD_HEAD_ONE, CMD_HEAD_TWO, 0x04, CMD_AUTO_TEST, isAuto, sum, CMD_FOOT};
        send(data);
    }


    private void send(byte[] data) {
        if (!connected) {
            Toast.makeText(getActivity(), "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + data.length + " bytes\n");
            spn.append(HexDump.dumpHexString(data)+"\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            //receiveText.append(spn);

            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void receive(byte[] data) {
        SpannableStringBuilder spn = new SpannableStringBuilder();

        for(int i=0; i < data.length; i++){
            String hexString = "";
            if(data[i] < 0){
                hexString = Integer.toHexString(256 + data[i]).toUpperCase();
            } else {
                hexString = Integer.toHexString(data[i]).toUpperCase();
            }

            if(hexString.length() < 2){
                hexString = "0" + hexString;
            }

            receive += hexString;
        }

        int headIndex = receive.indexOf("AAA5");
        int footIndex = receive.indexOf("55");

        Log.e(TAG, "receive:" + receive + ", headIndex:" + headIndex + ", footIndex:" + footIndex);

        //只针对完整数据进行处理
        if(headIndex < footIndex){
            int packageLength = receive.charAt(headIndex + 4);
            int cmd = Integer.parseInt(receive.substring(headIndex + 6, headIndex+8), 16);
            Log.e(TAG, "cmd: " + cmd);
            switch (cmd){
                case CMD_TEST_TEMP:
                    double temp = Integer.parseInt(receive.substring(headIndex + 8, headIndex + 12), 16) / 10.0;

                    Log.e(TAG, "测温结果:" + temp);

                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");// HH:mm:ss
                    Date date = new Date(System.currentTimeMillis());
                    spn.append(simpleDateFormat.format(date) + "\t\t\t" + temp + "℃\n");
                    break;

                //校准结果
                case CMD_NTC_CALIBRATION:
                case CMD_LOW_TEMP_CALIBRATION:
                case CMD_HIGH_TEMP_CALIBRATION:
                    repeatCalibration = false;
                    int result = Integer.parseInt(receive.substring(headIndex + 8, headIndex + 10), 16);
                    handleCalibrationResult(cmd, result);
                    break;
                case CMD_AUTO_TEST:
                    if(receive.equals("AAA503050855")){
                        isOpen = !isOpen;
                        autoTestSwitch.setChecked(isOpen);
                        if(repeatCalibration){
                            ntcCalibration();
                        }
                    }
                    break;
            }

            receive = "";
        }

        receiveText.append(spn);
    }

    private void handleCalibrationResult(int cmd, int result) {
        SpannableStringBuilder spn = new SpannableStringBuilder();

        switch (result){
            case 0:
                //校准通过
                Log.e(TAG, "校准通过");
                if(cmd == CMD_NTC_CALIBRATION){
                    spn.append( "NTC校准通过\n");
                } else if(cmd == CMD_LOW_TEMP_CALIBRATION) {
                    spn.append("低温红外校准通过\n");
                } else if(cmd == CMD_HIGH_TEMP_CALIBRATION) {
                    spn.append("高温红外校准通过\n");
                }
                break;
            case 1:
                Log.e(TAG, "ERR 1 低于使用温度");
                spn.append("ERR 1 低于使用温度\n");
                break;
            case 2:
                Log.e(TAG, "ERR2 高于使用温度");
                spn.append("ERR2 高于使用温度\n");
                break;
            case 3:
                Log.e(TAG, "ERR3 NTC故障");
                spn.append( "ERR3 NTC故障\n");
                break;
            case 4:
                Log.e(TAG, "ERR4 红外故障");
                spn.append("ERR4 红外故障\n");
                break;
            case 5:
                Log.e(TAG, "ERR5 NTC校准失败，设备温度与环镜温度差超过2.5度");
                spn.append("ERR5 NTC校准失败，设备温度与环镜温度差超过2.5度 \n");
                break;
            case 6:
                Log.e(TAG, "ERR6 红外校准失败，传感器灵敏度过高");
                spn.append("ERR6 红外校准失败，传感器灵敏度过高\n");
                break;
            case 7:
                Log.e(TAG, "ERR7 红外校准失败，传感器灵敏度过低");
                spn.append("ERR7 红外校准失败，传感器灵敏度过低\n");
                break;
            case 8:
                Log.e(TAG, "ERR8 红外零点过大");
                spn.append("ERR8 红外零点过大\n");
                break;
        }
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    void status(String str) {
        //SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        //spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        //receiveText.append(spn);
    }
}
