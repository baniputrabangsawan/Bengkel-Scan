package com.fr3ts0n.ecu.gui.androbd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The .obd reader is fed by any app that sends a VIEW intent. Android's deserialization shim
 * ignores resolveClass (measured), so the bound that can actually be enforced is the size cap;
 * this checks that a real measurement still loads within it and that an endless stream is cut.
 */
@RunWith(AndroidJUnit4.class)
public class MeasurementStreamInstrumentedTest
{
	/** Same guard the loader installs, reachable here without touching the activity. */
	private static ObjectInputStream guarded(InputStream raw) throws IOException
	{
		return FileHelper.guardedStreamForTest(raw);
	}

	@Test
	public void savedMeasurementRoundTrips() throws Exception
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ObjectOutputStream out = new ObjectOutputStream(bytes);
		out.writeInt(3);
		out.writeObject(new com.fr3ts0n.pvs.PvList());
		out.writeObject(new com.fr3ts0n.pvs.PvList());
		out.writeObject(new com.fr3ts0n.pvs.PvList());
		out.writeObject(new com.fr3ts0n.pvs.PvList());
		out.close();

		try (ObjectInputStream in = guarded(new ByteArrayInputStream(bytes.toByteArray())))
		{
			assertEquals(3, in.readInt());
			assertTrue(in.readObject() instanceof com.fr3ts0n.pvs.PvList);
		}
	}

	@Test
	public void oversizedStreamIsCut() throws Exception
	{
		byte[] big = new byte[1024];
		java.util.Arrays.fill(big, (byte) 'x');
		boolean cut = false;
		try (InputStream in = FileHelper.limitForTest(new ByteArrayInputStream(big), 128))
		{
			byte[] buffer = new byte[256];
			while (in.read(buffer) >= 0)
			{
				// keep reading until the cap trips
			}
		}
		catch (IOException expected)
		{
			cut = true;
		}
		assertTrue("the size cap must stop an endless stream", cut);
	}
}
