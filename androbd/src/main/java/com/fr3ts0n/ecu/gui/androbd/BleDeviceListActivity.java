package com.fr3ts0n.ecu.gui.androbd;

import android.annotation.SuppressLint;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.logging.Level;

/** BLE shares the base permission/result lifecycle but does not start classic discovery or bonding. */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class BleDeviceListActivity extends BtDeviceListActivity
{
    private BluetoothLeScanner leScanner;
    private ScanCallback activeScan;

    @SuppressLint("MissingPermission")
    @Override protected void startDeviceScan() {
        if (mBtAdapter == null) { scanFailed(R.string.obd_bt_unavailable); return; }
        leScanner = mBtAdapter.getBluetoothLeScanner();
        if (leScanner == null) { scanFailed(R.string.obd_bt_unavailable); return; }
        // A callback belongs to one scan only; late results from a stopped scan are discarded.
        ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (activeScan == this) addDevice(result.getDevice());
            }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                if (activeScan != this) return;
                for (ScanResult result : results) addDevice(result.getDevice());
            }
            @Override public void onScanFailed(int errorCode) {
                if (activeScan == this) scanFailed(R.string.obd_bt_scan_failed);
            }
        };
        activeScan = callback;
        leScanner.startScan(callback);
    }

    @SuppressLint("MissingPermission")
    @Override protected void stopDeviceScan() {
        ScanCallback callback = activeScan;
        activeScan = null;
        if (leScanner == null || callback == null) return;
        try { leScanner.stopScan(callback); }
        catch (SecurityException ex) { log.log(Level.FINE, "BLE scan permission revoked", ex); }
        catch (IllegalStateException ex) { log.log(Level.FINE, "Bluetooth adapter stopped", ex); }
        finally { leScanner = null; }
    }
}
