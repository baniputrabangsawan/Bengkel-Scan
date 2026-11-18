/*
 * Copyright 2026 Bengkel Scan contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticModule;

/** Presentation only: module names never imply scanner coverage. */
final class DiagnosticUi
{
	static final String EXTRA_MODULE = "diagnostic.module";

	private DiagnosticUi() { }

	static int moduleLabel(DiagnosticModule module)
	{
		switch (module)
		{
			case ENGINE: return R.string.diagnostic_module_engine;
			case SRS: return R.string.diagnostic_module_srs;
			case TRANSMISSION: return R.string.diagnostic_module_transmission;
			case ABS: return R.string.diagnostic_module_abs;
			case BODY: return R.string.diagnostic_module_body;
			case OTHER: return R.string.diagnostic_module_other;
			default: throw new IllegalArgumentException("Unknown diagnostic module");
		}
	}
}
