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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQUEST_BLUETOOTH = 1001;

    /*
     * Bluetooth SIG
     *
     * Cycling Power Service
     * 0x1818
     */
    private static final UUID CYCLING_POWER_SERVICE_UUID =
            UUID.fromString(
                    "00001818-0000-1000-8000-00805F9B34FB"
            );

    /*
     * Cycling Power Measurement
     * 0x2A63
     */
    private static final UUID CYCLING_POWER_MEASUREMENT_UUID =
            UUID.fromString(
                    "00002A63-0000-1000-8000-00805F9B34FB"
            );

    /*
     * Client Characteristic Configuration Descriptor
     * 0x2902
     */
    private static final UUID CCCD_UUID =
            UUID.fromString(
                    "00002902-0000-1000-8000-00805F9B34FB"
            );

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;

    private BluetoothGattCharacteristic powerCharacteristic;
    private BluetoothGattDescriptor cccdDescriptor;

    /*
     * Dispositivos atualmente conectados.
     */
    private final List<BluetoothDevice> connectedDevices =
            new ArrayList<>();

    /*
     * Dispositivos que efetivamente habilitaram
     * notificações da Cycling Power Measurement.
     */
    private final Set<String> notificationDevices =
            new HashSet<>();

    private TextView statusText;
    private TextView connectionText;
    private TextView powerText;

    private EditText powerInput;

    private Button sendButton;
    private Button startButton;
    private Button stopButton;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    /*
     * Potência inicial.
     */
    private int currentPower = 100;

    private boolean running = false;

    private final Runnable powerRunnable =
            new Runnable() {

                @Override
                public void run() {

                    if (!running) {
                        return;
                    }

                    sendPowerMeasurement();

                    handler.postDelayed(
                            this,
                            1000
                    );
                }
            };

    @Override
    protected void onCreate(
            Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_main
        );

        statusText =
                findViewById(R.id.statusText);

        connectionText =
                findViewById(R.id.connectionText);

        powerText =
                findViewById(R.id.powerText);

        powerInput =
                findViewById(R.id.powerInput);

        sendButton =
                findViewById(R.id.sendButton);

        startButton =
                findViewById(R.id.startButton);

        stopButton =
                findViewById(R.id.stopButton);

        bluetoothManager =
                (BluetoothManager)
                        getSystemService(
                                Context.BLUETOOTH_SERVICE
                        );

        bluetoothAdapter =
                bluetoothManager.getAdapter();

        sendButton.setOnClickListener(
                v -> applyPower()
        );

        startButton.setOnClickListener(
                v -> startBle()
        );

        stopButton.setOnClickListener(
                v -> stopBle()
        );

        powerInput.setText(
                String.valueOf(currentPower)
        );

        powerText.setText(
                "Potência: "
                        + currentPower
                        + " W"
        );

        statusText.setText(
                "V2 pronta."
        );

        stopButton.setEnabled(false);

        updateConnectionText();
    }

    /*
     * ============================================================
     * POTÊNCIA
     * ============================================================
     */

    private void applyPower() {

        String value =
                powerInput
                        .getText()
                        .toString()
                        .trim();

        if (value.isEmpty()) {

            Toast.makeText(
                    this,
                    "Digite a potência.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        try {

            int watts =
                    Integer.parseInt(value);

            if (watts < -32768 ||
                    watts > 32767) {

                Toast.makeText(
                        this,
                        "Valor permitido: -32768 a 32767 W.",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            currentPower = watts;

            powerText.setText(
                    "Potência: "
                            + currentPower
                            + " W"
            );

            /*
             * Se o Bryton já estiver conectado,
             * envia imediatamente o novo valor.
             */
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

    /*
     * ============================================================
     * PERMISSÕES
     * ============================================================
     */

    private boolean hasBluetoothPermissions() {

        if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.S) {

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

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_BLUETOOTH
            );
        }
    }

    /*
     * ============================================================
     * INICIALIZAÇÃO BLE
     * ============================================================
     */

    private void startBle() {

        if (!hasBluetoothPermissions()) {

            requestBluetoothPermissions();

            return;
        }

        if (!getPackageManager()
                .hasSystemFeature(
                        PackageManager.FEATURE_BLUETOOTH_LE
                )) {

            statusText.setText(
                    "Bluetooth LE não disponível."
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
                    "Ative o Bluetooth."
            );

            return;
        }

        advertiser =
                bluetoothAdapter
                        .getBluetoothLeAdvertiser();

        if (advertiser == null) {

            statusText.setText(
                    "Este celular não suporta BLE Peripheral."
            );

            return;
        }

        statusText.setText(
                "Abrindo GATT Server..."
        );

        openGattServer();
    }

    /*
     * ============================================================
     * GATT SERVER
     * ============================================================
     */

    private void openGattServer() {

        if (gattServer != null) {
            closeGattServer();
        }

        notificationDevices.clear();

        gattServer =
                bluetoothManager.openGattServer(
                        this,
                        gattServerCallback
                );

        if (gattServer == null) {

            statusText.setText(
                    "Falha ao abrir GATT Server."
            );

            return;
        }

        BluetoothGattService service =
                new BluetoothGattService(
                        CYCLING_POWER_SERVICE_UUID,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY
                );

        /*
         * Cycling Power Measurement
         *
         * READ + NOTIFY
         */
        powerCharacteristic =
                new BluetoothGattCharacteristic(
                        CYCLING_POWER_MEASUREMENT_UUID,

                        BluetoothGattCharacteristic.PROPERTY_READ
                                |
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,

                        BluetoothGattCharacteristic.PERMISSION_READ
                );

        /*
         * CCCD
         */
        cccdDescriptor =
                new BluetoothGattDescriptor(
                        CCCD_UUID,

                        BluetoothGattDescriptor.PERMISSION_READ
                                |
                                BluetoothGattDescriptor.PERMISSION_WRITE
                );

        /*
         * Valor inicial do CCCD:
         * notificações desligadas.
         */
        cccdDescriptor.setValue(
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        );

        powerCharacteristic.addDescriptor(
                cccdDescriptor
        );

        service.addCharacteristic(
                powerCharacteristic
        );

        statusText.setText(
                "Adicionando Cycling Power Service..."
        );

        /*
         * IMPORTANTE:
         *
         * Não iniciamos o advertising aqui.
         *
         * Vamos esperar onServiceAdded().
         */
        boolean result =
                gattServer.addService(service);

        if (!result) {

            statusText.setText(
                    "Falha ao solicitar criação do serviço."
            );
        }
    }

    /*
     * ============================================================
     * ADVERTISING
     * ============================================================
     */

    private void startAdvertising() {

        if (advertiser == null) {
            return;
        }

        /*
         * Garante que um advertising anterior
         * não permaneça ativo.
         */
        try {

            advertiser.stopAdvertising(
                    advertiseCallback
            );

        } catch (Exception ignored) {
        }

        AdvertiseSettings settings =
                new AdvertiseSettings.Builder()
                        .setAdvertiseMode(
                                AdvertiseSettings
                                        .ADVERTISE_MODE_LOW_LATENCY
                        )
                        .setTxPowerLevel(
                                AdvertiseSettings
                                        .ADVERTISE_TX_POWER_HIGH
                        )
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();

        /*
         * Colocamos o UUID do Cycling Power Service
         * no advertising.
         */
        AdvertiseData data =
                new AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addServiceUuid(
                                new ParcelUuid(
                                        CYCLING_POWER_SERVICE_UUID
                                )
                        )
                        .build();

        statusText.setText(
                "Iniciando advertising..."
        );

        advertiser.startAdvertising(
                settings,
                data,
                advertiseCallback
        );
    }

    /*
     * ============================================================
     * CALLBACK ADVERTISING
     * ============================================================
     */

    private final AdvertiseCallback advertiseCallback =
            new AdvertiseCallback() {

                @Override
                public void onStartSuccess(
                        AdvertiseSettings settingsInEffect) {

                    running = true;

                    startButton.setEnabled(false);
                    stopButton.setEnabled(true);

                    statusText.setText(
                            "Virtual Power V2 ativo."
                    );

                    handler.removeCallbacks(
                            powerRunnable
                    );

                    handler.post(
                            powerRunnable
                    );
                }

                @Override
                public void onStartFailure(
                        int errorCode) {

                    running = false;

                    statusText.setText(
                            "Falha no advertising. Código: "
                                    + errorCode
                    );
                }
            };

    /*
     * ============================================================
     * GATT CALLBACK
     * ============================================================
     */

    private final BluetoothGattServerCallback
            gattServerCallback =
            new BluetoothGattServerCallback() {

                /*
                 * ------------------------------------------------
                 * SERVIÇO ADICIONADO
                 * ------------------------------------------------
                 */

                @Override
                public void onServiceAdded(
                        int status,
                        BluetoothGattService service) {

                    runOnUiThread(() -> {

                        if (status ==
                                BluetoothGatt.GATT_SUCCESS) {

                            statusText.setText(
                                    "Cycling Power Service criado."
                            );

                            /*
                             * SOMENTE AGORA iniciamos
                             * o advertising.
                             */
                            startAdvertising();

                        } else {

                            statusText.setText(
                                    "Erro GATT ao criar serviço: "
                                            + status
                            );
                        }
                    });
                }

                /*
                 * ------------------------------------------------
                 * CONEXÃO
                 * ------------------------------------------------
                 */

                @Override
                public void onConnectionStateChange(
                        BluetoothDevice device,
                        int status,
                        int newState) {

                    if (newState ==
                            BluetoothProfile.STATE_CONNECTED) {

                        if (!connectedDevices
                                .contains(device)) {

                            connectedDevices.add(
                                    device
                            );
                        }

                        runOnUiThread(() ->
                                statusText.setText(
                                        "Bryton/dispositivo conectado."
                                )
                        );

                    } else if (
                            newState ==
                                    BluetoothProfile
                                            .STATE_DISCONNECTED) {

                        connectedDevices.remove(
                                device
                        );

                        notificationDevices.remove(
                                device.getAddress()
                        );

                        runOnUiThread(() ->
                                statusText.setText(
                                        "Dispositivo desconectado."
                                )
                        );
                    }

                    runOnUiThread(
                            MainActivity.this
                                    ::updateConnectionText
                    );
                }

                /*
                 * ------------------------------------------------
                 * LEITURA DA POWER MEASUREMENT
                 * ------------------------------------------------
                 */

                @Override
                public void onCharacteristicReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattCharacteristic characteristic) {

                    if (!CYCLING_POWER_MEASUREMENT_UUID
                            .equals(
                                    characteristic.getUuid()
                            )) {

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt
                                        .GATT_REQUEST_NOT_SUPPORTED,
                                offset,
                                null
                        );

                        return;
                    }

                    byte[] packet =
                            buildPowerPacket(
                                    currentPower
                            );

                    if (offset > packet.length) {

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt
                                        .GATT_INVALID_OFFSET,
                                offset,
                                null
                        );

                        return;
                    }

                    byte[] response =
                            new byte[
                                    packet.length - offset
                            ];

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

                /*
                 * ------------------------------------------------
                 * LEITURA DO CCCD
                 * ------------------------------------------------
                 */

                @Override
                public void onDescriptorReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattDescriptor descriptor) {

                    if (!CCCD_UUID.equals(
                            descriptor.getUuid()
                    )) {

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt
                                        .GATT_REQUEST_NOT_SUPPORTED,
                                offset,
                                null
                        );

                        return;
                    }

                    byte[] value =
                            notificationDevices.contains(
                                    device.getAddress()
                            )
                                    ? BluetoothGattDescriptor
                                    .ENABLE_NOTIFICATION_VALUE
                                    : BluetoothGattDescriptor
                                            .DISABLE_NOTIFICATION_VALUE;

                    if (offset > value.length) {

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt
                                        .GATT_INVALID_OFFSET,
                                offset,
                                null
                        );

                        return;
                    }

                    byte[] response =
                            new byte[
                                    value.length - offset
                            ];

                    System.arraycopy(
                            value,
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

                /*
                 * ------------------------------------------------
                 * ESCRITA DO CCCD
                 * ------------------------------------------------
                 */

                @Override
                public void onDescriptorWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattDescriptor descriptor,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {

                    if (!CCCD_UUID.equals(
                            descriptor.getUuid()
                    )) {

                        if (responseNeeded) {

                            gattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt
                                            .GATT_REQUEST_NOT_SUPPORTED,
                                    offset,
                                    null
                            );
                        }

                        return;
                    }

                    boolean enableNotify =
                            value != null
                                    &&
                                    value.length >= 2
                                    &&
                                    value[0] == 0x01
                                    &&
                                    value[1] == 0x00;

                    boolean disableNotify =
                            value != null
                                    &&
                                    value.length >= 2
                                    &&
                                    value[0] == 0x00
                                    &&
                                    value[1] == 0x00;

                    if (enableNotify) {

                        notificationDevices.add(
                                device.getAddress()
                        );

                        cccdDescriptor.setValue(
                                BluetoothGattDescriptor
                                        .ENABLE_NOTIFICATION_VALUE
                        );

                        runOnUiThread(() ->
                                statusText.setText(
                                        "Power Notify habilitado."
                                )
                        );

                    } else if (disableNotify) {

                        notificationDevices.remove(
                                device.getAddress()
                        );

                        cccdDescriptor.setValue(
                                BluetoothGattDescriptor
                                        .DISABLE_NOTIFICATION_VALUE
                        );

                        runOnUiThread(() ->
                                statusText.setText(
                                        "Power Notify desabilitado."
                                )
                        );
                    }

                    if (responseNeeded) {

                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                null
                        );
                    }
                }

                /*
                 * ------------------------------------------------
                 * NOTIFICAÇÃO ENVIADA
                 * ------------------------------------------------
                 */

                @Override
                public void onNotificationSent(
                        BluetoothDevice device,
                        int status) {

                    if (status ==
                            BluetoothGatt.GATT_SUCCESS) {

                        runOnUiThread(() ->
                                powerText.setText(
                                        "Transmitindo: "
                                                + currentPower
                                                + " W"
                                )
                        );

                    } else {

                        runOnUiThread(() ->
                                statusText.setText(
                                        "Erro ao enviar Power Measurement: "
                                                + status
                                )
                        );
                    }
                }
            };

    /*
     * ============================================================
     * PACOTE CYCLING POWER MEASUREMENT
     * ============================================================
     *
     * Flags:
     * 0x0000
     *
     * Instantaneous Power:
     * signed 16-bit little endian
     *
     * Total:
     * 4 bytes
     */

    private byte[] buildPowerPacket(
            int watts) {

        ByteBuffer buffer =
                ByteBuffer
                        .allocate(4)
                        .order(
                                ByteOrder.LITTLE_ENDIAN
                        );

        /*
         * Flags = 0x0000
         */
        buffer.putShort(
                (short) 0
        );

        /*
         * Instantaneous Power
         */
        buffer.putShort(
                (short) watts
        );

        return buffer.array();
    }

    /*
     * ============================================================
     * ENVIO
     * ============================================================
     */

    private void sendPowerMeasurement() {

        if (!running) {
            return;
        }

        if (gattServer == null ||
                powerCharacteristic == null) {

            return;
        }

        byte[] packet =
                buildPowerPacket(
                        currentPower
                );

        powerCharacteristic.setValue(
                packet
        );

        /*
         * Só enviamos para dispositivos que
         * efetivamente habilitaram Notify.
         */
        for (BluetoothDevice device :
                new ArrayList<>(
                        connectedDevices
                )) {

            if (!notificationDevices.contains(
                    device.getAddress()
            )) {

                continue;
            }

            if (Build.VERSION.SDK_INT >= 33) {

                gattServer
                        .notifyCharacteristicChanged(
                                device,
                                powerCharacteristic,
                                false,
                                packet
                        );

            } else {

                powerCharacteristic.setValue(
                        packet
                );

                gattServer
                        .notifyCharacteristicChanged(
                                device,
                                powerCharacteristic,
                                false
                        );
            }
        }
    }

    /*
     * ============================================================
     * INTERFACE
     * ============================================================
     */

    private void updateConnectionText() {

        connectionText.setText(
                "Clientes conectados: "
                        + connectedDevices.size()
                        + " | Notify: "
                        + notificationDevices.size()
        );
    }

    /*
     * ============================================================
     * PARAR
     * ============================================================
     */

    private void stopBle() {

        running = false;

        handler.removeCallbacks(
                powerRunnable
        );

        if (advertiser != null &&
                hasBluetoothPermissions()) {

            try {

                advertiser.stopAdvertising(
                        advertiseCallback
                );

            } catch (Exception ignored) {
            }
        }

        closeGattServer();

        connectedDevices.clear();
        notificationDevices.clear();

        updateConnectionText();

        startButton.setEnabled(true);
        stopButton.setEnabled(false);

        statusText.setText(
                "Virtual Power V2 parado."
        );
    }

    private void closeGattServer() {

        if (gattServer != null) {

            try {
                gattServer.clearServices();
            } catch (Exception ignored) {
            }

            try {
                gattServer.close();
            } catch (Exception ignored) {
            }

            gattServer = null;
        }
    }

    /*
     * ============================================================
     * CICLO DE VIDA
     * ============================================================
     */

    @Override
    protected void onDestroy() {

        stopBle();

        super.onDestroy();
    }

    /*
     * ============================================================
     * PERMISSÕES
     * ============================================================
     */

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

        if (requestCode ==
                REQUEST_BLUETOOTH) {

            if (hasBluetoothPermissions()) {

                statusText.setText(
                        "Permissões concedidas. "
                                + "Toque em INICIAR BLE POWER METER."
                );

            } else {

                statusText.setText(
                        "Permissões Bluetooth necessárias "
                                + "não foram concedidas."
                );
            }
        }
    }
}
