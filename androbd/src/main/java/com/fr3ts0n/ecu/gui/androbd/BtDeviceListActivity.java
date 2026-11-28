/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Lists paired and discovered adapters; classic devices are paired through the Android OS dialog. */
public class BtDeviceListActivity extends AppCompatActivity
{
	static final String TAG = BtDeviceListActivity.class.getSimpleName();
	protected static final Logger log = Logger.getLogger(TAG);
	public static final String EXTRA_DEVICE_ADDRESS = "device_address";
	private static final int PERMISSIONS_REQUEST = 1;
	private static final int SCAN_MENU = Menu.FIRST;
	private static final long SCAN_DURATION_MS = 15_000;
	private static final long PAIR_DURATION_MS = 60_000;

	protected BluetoothAdapter mBtAdapter;
	protected final List<BluetoothDevice> mDevices = new ArrayList<>();
	protected DeviceAdapter mDeviceAdapter;
	protected TextView mEmptyStateView;
	protected LinearProgressIndicator mScanProgress;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private boolean started;
	private boolean receiverRegistered;
	private boolean permissionRequestInFlight;
	private boolean permissionsRequested;
	private BluetoothDevice pendingPair;
	private long pairStartedAt;
	private AlertDialog permissionDialog;
	private final Runnable scanDeadline = this::stopScanning;
	private final Runnable pairDeadline = () -> {
		pendingPair = null;
		scanFailed(R.string.obd_bt_pair_failed);
	};

	@Override protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		setResult(RESULT_CANCELED);
		setContentView(R.layout.device_list);
		setTitle(this instanceof BleDeviceListActivity ? R.string.select_ble_device : R.string.select_device);
		ModernUiUtils.applyScreenInsets(this);
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
		RecyclerView list = findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(this));
		mDeviceAdapter = new DeviceAdapter();
		list.setAdapter(mDeviceAdapter);
		mEmptyStateView = findViewById(R.id.empty_state);
		mEmptyStateView.setText(R.string.obd_bt_no_devices);
		mEmptyStateView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		mScanProgress = findViewById(R.id.scan_progress);
		mScanProgress.setContentDescription(getString(R.string.obd_bt_scanning));
		updateEmptyState();
	}

	@Override protected void onStart()
	{
		super.onStart();
		started = true;
		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		// Bluetooth broadcasts originate from a privileged process outside the app UID.
		ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
		receiverRegistered = true;
		if (pendingPair != null) resumePairing();
		else beginScan(false);
	}

	@Override protected void onStop()
	{
		started = false;
		stopScanning();
		handler.removeCallbacks(pairDeadline);
		if (receiverRegistered) {
			unregisterReceiver(receiver);
			receiverRegistered = false;
		}
		super.onStop();
	}

	@Override protected void onDestroy()
	{
		handler.removeCallbacksAndMessages(null);
		if (permissionDialog != null) permissionDialog.dismiss();
		super.onDestroy();
	}

	@Override public boolean onCreateOptionsMenu(Menu menu)
	{
		menu.add(Menu.NONE, SCAN_MENU, Menu.NONE, R.string.obd_bt_scan_retry)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		return true;
	}

	@Override public boolean onOptionsItemSelected(@NonNull MenuItem item)
	{
		if (item.getItemId() == SCAN_MENU) {
			pendingPair = null;
			handler.removeCallbacks(pairDeadline);
			beginScan(true);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private ArrayList<String> missingPermissions()
	{
		ArrayList<String> missing = new ArrayList<>();
		String[] required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
				: new String[]{Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
				? Manifest.permission.ACCESS_FINE_LOCATION : Manifest.permission.ACCESS_COARSE_LOCATION};
		for (String permission : required) {
			if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
				missing.add(permission);
		}
		return missing;
	}

	@SuppressLint("MissingPermission")
	private void beginScan(boolean explicitRetry)
	{
		if (!started || permissionRequestInFlight) return;
		if (mBtAdapter == null) { scanFailed(R.string.obd_bt_unavailable); return; }
		ArrayList<String> missing = missingPermissions();
		if (!missing.isEmpty()) {
			stopScanning();
			if (explicitRetry || !permissionsRequested) {
				permissionsRequested = true;
				permissionRequestInFlight = true;
				ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSIONS_REQUEST);
			} else scanFailed(R.string.obd_bt_permission_required);
			return;
		}
		try {
			if (mBtAdapter == null || !mBtAdapter.isEnabled()) {
				scanFailed(R.string.obd_bt_unavailable);
				return;
			}
			stopScanning();
			mDevices.clear();
			mDeviceAdapter.notifyDataSetChanged();
			mEmptyStateView.setText(R.string.obd_bt_scanning);
			updateEmptyState();
			mScanProgress.setVisibility(View.VISIBLE);
			startDeviceScan();
			if (mScanProgress.getVisibility() != View.VISIBLE) return;
			handler.postDelayed(scanDeadline, SCAN_DURATION_MS);
		} catch (SecurityException ex) {
			scanFailed(R.string.obd_bt_permission_required);
		} catch (IllegalStateException ex) {
			scanFailed(R.string.obd_bt_unavailable);
		}
	}

	@Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
	                                                 @NonNull int[] grantResults)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != PERMISSIONS_REQUEST) return;
		permissionRequestInFlight = false;
		if (!started) return;
		if (missingPermissions().isEmpty()) beginScan(false);
		else {
			scanFailed(R.string.obd_bt_permission_required);
			if (permissionDialog == null || !permissionDialog.isShowing()) {
				permissionDialog = new AlertDialog.Builder(this)
						.setMessage(R.string.obd_bt_permission_required)
						.setPositiveButton(R.string.obd_bt_settings, (dialog, which) ->
								startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
										Uri.parse("package:" + getPackageName()))))
						.setNeutralButton(R.string.obd_bt_scan_retry, (dialog, which) -> beginScan(true))
						.setNegativeButton(R.string.obd_bt_back, (dialog, which) -> finish())
						.show();
			}
		}
	}

	protected void addDevice(BluetoothDevice device)
	{
		if (!started || device == null) return;
		int index = mDevices.indexOf(device);
		if (index < 0) {
			mDevices.add(device);
			mDeviceAdapter.notifyItemInserted(mDevices.size() - 1);
		} else mDeviceAdapter.notifyItemChanged(index);
		updateEmptyState();
	}

	protected void updateEmptyState()
	{
		mEmptyStateView.setVisibility(mDevices.isEmpty() ? View.VISIBLE : View.GONE);
	}

	protected final void scanFailed(int message)
	{
		stopScanning();
		pendingPair = null;
		handler.removeCallbacks(pairDeadline);
		mEmptyStateView.setText(message);
		updateEmptyState();
		if (started) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
	}

	private void stopScanning()
	{
		handler.removeCallbacks(scanDeadline);
		stopDeviceScan();
		mScanProgress.setVisibility(View.GONE);
		if (mDevices.isEmpty()) mEmptyStateView.setText(R.string.obd_bt_no_devices);
	}

	@SuppressLint("MissingPermission")
	protected void onDeviceSelected(BluetoothDevice device)
	{
		if (!missingPermissions().isEmpty()) { beginScan(true); return; }
		handler.removeCallbacks(pairDeadline);
		pendingPair = null;
		stopScanning();
		try {
			// BLE keeps its existing GATT selection behavior; OS bonding is for classic SPP devices.
			if (this instanceof BleDeviceListActivity || device.getBondState() == BluetoothDevice.BOND_BONDED) {
				returnDevice(device);
				return;
			}
			pendingPair = device;
			pairStartedAt = SystemClock.elapsedRealtime();
			if (device.getBondState() == BluetoothDevice.BOND_NONE && !device.createBond()) {
				pendingPair = null;
				scanFailed(R.string.obd_bt_pair_failed);
				return;
			}
			Toast.makeText(this, R.string.obd_bt_pair_wait, Toast.LENGTH_LONG).show();
			handler.postDelayed(pairDeadline, PAIR_DURATION_MS);
		} catch (SecurityException ex) {
			pendingPair = null;
			scanFailed(R.string.obd_bt_permission_required);
		}
	}

	@SuppressLint("MissingPermission")
	private void resumePairing()
	{
		handler.removeCallbacks(pairDeadline);
		try {
			if (!missingPermissions().isEmpty()) {
				pendingPair = null;
				scanFailed(R.string.obd_bt_permission_required);
			} else if (pendingPair.getBondState() == BluetoothDevice.BOND_BONDED) returnDevice(pendingPair);
			else if (pendingPair.getBondState() == BluetoothDevice.BOND_NONE) {
				pendingPair = null;
				scanFailed(R.string.obd_bt_pair_failed);
			} else handler.postDelayed(pairDeadline,
					Math.max(0, PAIR_DURATION_MS - (SystemClock.elapsedRealtime() - pairStartedAt)));
		} catch (SecurityException ex) {
			pendingPair = null;
			scanFailed(R.string.obd_bt_permission_required);
		}
	}

	private void returnDevice(BluetoothDevice device)
	{
		Intent intent = new Intent();
		intent.putExtra(EXTRA_DEVICE_ADDRESS, device.getAddress());
		pendingPair = null;
		handler.removeCallbacks(pairDeadline);
		setResult(RESULT_OK, intent);
		finish();
	}

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override public void onReceive(Context context, Intent intent) {
			if (!started) return;
			String action = intent.getAction();
			if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (!(BtDeviceListActivity.this instanceof BleDeviceListActivity)) {
					try {
						if (mBtAdapter == null || !mBtAdapter.isDiscovering())
							mScanProgress.setVisibility(View.GONE);
					} catch (SecurityException ex) {
						scanFailed(R.string.obd_bt_permission_required);
					}
				}
			} else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
						!= BluetoothAdapter.STATE_ON) scanFailed(R.string.obd_bt_unavailable);
			} else {
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					if (!(BtDeviceListActivity.this instanceof BleDeviceListActivity)) addDevice(device);
				} else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && device != null) {
					int index = mDevices.indexOf(device);
					if (index >= 0) mDeviceAdapter.notifyItemChanged(index);
					if (device.equals(pendingPair)) resumePairing();
				}
			}
		}
	};

	protected static class DeviceViewHolder extends RecyclerView.ViewHolder {
		final TextView text;
		DeviceViewHolder(@NonNull View itemView) {
			super(itemView);
			text = (TextView) itemView;
		}
	}

	protected class DeviceAdapter extends RecyclerView.Adapter<DeviceViewHolder> {
		@NonNull @Override public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.device_name, parent, false);
			view.setFocusable(true);
			return new DeviceViewHolder(view);
		}

		@SuppressLint("MissingPermission")
		@Override public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
			BluetoothDevice device = mDevices.get(position);
			try {
				String name = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? device.getAlias() : null;
				if (name == null || name.isEmpty()) name = device.getName();
				if (name == null || name.isEmpty()) name = getString(R.string.obd_bt_unknown_device);
				int bond = device.getBondState();
				int status = bond == BluetoothDevice.BOND_BONDED ? R.string.obd_bt_paired
						: bond == BluetoothDevice.BOND_BONDING ? R.string.obd_bt_pairing : R.string.obd_bt_not_paired;
				holder.text.setText(getString(R.string.obd_bt_device_entry, name, device.getAddress(), getString(status)));
				holder.itemView.setOnClickListener(view -> onDeviceSelected(device));
			} catch (SecurityException ex) {
				holder.text.setText(R.string.obd_bt_permission_required);
				holder.itemView.setOnClickListener(view -> beginScan(true));
			}
		}

		@Override public int getItemCount() { return mDevices.size(); }
	}

	@SuppressLint("MissingPermission")
	protected void startDeviceScan()
	{
		if (mBtAdapter == null) { scanFailed(R.string.obd_bt_unavailable); return; }
		for (BluetoothDevice device : mBtAdapter.getBondedDevices()) addDevice(device);
		if (!mBtAdapter.startDiscovery()) scanFailed(R.string.obd_bt_scan_failed);
	}

	@SuppressLint("MissingPermission")
	protected void stopDeviceScan()
	{
		if (mBtAdapter == null) return;
		try { mBtAdapter.cancelDiscovery(); }
		catch (SecurityException ex) { log.log(Level.FINE, "Bluetooth scan permission revoked", ex); }
		catch (IllegalStateException ex) { log.log(Level.FINE, "Bluetooth adapter stopped", ex); }
	}
}
