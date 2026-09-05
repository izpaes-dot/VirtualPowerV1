package br.com.virtualpower.v1;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQUEST_BLUETOOTH = 1001;

    private static final UUID CYCLING_POWER_SERVICE_UUID =
            UUID.fromString("00001818-0000-1000-8000-00805F9B34FB");

    private static final UUID CYCLING_POWER_MEASUREMENT_UUID =
            UUID.fromString("00002A63-0000-1000-8000-00805F9B34FB");

    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;

    private BluetoothGattCharacteristic powerCharacteristic;

    private final List<BluetoothDevice> connectedDevices = new ArrayList<>();

    private TextView statusText;
    private TextView connectionText;
    private TextView powerText;

    private EditText powerInput;

    private Button sendButton;
    private Button startButton;
    private Button stopButton;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int currentPower = 200;

    private boolean running = false;

    private final Runnable powerRunnable = new Runnable() {

        @Override
        public void run() {

            if (!running) {
                return;
            }

            sendPowerMeasurement();

            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        connectionText = findViewById(R.id.connectionText);
        powerText = findViewById(R.id.powerText);

        powerInput = findViewById(R.id.powerInput);

        sendButton = findViewById(R.id.sendButton);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        bluetoothAdapter = bluetoothManager.getAdapter();

        sendButton.setOnClickListener(v -> applyPower());

        startButton.setOnClickListener(v -> startBle());

        stopButton.setOnClickListener(v -> stopBle());

        statusText.setText("Pronto para iniciar.");

        updateConnectionText();
    }

    private void applyPower() {

        String value = powerInput.getText().toString().trim();

        if (value.isEmpty()) {

            Toast.makeText(
                    this,
                    "Digite a potência.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        try {

            int watts = Integer.parseInt(value);

            if (watts < -32768 || watts > 32767) {

                Toast.makeText(
                        this,
                        "Valor permitido: -32768 a 32767 W.",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            currentPower = watts;

            powerText.setText(
                    "Potência transmitida: " + currentPower + " W"
            );

            if (running) {
                sendPowerMeasurement();
            }

        } catch (NumberFormatException e) {

            Toast.makeText(
                    this,
                    "Valor inválido.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private boolean hasBluetoothPermissions() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }

        return checkSelfPermission(
                Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
                &&
                checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_BLUETOOTH
            );
        }
    }

    private void startBle() {

        if (!hasBluetoothPermissions()) {

            requestBluetoothPermissions();

            return;
        }

        if (!getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE)) {

            statusText.setText(
                    "Este aparelho não possui Bluetooth LE."
            );

            return;
        }

        if (bluetoothAdapter == null) {

            statusText.setText(
                    "Bluetooth não disponível."
            );

            return;
        }

        if (!bluetoothAdapter.isEnabled()) {

            statusText.setText(
                    "Ative o Bluetooth do celular."
            );

            return;
        }

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();

        if (advertiser == null) {

            statusText.setText(
                    "Este celular não suporta BLE Peripheral/Advertising."
            );

            return;
        }

        openGattServer();
    }

    private void openGattServer() {

        if (gattServer != null) {
            stopBle();
        }

        gattServer = bluetoothManager.openGattServer(
                this,
                gattServerCallback
        );

        if (gattServer == null) {

            statusText.setText(
                    "Não foi possível abrir o GATT Server."
            );

            return;
        }

        BluetoothGattService service =
                new BluetoothGattService(
                        CYCLING_POWER_SERVICE_UUID,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY
                );

        powerCharacteristic =
                new BluetoothGattCharacteristic(
                        CYCLING_POWER_MEASUREMENT_UUID,

                        BluetoothGattCharacteristic.PROPERTY_READ
                                |
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,

                        BluetoothGattCharacteristic.PERMISSION_READ
                );

        BluetoothGattDescriptor cccd =
                new BluetoothGattDescriptor(
                        CCCD_UUID,

                        BluetoothGattDescriptor.PERMISSION_READ
                                |
                                BluetoothGattDescriptor.PERMISSION_WRITE
                );

        powerCharacteristic.addDescriptor(cccd);

        service.addCharacteristic(powerCharacteristic);

        boolean added = gattServer.addService(service);

        if (!added) {

            statusText.setText(
                    "Falha ao adicionar o serviço BLE."
            );

            return;
        }

        statusText.setText(
                "GATT Server iniciado. Aguardando Bryton..."
        );

        startAdvertising();
    }

    private void startAdvertising() {

        if (advertiser == null) {
            return;
        }

        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(
                                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                        )
                        .setTxPowerLevel(
                                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                        )
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();

        AdvertiseData data =
                new AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addServiceUuid(
                                new ParcelUuid(
                                        CYCLING_POWER_SERVICE_UUID
                                )
                        )
                        .build();

        advertiser.startAdvertising(
                settings,
                data,
                advertiseCallback
        );
    }

    private final AdvertiseCallback advertiseCallback =
            new AdvertiseCallback() {

                @Override
                public void onStartSuccess(
                        AdvertiseSettings settingsInEffect) {

                    running = true;

                    startButton.setEnabled(false);
                    stopButton.setEnabled(true);

                    statusText.setText(
                            "BLE Power Meter ativo. Procurável pelo Bryton."
                    );

                    handler.removeCallbacks(powerRunnable);

                    handler.post(powerRunnable);
                }

                @Override
                public void onStartFailure(int errorCode) {

                    running = false;

                    statusText.setText(
                            "Falha no Advertising BLE. Código: "
                                    + errorCode
                    );
                }
            };

    private final BluetoothGattServerCallback gattServerCallback =
            new BluetoothGattServerCallback() {

                @Override
                public void onServiceAdded(
                        int status,
                        BluetoothGattService service) {

                    runOnUiThread(() -> {

                        if (status == BluetoothGatt.GATT_SUCCESS) {

                            statusText.setText(
                                    "Serviço Cycling Power criado."
                            );

                        } else {

                            statusText.setText(
                                    "Erro ao criar serviço. Código: "
                                            + status
                            );
                        }
                    });
                }

                @Override
                public void onConnectionStateChange(
                        BluetoothDevice device,
                        int status,
                        int newState) {

                    if (newState == BluetoothProfile.STATE_CONNECTED) {

                        if (!connectedDevices.contains(device)) {
                            connectedDevices.add(device);
                        }

                    } else if (
                            newState ==
                                    BluetoothProfile.STATE_DISCONNECTED) {

                        connectedDevices.remove(device);
                    }

                    runOnUiThread(
                            MainActivity.this::updateConnectionText
                    );
                }

                @Override
                public void onCharacteristicReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattCharacteristic characteristic) {

                    if (powerCharacteristic.equals(characteristic)) {

                        byte[] packet = buildPowerPacket(currentPower);

                        if (offset > packet.length) {

                            gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_INVALID_OFFSET,
                                    offset,
                                    null
                            );

                            return;
                        }

                        byte[] response =
                                new byte[packet.length - offset];

                        System.arraycopy(
                                packet,
                                offset,
                                response,
                                0,
                                response.length
                        );

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                response
                        );
                    }
                }

                @Override
                public void onDescriptorWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattDescriptor descriptor,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {

                    if (CCCD_UUID.equals(descriptor.getUuid())) {

                        if (responseNeeded) {

                            gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_SUCCESS,
                                    0,
                                    null
                            );
                        }

                        return;
                    }

                    if (responseNeeded) {

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                0,
                                null
                        );
                    }
                }
            };

    private byte[] buildPowerPacket(int watts) {

        ByteBuffer buffer =
                ByteBuffer
                        .allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN);

        // Flags = 0x0000
        buffer.putShort((short) 0);

        // Instantaneous Power = signed 16-bit
        buffer.putShort((short) watts);

        return buffer.array();
    }

    private void sendPowerMeasurement() {

        if (!running) {
            return;
        }

        if (gattServer == null ||
                powerCharacteristic == null) {
            return;
        }

        byte[] packet =
                buildPowerPacket(currentPower);

        powerCharacteristic.setValue(packet);

        for (BluetoothDevice device :
                new ArrayList<>(connectedDevices)) {

            if (Build.VERSION.SDK_INT >= 33) {

                gattServer.notifyCharacteristicChanged(
                        device,
                        powerCharacteristic,
                        false,
                        packet
                );

            } else {

                powerCharacteristic.setValue(packet);

                gattServer.notifyCharacteristicChanged(
                        device,
                        powerCharacteristic,
                        false
                );
            }
        }
    }

    private void updateConnectionText() {

        connectionText.setText(
                "Clientes conectados: "
                        + connectedDevices.size()
        );
    }

    private void stopBle() {

        running = false;

        handler.removeCallbacks(powerRunnable);

        if (advertiser != null &&
                hasBluetoothPermissions()) {

            try {

                advertiser.stopAdvertising(
                        advertiseCallback
                );

            } catch (Exception ignored) {
            }
        }

        if (gattServer != null) {

            try {
                gattServer.close();
            } catch (Exception ignored) {
            }

            gattServer = null;
        }

        connectedDevices.clear();

        updateConnectionText();

        startButton.setEnabled(true);
        stopButton.setEnabled(false);

        statusText.setText(
                "BLE Power Meter parado."
        );
    }

    @Override
    protected void onDestroy() {

        stopBle();

        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQUEST_BLUETOOTH) {

            if (hasBluetoothPermissions()) {

                statusText.setText(
                        "Permissões concedidas. Toque em INICIAR BLE POWER METER."
                );

            } else {

                statusText.setText(
                        "Permissões Bluetooth necessárias não foram concedidas."
                );
            }
        }
    }
}
