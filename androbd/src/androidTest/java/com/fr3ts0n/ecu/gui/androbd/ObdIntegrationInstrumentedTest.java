package com.fr3ts0n.ecu.gui.androbd;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdEvidence;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdPidSample;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.AudioResultActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.AudioResultViewModel;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ObdLiveData;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real production transport + ELM decoder + collector + fusion + Activity, with a test-only socket. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class ObdIntegrationInstrumentedTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    private interface Check { boolean ready(); }
    private ObdEvidence evidence() { return ObdLiveData.INSTANCE.getState().getValue(); }
    private int display() { return Integer.parseInt(InstrumentationRegistry.getArguments().getString("displayId", "0")); }
    private Bundle options() {
        assertTrue("OBD UI tests require an isolated display", display() > 0);
        ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(display()); return options.toBundle();
    }
    private Intent result(String system) {
        String context = "{\"make\":\"\",\"model\":\"\",\"year\":null,\"fuel_type\":\"petrol\",\"cylinder_count\":3,\"transmission\":\"manual\",\"mileage_km\":null,\"fuel_system\":\"" + system + "\",\"power_steering_type\":\"\",\"engine_temp_state\":\"warm\",\"trigger_conditions\":[\"idle\"],\"location\":\"\",\"safety_blocked\":false}";
        return new Intent(app, AudioResultActivity.class).putExtra(AudioResultActivity.EXTRA_CONTEXT, context)
                .putStringArrayListExtra(AudioResultActivity.EXTRA_SYMPTOMS, new ArrayList<>(Collections.singletonList("belt_squeal")));
    }
    private AudioResultViewModel model(ActivityScenario<AudioResultActivity> scenario) {
        final AudioResultViewModel[] value = new AudioResultViewModel[1];
        scenario.onActivity(activity -> value[0] = new ViewModelProvider(activity).get(AudioResultViewModel.class)); return value[0];
    }
    private void await(Check check) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        boolean done = check.ready();
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20);
            done = check.ready();
        }
        assertTrue("Expected OBD state not reached", done);
    }

    @Test public void mockSocketFeedsActualPidsDtcsFusionAndDisconnectFallsBackWithoutCrash() throws Exception {
        ObdLiveData.modeChanged("OFFLINE");
        int originalUnits = EcuDataItem.cnvSystem;
        try (Adapter fixture = new Adapter(); ActivityScenario<AudioResultActivity> scenario = ActivityScenario.launch(result("injection"), options())) {
            AudioResultViewModel vm = model(scenario);
            await(() -> !vm.getState().getValue().getBusy());
            assertFalse(vm.getState().getValue().getObdAttached());
            assertTrue(vm.getState().getValue().getResult().getCauses().get(0).getId().startsWith("belt_squeal."));
            fixture.connect();
            EcuDataItem.cnvSystem = EcuDataItem.SYSTEM_IMPERIAL;
            CommService.elm.setService(0, false); CommService.elm.setService(1, true);
            fixture.send("4100BE3EB810");
            fixture.send("410100070000");
            for (int i = 0; i < 3; i++) {
                fixture.send("410C0C80"); fixture.send("41057D"); fixture.send("41030200");
                fixture.send("41069A"); fixture.send("41078D");
                SystemClock.sleep(550);
            }
            await(() -> evidence().getSamples().stream().filter(p -> p.getMnemonic().equals("fuel_trim_short_b1")).count() >= 3);
            ObdPidSample rpm = evidence().getSamples().stream().filter(p -> p.getMnemonic().equals("engine_speed")).findFirst().get();
            ObdPidSample coolant = evidence().getSamples().stream().filter(p -> p.getMnemonic().equals("engine_coolant_temperature")).findFirst().get();
            assertEquals(800.0, rpm.getValue(), 0.01); assertEquals(85.0, coolant.getValue(), 0.01);
            assertFalse(evidence().getSamples().stream().anyMatch(p -> p.getMnemonic().equals("fuel_trim_short_b2")));
            int count = evidence().getSamples().size();
            fixture.send("410C00"); fixture.send("NO DATA");
            assertEquals(count, evidence().getSamples().size());
            CommService.elm.setService(0, false); CommService.elm.setService(3, true);
            // Standard stored DTC and a reconstructed multi-line reply through the unchanged parser.
            fixture.send("4303010000");
            await(() -> evidence().getCodes().stream().anyMatch(c -> c.getCode().equals("P0301")));
            fixture.send("005"); fixture.send("0:4303010171");
            await(() -> evidence().getCodes().stream().anyMatch(c -> c.getCode().equals("P0171")));
            await(() -> !vm.getState().getValue().getBusy() && vm.getState().getValue().getObd().getConnected());
            scenario.onActivity(activity -> activity.findViewById(R.id.result_obd_use).performClick());
            confirmVehicleDialog();
            await(() -> !vm.getState().getValue().getBusy() && vm.getState().getValue().getObdUsed());
            assertFalse(vm.getState().getValue().getResult().getCauses().get(0).getId().startsWith("belt_squeal."));
            assertTrue(vm.getState().getValue().getResult().getCauses().stream().anyMatch(c -> c.getReason().contains("P0301") || c.getReason().contains("P0171")));
            assertTrue(vm.getState().getValue().getResult().getCauses().stream().allMatch(c -> c.getProbability() == null));
            scenario.onActivity(activity -> {
                assertTrue(((TextView) activity.findViewById(R.id.result_obd_dtcs)).getText().toString().contains("gagal pembakaran"));
                ((ScrollView) activity.findViewById(R.id.result_scroll)).scrollTo(0, activity.findViewById(R.id.result_obd_panel).getTop());
            });
            capture(scenario, "phase7-mock-obd.png");
            scenario.onActivity(activity -> ((ScrollView) activity.findViewById(R.id.result_scroll)).scrollTo(0,
                    activity.findViewById(R.id.result_obd_panel).getTop() + activity.findViewById(R.id.result_obd_dtcs).getTop()));
            capture(scenario, "phase7-dtcs.png");
            scenario.onActivity(activity -> ((ScrollView) activity.findViewById(R.id.result_scroll)).scrollTo(0, 0));
            capture(scenario, "phase7-obd-reasons.png");
            long oldSession = evidence().getSessionId();
            fixture.socket.get().disconnect();
            await(() -> !evidence().getConnected() && !vm.getState().getValue().getBusy() && !vm.getState().getValue().getObdAttached());
            assertFalse(vm.getState().getValue().getObdUsed());
            assertTrue(vm.getState().getValue().getResult().getCauses().get(0).getId().startsWith("belt_squeal."));
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertTrue(((TextView) activity.findViewById(R.id.result_obd_status)).getText().toString().contains("terputus"));
                ((ScrollView) activity.findViewById(R.id.result_scroll)).scrollTo(0, activity.findViewById(R.id.result_obd_panel).getTop());
            });
            capture(scenario, "phase7-disconnected.png");
            fixture.connect();
            assertTrue(evidence().getSessionId() > oldSession);
            assertTrue(evidence().getCodes().isEmpty()); assertTrue(evidence().getSamples().isEmpty());
            assertFalse(vm.getState().getValue().getObdAttached());
        } finally {
            EcuDataItem.cnvSystem = originalUnits;
            CommService.elm.setService(0, true); ObdLiveData.modeChanged("OFFLINE");
        }
    }

    @Test public void carburetorKeepsManualGuidanceWithoutAdapterOrRecording() throws Exception {
        ObdLiveData.modeChanged("OFFLINE");
        try (ActivityScenario<AudioResultActivity> scenario = ActivityScenario.launch(result("carburetor"), options())) {
            AudioResultViewModel vm = model(scenario); await(() -> !vm.getState().getValue().getBusy());
            assertNotNull(vm.getState().getValue().getResult()); assertFalse(vm.canUseObd());
            assertFalse(vm.getState().getValue().getObdAttached()); assertFalse(vm.getState().getValue().getCanFeedback());
            assertTrue(vm.getState().getValue().getResult().getCauses().get(0).getId().startsWith("belt_squeal."));
            scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.result_obd_use).isEnabled());
                assertTrue(((TextView) activity.findViewById(R.id.result_obd_status)).getText().toString().contains("karburator"));
                ((ScrollView) activity.findViewById(R.id.result_scroll)).scrollTo(0, activity.findViewById(R.id.result_obd_panel).getTop());
            });
            capture(scenario, "phase7-no-obd.png");
        }
    }

    @Test public void rejectedAudioCanUseIndependentObdWithoutLosingStopWarning() throws Exception {
        ObdLiveData.modeChanged("OFFLINE");
        Intent request = result("injection").putExtra(AudioResultActivity.EXTRA_REJECTED, true)
                .putExtra(AudioResultActivity.EXTRA_SAFETY_BLOCKED, true);
        try (Adapter fixture = new Adapter(); ActivityScenario<AudioResultActivity> scenario = ActivityScenario.launch(request, options())) {
            AudioResultViewModel vm = model(scenario);
            await(() -> !vm.getState().getValue().getBusy());
            assertEquals("RECORDING_REJECTED", vm.getState().getValue().getResult().getOutcome().name());
            fixture.connect();
            CommService.elm.setService(0, false); CommService.elm.setService(3, true);
            fixture.send("4303010000");
            await(() -> evidence().getCodes().stream().anyMatch(c -> c.getCode().equals("P0301")));
            await(() -> !vm.getState().getValue().getBusy() && vm.getState().getValue().getObd().getConnected());
            scenario.onActivity(activity -> activity.findViewById(R.id.result_obd_use).performClick());
            confirmVehicleDialog();
            await(() -> !vm.getState().getValue().getBusy() && vm.getState().getValue().getObdUsed());
            assertEquals("EXPERT_ONLY", vm.getState().getValue().getResult().getOutcome().name());
            assertTrue(vm.getState().getValue().getResult().getStopWarning());
            assertTrue(vm.getState().getValue().getObdNotes().stream().anyMatch(note -> note.contains("audio ditolak")));
            fixture.socket.get().disconnect();
            await(() -> !vm.getState().getValue().getBusy() && !vm.getState().getValue().getObdAttached());
            assertEquals("RECORDING_REJECTED", vm.getState().getValue().getResult().getOutcome().name());
            assertTrue(vm.getState().getValue().getResult().getStopWarning());
        } finally { CommService.elm.setService(0, true); ObdLiveData.modeChanged("OFFLINE"); }
    }

    private void confirmVehicleDialog() {
        UiAutomation ui = instrumentation.getUiAutomation(); AccessibilityServiceInfo previous = ui.getServiceInfo();
        AccessibilityServiceInfo info = ui.getServiceInfo(); info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; ui.setServiceInfo(info);
        try {
            await(() -> {
                List<AccessibilityWindowInfo> windows = ui.getWindowsOnAllDisplays().get(display());
                if (windows == null) return false;
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot(); if (root == null) continue;
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(app.getString(R.string.obd_confirm_use))) {
                        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    }
                }
                return false;
            });
        } finally { ui.setServiceInfo(previous); }
    }
    private void capture(ActivityScenario<AudioResultActivity> scenario, String name) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(activity -> activity.getWindow().getDecorView().postOnAnimation(() ->
                activity.getWindow().getDecorView().postOnAnimation(frame::countDown)));
        assertTrue(frame.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync(); final Bitmap[] bitmap = new Bitmap[1]; final int[] status = {-1}; CountDownLatch ready = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            bitmap[0] = Bitmap.createBitmap(activity.getWindow().getDecorView().getWidth(), activity.getWindow().getDecorView().getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), bitmap[0], result -> { status[0] = result; ready.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status[0]);
        try (FileOutputStream out = new FileOutputStream(new File(app.getCacheDir(), name))) { assertTrue(bitmap[0].compress(Bitmap.CompressFormat.PNG, 100, out)); }
        finally { bitmap[0].recycle(); }
    }
    private final class Adapter implements AutoCloseable {
        final AtomicReference<MockSocket> socket = new AtomicReference<>(); final AtomicReference<Throwable> failure = new AtomicReference<>();
        final BtTransportSession session = new BtTransportSession(new Object(), CommService.elm, new BtTransportSession.Listener() {
            @Override public void connected() { ObdLiveData.transportChanged("CONNECTED", "BLUETOOTH"); ObdLiveData.ecuSelected(0x7E8); }
            @Override public void failed(BtTransportSession.Failure reason) { failure.set(new AssertionError(reason)); ObdLiveData.transportChanged("OFFLINE", "BLUETOOTH"); }
            @Override public void disconnected() { ObdLiveData.transportChanged("OFFLINE", "BLUETOOTH"); }
        }, BtTransportSession.SCHEDULER, 2000, 0, frame -> { int result = CommService.elm.handleTelegram(frame); ObdLiveData.onTelegram(frame); return result; });
        void connect() throws Exception {
            MockSocket next = new MockSocket(); socket.set(next); session.connect(fallback -> next);
            await(() -> evidence().getConnected() || failure.get() != null); assertNull(failure.get());
        }
        void send(String frame) throws Exception { socket.get().feed.write((frame + "\r").getBytes(StandardCharsets.US_ASCII)); socket.get().feed.flush(); SystemClock.sleep(35); }
        @Override public void close() { session.stop(); }
    }
    private static final class MockSocket implements BtTransportSession.Socket {
        final PipedInputStream input = new PipedInputStream(8192); final PipedOutputStream feed;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        MockSocket() throws IOException { feed = new PipedOutputStream(input); }
        @Override public void connect() { /* Test peer is already present. */ }
        @Override public InputStream input() { return input; }
        @Override public OutputStream output() { return output; }
        void disconnect() throws IOException { feed.close(); }
        @Override public void close() throws IOException { feed.close(); input.close(); }
    }
}
