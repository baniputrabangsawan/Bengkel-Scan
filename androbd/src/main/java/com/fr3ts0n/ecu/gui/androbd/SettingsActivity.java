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
 *
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.util.Locale;

import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;

import java.util.HashSet;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SettingsActivity extends AppCompatActivity
	implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback
{
	static final String EXTRA_PREFERENCE_ROOT = "workshop_preference_root";
	// Preference keys referenced from outside this class (MainActivity, ObdItemAdapter) -
	// kept at this level, unlike the Settings-internal-only keys below, so those external
	// call sites don't need to change.
	static final String[] extKeys =
	{
		"ext_file_conversions",
		"ext_file_dataitems"
	};
	static final String KEY_DATA_ITEMS = "data_items";
	static final String KEY_PROT_SELECT = "protocol";
	static final String KEY_COMM_MEDIUM = "comm_medium";
	static final String ELM_MIN_TIMEOUT = "elm_min_timeout";
	static final String ELM_CMD_DISABLE = "elm_cmd_disable";
	private static final String KEY_APP_LANGUAGE = "app_language";

	/**
	 * Apply locale based on user preference
	 *
	 * @param activity Activity context
	 */
	public static void applyLocale(Activity activity)
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
		String language = prefs.getString(KEY_APP_LANGUAGE, "id");
		if (!prefs.getBoolean("workshop_language_initialized", false))
		{
			if ("system".equals(language)) language = "id";
			prefs.edit().putString(KEY_APP_LANGUAGE, language)
				.putBoolean("workshop_language_initialized", true).apply();
		}
		Locale locale = "system".equals(language)
			? Resources.getSystem().getConfiguration().locale : new Locale(language);
		Locale.setDefault(locale);

		Resources resources = activity.getResources();
		Configuration config = resources.getConfiguration();
		config.setLocale(locale);

		resources.updateConfiguration(config, resources.getDisplayMetrics());
	}

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		// Apply locale before calling super.onCreate()
		applyLocale(this);
		super.onCreate(savedInstanceState);
		ModernUiUtils.applyScreenInsets(this);
		setTitle(R.string.settings);

		if (savedInstanceState == null)
		{
			SettingsFragment fragment = new SettingsFragment();
			String root = getIntent().getStringExtra(EXTRA_PREFERENCE_ROOT);
			if ("screen_app_info".equals(root) || "screen_obd_options".equals(root))
			{
				Bundle arguments = new Bundle();
				arguments.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, root);
				fragment.setArguments(arguments);
			}
			getSupportFragmentManager()
				.beginTransaction()
				.replace(android.R.id.content, fragment)
				.commit();
		}
	}

	/**
	 * Called when the user taps a nested {@code PreferenceScreen} (e.g. "OBD Options",
	 * "CSV Export Options"). AndroidX Preference does not navigate to sub-screens on its
	 * own - the hosting activity has to swap in a new {@link SettingsFragment} rooted at
	 * the tapped screen's key, or the tap silently does nothing.
	 */
	@Override
	public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref)
	{
		SettingsFragment fragment = new SettingsFragment();
		Bundle args = new Bundle();
		args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.getKey());
		fragment.setArguments(args);

		getSupportFragmentManager()
			.beginTransaction()
			.replace(android.R.id.content, fragment)
			.addToBackStack(pref.getKey())
			.commit();
		return true;
	}

	/**
	 * The actual preference screen - AndroidX preference dialogs/screens are hosted in a
	 * fragment rather than directly in an Activity, unlike the old {@code PreferenceActivity}
	 * API this was originally written against.
	 */
	public static class SettingsFragment
		extends
			PreferenceFragmentCompat
		implements
			SharedPreferences.OnSharedPreferenceChangeListener,
			Preference.OnPreferenceClickListener
	{
		/** The logger object */
		private static final Logger log = Logger.getLogger(SettingsFragment.class.getName());

		/**
		 * app preferences
		 */
		private SharedPreferences prefs;

		/**
		 * key ids for device network settings
		 */
		private static final String[] networkKeys =
		{
			"device_address",
			"device_port"
		};
		/**
		 * key ids for device network settings
		 */
		private static final String[] bluetoothKeys =
		{
			"bt_secure_connection"
		};
		/**
		 * key ids for device network settings
		 */
		private static final String[] usbKeys =
		{
				"comm_baudrate"
		};

		static final String ELM_TIMING_SELECT = "adaptive_timing_mode";
		private static final String KEY_BITCOIN = "bitcoin";
		private static final String KEY_OPEN_LOG_DIR = "open_log_dir";

		Vector<EcuDataItem> items;

		@Override
		public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey)
		{
			prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

			if (!WorkshopFeatures.SHOW_ADVANCED_FEATURES
					&& ("screen_development_options".equals(rootKey)
					|| "screen_csv_export_options".equals(rootKey)))
				rootKey = null;
			setPreferencesFromResource(R.xml.settings, rootKey);

			for (String key : extKeys)
			{
				setPrefsText(key);
			}

			// set up communication media selection
			setupCommMediaSelection();
			// set up protocol selection
			setupProtoSelection();
			// set up ELM command selection
			setupElmCmdSelection();
			// set up ELM adaptive timing mode selection
			setupElmTimingSelection();
			// set up selectable PID list
			setupPidSelection();
			// update network selection fields
			updateNetworkSelections();
			setPrefClickListener(KEY_BITCOIN);
			setPrefClickListener(KEY_OPEN_LOG_DIR);
			confirmAdvancedMode();

			if (!WorkshopFeatures.SHOW_ADVANCED_FEATURES)
			{
				for (String key : new String[] {"screen_development_options",
						"screen_csv_export_options", "USE_LAST_SETTINGS"})
				{
					Preference hidden = findPreference(key);
					if (hidden != null) hidden.setVisible(false);
				}
				for (String key : new String[] {"elm_custom_init_cmds", "autohide_toolbar"})
				{
					Preference hidden = findPreference(key);
					if (hidden != null && hidden.getParent() != null)
						hidden.getParent().setVisible(false);
				}
			}
		}

		@Override
		public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
		{
			super.onViewCreated(view, savedInstanceState);
			requireActivity().setTitle(getPreferenceScreen().getTitle());
			// One padding rule and a visible divider, so a long settings list keeps its rhythm.
			if (view instanceof android.view.ViewGroup)
			{
				androidx.recyclerview.widget.RecyclerView list =
						((android.view.ViewGroup) view).findViewById(androidx.preference.R.id.recycler_view);
				if (list != null)
				{
					int pad = getResources().getDimensionPixelSize(R.dimen.ui_screen_padding_horizontal);
					list.setPadding(pad, pad, pad, pad);
					list.setClipToPadding(false);
				}
			}
		}


		/**
		 * Advanced mode is a mechanic-facing switch, so turning it on asks first.
		 *
		 * <p>The switch is held off while the dialog is open: only an explicit
		 * confirmation writes {@code true}. Turning it off needs no ceremony.</p>
		 */
		private void confirmAdvancedMode()
		{
			Preference found = findPreference(WorkshopFeatures.PREF_ADVANCED_MODE);
			if (!(found instanceof androidx.preference.SwitchPreferenceCompat)) return;
			final androidx.preference.SwitchPreferenceCompat switcher =
					(androidx.preference.SwitchPreferenceCompat) found;
			switcher.setOnPreferenceChangeListener((preference, newValue) ->
			{
				if (!Boolean.TRUE.equals(newValue)) return true;
				new android.app.AlertDialog.Builder(requireContext())
						.setTitle(R.string.advanced_mode_confirm_title)
						.setMessage(R.string.advanced_mode_confirm_message)
						.setNegativeButton(R.string.advanced_mode_cancel,
								(dialog, which) -> android.widget.Toast.makeText(requireContext(),
										R.string.advanced_mode_denied,
										android.widget.Toast.LENGTH_SHORT).show())
						.setPositiveButton(R.string.advanced_mode_on, (dialog, which) ->
						{
							prefs.edit().putBoolean(WorkshopFeatures.PREF_ADVANCED_MODE, true).apply();
							switcher.setChecked(true);
							android.widget.Toast.makeText(requireContext(),
									R.string.advanced_mode_enabled,
									android.widget.Toast.LENGTH_SHORT).show();
						})
						.show();
				// Returning false refuses the automatic switch-on; the confirmation
				// button writes the value itself.
				return false;
			});
			switcher.setChecked(prefs.getBoolean(WorkshopFeatures.PREF_ADVANCED_MODE, false));
		}

		/**
		 * Set this fragment as the click listener for a preference, if it exists in the
		 * currently displayed (sub-)screen. findPreference() only searches the tree rooted
		 * at whichever screen setPreferencesFromResource() was given - a key that lives in
		 * a different top-level PreferenceScreen won't be found, which is expected rather
		 * than an error.
		 */
		private void setPrefClickListener(String key)
		{
			Preference pref = findPreference(key);
			if (pref != null)
			{
				pref.setOnPreferenceClickListener(this);
			}
		}

		@Override
		public void onResume()
		{
			super.onResume();
			// add handler for selection update
			prefs.registerOnSharedPreferenceChangeListener(this);
		}

		@Override
		public void onPause()
		{
			prefs.unregisterOnSharedPreferenceChangeListener(this);
			super.onPause();
		}

		@Override
		public void onDisplayPreferenceDialog(@NonNull Preference preference)
		{
			if (preference instanceof SearchableMultiSelectListPreference)
			{
				SearchableMultiSelectListPreferenceDialogFragment fragment =
					SearchableMultiSelectListPreferenceDialogFragment.newInstance(preference.getKey());
				fragment.setTargetFragment(this, 0);
				fragment.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
				return;
			}
			super.onDisplayPreferenceDialog(preference);
		}

		/**
		 * set up protocol selection
		 */
		void setupProtoSelection()
		{
			ListPreference pref = (ListPreference) findPreference(KEY_PROT_SELECT);
			if (pref == null) return;
			ElmProt.PROT[] values = ElmProt.PROT.values();
			CharSequence[] titles = new CharSequence[values.length];
			CharSequence[] keys = new CharSequence[values.length];
			int i = 0;
			for (ElmProt.PROT proto : values)
			{
				titles[i] = proto.toString();
				keys[i] = String.valueOf(proto.ordinal());
				i++;
			}
			// set enries and keys
			pref.setEntries(titles);
			pref.setEntryValues(keys);
			pref.setDefaultValue(titles[0]);
			// show current selection
			pref.setSummary(pref.getEntry());
		}

		/**
		 * set up protocol selection
		 */
		void setupElmTimingSelection()
		{
			ListPreference pref = (ListPreference) findPreference(ELM_TIMING_SELECT);
			if (pref == null) return;
			ElmProt.AdaptTimingMode[] values = ElmProt.AdaptTimingMode.values();
			CharSequence[] titles = new CharSequence[values.length];
			CharSequence[] keys = new CharSequence[values.length];
			int i = 0;
			for (ElmProt.AdaptTimingMode mode : values)
			{
				titles[i] = mode.toString();
				keys[i] = mode.toString();
				i++;
			}
			// set entries and keys
			pref.setEntries(titles);
			pref.setEntryValues(keys);
			pref.setDefaultValue(titles[0]);
			// show current selection
			pref.setSummary(pref.getEntry());
		}

		/**
		 * set up protocol selection
		 */
		void setupElmCmdSelection()
		{
			MultiSelectListPreference pref =
				(MultiSelectListPreference) findPreference(ELM_CMD_DISABLE);
			if (pref == null) return;
			ElmProt.CMD[] values = ElmProt.CMD.values();
			HashSet<String> selections = new HashSet<>();
			CharSequence[] titles = new CharSequence[values.length];
			CharSequence[] keys = new CharSequence[values.length];
			int i = 0;
			for (ElmProt.CMD cmd : values)
			{
				titles[i] = cmd.toString();
				keys[i] = cmd.toString();
				if(!cmd.isEnabled()) selections.add(cmd.toString());
				i++;
			}
			// set enries and keys
			pref.setEntries(titles);
			pref.setEntryValues(keys);
			pref.setValues(selections);
		}

		/**
		 * set up protocol selection
		 */
		void setupCommMediaSelection()
		{
			ListPreference pref = (ListPreference) findPreference(KEY_COMM_MEDIUM);
			if (pref == null) return;
			CommService.MEDIUM[] values = CommService.MEDIUM.values();
			CharSequence[] titles = new CharSequence[values.length];
			String[] mediumLabels = getResources().getStringArray(R.array.comm_medium_labels);
			CharSequence[] keys = new CharSequence[values.length];
			int i = 0;
			for (CommService.MEDIUM proto : values)
			{
				titles[i] = mediumLabels[proto.ordinal()];
				keys[i] = String.valueOf(proto.ordinal());
				i++;
			}
			// set enries and keys
			pref.setEntries(titles);
			pref.setEntryValues(keys);
			pref.setDefaultValue(titles[0]);
			// show current selection
			pref.setSummary(pref.getEntry());
		}

		/**
		 * set up selection for PIDs
		 */
		void setupPidSelection()
		{
			SearchableMultiSelectListPreference itemList =
				(SearchableMultiSelectListPreference) findPreference(KEY_DATA_ITEMS);
			if (itemList == null) return;

			// collect data items for selection
			items = ObdProt.dataItems.getSvcDataItems(ObdProt.OBD_SVC_DATA);
			HashSet<String> selections = new HashSet<>();
			CharSequence[] titles = new CharSequence[items.size()];
			CharSequence[] keys = new CharSequence[items.size()];
			// loop through data items
			int i = 0;
			for (EcuDataItem currItem : items)
			{
				titles[i] = ObdUiLabels.label(requireContext(),
					String.valueOf(currItem.pv.get(EcuDataPv.FID_MNEMONIC)), currItem.label);
				keys[i] = currItem.toString();
				selections.add(currItem.toString());
				i++;
			}
			// set enries and keys
			itemList.setEntries(titles);
			itemList.setEntryValues(keys);

			// if there is no item selected, mark all as selected
			if (itemList.getValues().size() == 0)
			{
				itemList.setValues(selections);
			}
		}

		/**
		 * set up preference text for extension files
		 *
		 * @param key preference key to be set up
		 */
		void setPrefsText(String key)
		{
			Preference prefComp = findPreference(key);
			if (prefComp == null) return;
			prefComp.setOnPreferenceClickListener(this);
			String value = prefs.getString(key, null);
			if (value != null)
			{
				prefComp.setSummary(value);
			}
		}

		/**
		 * Update fields for network parameters
		 *
		 * enable/disable elements for network parameters
		 * based on selection of communication medium
		 */
		void updateNetworkSelections()
		{
			boolean networkSelected =
				String.valueOf(CommService.MEDIUM.NETWORK.ordinal())
					.equals(prefs.getString(KEY_COMM_MEDIUM,""));
			boolean bluetoothSelected =
				String.valueOf(CommService.MEDIUM.BLUETOOTH.ordinal())
					.equals(prefs.getString(KEY_COMM_MEDIUM,""));
			boolean usbSelected =
					String.valueOf(CommService.MEDIUM.USB.ordinal())
							.equals(prefs.getString(KEY_COMM_MEDIUM,""));

			// enable/disable network specific entries
			for(String key : networkKeys)
			{
				Preference pref = findPreference(key);
				if (pref != null) pref.setEnabled(networkSelected);
			}

			// enable/disable bluetooth specific entries
			for(String key : bluetoothKeys)
			{
				Preference pref = findPreference(key);
				if (pref != null) pref.setEnabled(bluetoothSelected);
			}

			// enable/disable usb specific entries
			for(String key : usbKeys)
			{
				Preference pref = findPreference(key);
				if (pref != null) pref.setEnabled(usbSelected);
			}
		}

		private void openLogDirectory()
		{
			java.io.File logDir = new java.io.File(
					FileHelper.getPath(requireContext()), "log");
			if (!logDir.isDirectory())
			{
				Toast.makeText(requireContext(),
						R.string.log_dir_not_found,
						Toast.LENGTH_LONG).show();
				return;
			}
			try
			{
				Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setDataAndType(Uri.fromFile(logDir), "resource/folder");
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity(Intent.createChooser(intent,
						getString(R.string.open_log_dir)));
			}
			catch (Exception e)
			{
				// No file manager installed, or Android 7+ blocked file:// URI;
				// copy the path to clipboard as a fallback.
				ClipboardManager cm = (ClipboardManager)
						requireContext().getSystemService(CLIPBOARD_SERVICE);
				cm.setPrimaryClip(ClipData.newPlainText(
						"log_dir", logDir.getAbsolutePath()));
				Toast.makeText(requireContext(),
						R.string.log_dir_no_app,
						Toast.LENGTH_LONG).show();
			}
		}

		@Override
		public boolean onPreferenceClick(Preference preference)
		{
			Intent intent = preference.getIntent();
			try
			{
				if(KEY_BITCOIN.equals(preference.getKey()))
				{
					// special handling for bitcoin VIEW intent
					startActivity(intent);
				}
				else if(KEY_OPEN_LOG_DIR.equals(preference.getKey()))
				{
					openLogDirectory();
					return true;
				}
				else
				{
					// OPEN intents require result handling
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					startActivityForResult(intent, preference.hashCode());
				}
			}
			catch(Exception e)
			{
				log.log(Level.SEVERE, "Settings", e);
				Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
			}
			return true;
		}

		/**
		 * Handler for result messages from other activities
		 */
		@Override
		public void onActivityResult(int requestCode, int resultCode, Intent data)
		{
			Preference pref;
			SharedPreferences.Editor ed = prefs.edit();
			String value = (resultCode == Activity.RESULT_OK) ? String.valueOf(data.getData()) : null;
			// find the right key
			for (String key : extKeys)
			{
				pref = findPreference(key);
				if (pref.hashCode() == requestCode)
				{
					ed.putString(key, value);
					pref.setSummary(value != null ? value : getString(R.string.select_extension));
				}
			}

			ed.apply();
		}

		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
		{
			Preference pref = findPreference(key);

			if (pref instanceof ListPreference)
			{
				ListPreference currPref = (ListPreference) pref;
				currPref.setSummary(currPref.getEntry());
			}
			else
			if (pref instanceof EditTextPreference)
			{
				EditTextPreference currPref = (EditTextPreference) pref;
				currPref.setSummary(currPref.getText());
			}

			if(KEY_COMM_MEDIUM.equals(key))
				updateNetworkSelections();

			if(ELM_TIMING_SELECT.equals(key))
				//noinspection ConstantConditions
				findPreference(ELM_MIN_TIMEOUT)
					.setEnabled(ElmProt.AdaptTimingMode.SOFTWARE.toString()
						          .equals(((ListPreference)pref).getValue())
					           );

			if(KEY_APP_LANGUAGE.equals(key))
			{
				// Apply new language immediately
				SettingsActivity.applyLocale(requireActivity());

				// Show restart message
				Toast.makeText(requireContext(),
				              getString(R.string.restart_required),
				              Toast.LENGTH_LONG).show();
			}
		}
	}
}
