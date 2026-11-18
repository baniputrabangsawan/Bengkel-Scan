package com.fr3ts0n.ecu.gui.androbd.workshop;

/** Missing, unsupported and failed reads never imply a healthy module. */
public enum DiagnosticReadStatus
{
	NOT_CHECKED, NOT_SUPPORTED, READ, READ_FAILED;

	/** Strict parsing: an unknown value has no safe fallback. */
	public static DiagnosticReadStatus fromStored(String value)
	{
		if (value == null) throw new IllegalArgumentException("diagnostic.read_status.required");
		return valueOf(value);
	}
}
