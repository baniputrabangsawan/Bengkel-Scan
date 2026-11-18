package com.fr3ts0n.ecu.gui.androbd.workshop;

/** A technician-selected module, not a claim of scanner connectivity. */
public enum DiagnosticModule
{
	ENGINE, SRS, TRANSMISSION, ABS, BODY, OTHER;

	/** Reject unknown persisted values instead of silently selecting a module. */
	public static DiagnosticModule fromStored(String value)
	{
		if (value == null) throw new IllegalArgumentException("diagnostic.module.required");
		return valueOf(value);
	}
}
