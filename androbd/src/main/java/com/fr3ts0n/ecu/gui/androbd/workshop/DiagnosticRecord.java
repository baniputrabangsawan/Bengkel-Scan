package com.fr3ts0n.ecu.gui.androbd.workshop;

/** Immutable, scanner-independent evidence for one service and module. */
public final class DiagnosticRecord
{
	public final long serviceId;
	public final DiagnosticModule module;
	public final DiagnosticReadStatus readStatus;
	public final DiagnosticConclusion conclusion;
	public final String scanner;
	public final String codes;
	public final String symptoms;
	public final String observations;
	public final String testProcedure;
	public final String testResult;
	public final String reference;
	public final String diagnosis;
	public final String repair;
	public final String verification;
	public final long updatedAt;

	public DiagnosticRecord(long serviceId, DiagnosticModule module,
			DiagnosticReadStatus readStatus, DiagnosticConclusion conclusion, String scanner,
			String codes, String symptoms, String observations, String testProcedure,
			String testResult, String reference, String diagnosis, String repair,
			String verification, long updatedAt)
	{
		this.serviceId = serviceId;
		this.module = module;
		this.readStatus = readStatus;
		this.conclusion = conclusion;
		this.scanner = text(scanner);
		this.codes = text(codes);
		this.symptoms = text(symptoms);
		this.observations = text(observations);
		this.testProcedure = text(testProcedure);
		this.testResult = text(testResult);
		this.reference = text(reference);
		this.diagnosis = text(diagnosis);
		this.repair = text(repair);
		this.verification = text(verification);
		this.updatedAt = updatedAt;
	}

	private static String text(String value)
	{
		// Keep the technician's entered formatting and notes, even for failed reads.
		return value == null ? "" : value;
	}
}
