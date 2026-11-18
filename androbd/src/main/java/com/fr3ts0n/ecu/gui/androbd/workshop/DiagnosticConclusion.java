package com.fr3ts0n.ecu.gui.androbd.workshop;

/** Technician-authored confidence, not an app-certified repair result. */
public enum DiagnosticConclusion
{
	ECU_INDICATION, HYPOTHESIS, CONFIRMED;

	/** Strict parsing never upgrades unknown data into a confirmed diagnosis. */
	public static DiagnosticConclusion fromStored(String value)
	{
		if (value == null) throw new IllegalArgumentException("diagnostic.conclusion.required");
		return valueOf(value);
	}
}
