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

import android.annotation.SuppressLint;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import androidx.core.content.ContextCompat;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ObdLiveData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Classic Bluetooth SPP transport. Socket lifecycle is isolated from the ELM protocol parser. */
public class BtCommService extends CommService
{
	private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private static final long CONNECT_TIMEOUT_MS = 20_000;
	private final BtTransportSession session;
	private final Handler uiHandler;
	private String deviceName;

	BtCommService(Context context, Handler handler)
	{
		super(context, handler);
		uiHandler = handler;
		session = new BtTransportSession(this, elm, new BtTransportSession.Listener() {
			@Override public void connected() {
				connectionEstablished(deviceName);
			}
			@Override public void failed(BtTransportSession.Failure failure) {
				int text;
				switch (failure) {
					case PERMISSION: text = R.string.obd_bt_permission_required; break;
					case TIMEOUT: text = R.string.obd_bt_connect_timeout; break;
					case STREAMS: text = R.string.obd_bt_stream_failed; break;
					default: text = R.string.obd_bt_connect_failed;
				}
				reportFailure(text);
			}
			@Override public void disconnected() {
				reportFailure(R.string.obd_bt_connection_lost);
			}
		}, BtTransportSession.SCHEDULER, CONNECT_TIMEOUT_MS, 500, buffer -> {
			int result = elm.handleTelegram(buffer);
			ObdLiveData.onTelegram(buffer);
			return result;
		});
	}

	@Override public synchronized void start()
	{
		session.stop();
		setState(STATE.LISTEN);
	}

	@SuppressLint("MissingPermission")
	@Override public synchronized void connect(Object device, boolean secure)
	{
		session.stop();
		if (!(device instanceof BluetoothDevice)) {
			reportFailure(R.string.obd_bt_connect_failed);
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT)
				!= PackageManager.PERMISSION_GRANTED) {
			reportFailure(R.string.obd_bt_permission_required);
			return;
		}
		try {
			BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
			if (adapter == null || !adapter.isEnabled()) {
				reportFailure(R.string.obd_bt_unavailable);
				return;
			}
			// Discovery is optional for an already known device; do not require scan permission here.
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
					|| ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_SCAN)
					== PackageManager.PERMISSION_GRANTED) adapter.cancelDiscovery();
			BluetoothDevice target = (BluetoothDevice) device;
			deviceName = target.getName();
			if (deviceName == null) deviceName = mContext.getString(R.string.obd_bt_unknown_device);
			setState(STATE.CONNECTING);
			session.connect(fallback -> createSocket(target, secure, fallback));
		} catch (IllegalStateException ex) {
			reportFailure(R.string.obd_bt_unavailable);
		} catch (SecurityException ex) {
			reportFailure(R.string.obd_bt_permission_required);
		}
	}

	@Override public synchronized void stop()
	{
		session.stop();
		setState(STATE.OFFLINE);
	}

	@Override public synchronized void write(byte[] out)
	{
		session.write(out);
	}

	private void reportFailure(int text)
	{
		session.stop();
		setState(STATE.OFFLINE);
		Message message = uiHandler.obtainMessage(MainActivity.MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(MainActivity.TOAST, mContext.getString(text));
		message.setData(bundle);
		message.sendToTarget();
	}

	@SuppressLint("MissingPermission")
	private static BtTransportSession.Socket createSocket(BluetoothDevice device, boolean secure,
	                                                      boolean fallback) throws IOException
	{
		BluetoothSocket socket;
		if (!fallback) {
			socket = secure ? device.createRfcommSocketToServiceRecord(SPP_UUID)
					: device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
		} else {
			// Retain the existing channel-1 workaround for adapters with broken SDP implementations.
			try {
				Method method = device.getClass().getMethod("createRfcommSocket", Integer.TYPE);
				socket = (BluetoothSocket) method.invoke(device, 1);
			} catch (InvocationTargetException ex) {
				Throwable cause = ex.getCause();
				if (cause instanceof SecurityException) throw (SecurityException) cause;
				throw new IOException("Clone adapter RFCOMM fallback failed", cause);
			} catch (ReflectiveOperationException | ClassCastException ex) {
				throw new IOException("Clone adapter RFCOMM fallback unavailable", ex);
			}
		}
		if (socket == null) throw new IOException("Bluetooth socket creation returned null");
		return new BtTransportSession.Socket() {
			@Override public void connect() throws IOException { socket.connect(); }
			@Override public InputStream input() throws IOException { return socket.getInputStream(); }
			@Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
			@Override public void close() throws IOException { socket.close(); }
		};
	}
}
