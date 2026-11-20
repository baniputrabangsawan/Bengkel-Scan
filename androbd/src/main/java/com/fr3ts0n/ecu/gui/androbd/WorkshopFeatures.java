package com.fr3ts0n.ecu.gui.androbd;

/** Build-time UI switches; never exposed as mechanic-facing preferences. */
final class WorkshopFeatures
{
    static final boolean SHOW_ADVANCED_FEATURES = false;
    static final boolean SHOW_ACTUATOR_TEST_CONTROLS = false;
    /**
     * Clearing is offered again now that every clearing writes an audit line and
     * the codes about to be destroyed are captured first. It still requires the
     * explicit destructive confirmation dialog; this flag only shows the menu.
     */
    static final boolean SHOW_CLEAR_FAULT_CODES = true;

    private WorkshopFeatures()
    {
    }

    /** Preference key the mechanic can switch, unlike the build-time flags above. */
    static final String PREF_ADVANCED_MODE = "advanced_mode";

    static boolean showMenuItem(int id)
    {
        return showMenuItem(id, false);
    }

    /**
     * @param advancedMode the mechanic's own switch, read from preferences by the
     *                     caller; build-time flags still win for risky actions
     */
    static boolean showMenuItem(int id, boolean advancedMode)
    {
        // Actuator tests are never exposed, whatever the mechanic switched on.
        if (id == R.id.service_testcontrol) return SHOW_ACTUATOR_TEST_CONTROLS;
        if (id == R.id.service_clearcodes) return SHOW_CLEAR_FAULT_CODES;
        if (id == R.id.save || id == R.id.load || id == R.id.day_night_mode
                || id == R.id.plugin_manager || id == R.id.reset_preselections)
            return advancedMode || SHOW_ADVANCED_FEATURES;
        return true;
    }
}
