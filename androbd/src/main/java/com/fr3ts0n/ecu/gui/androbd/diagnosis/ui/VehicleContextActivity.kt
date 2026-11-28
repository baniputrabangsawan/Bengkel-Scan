package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DescriptionMatch
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.Severity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingContext
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.SymptomLexiconLoader
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.SymptomDescriptionParser
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.TriageResult
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Explicit text review precedes heuristic ranking; speech only fills the editable description. */
class VehicleContextActivity : AppCompatActivity(), OfflineSpeechInput.Listener {
    private lateinit var model: VehicleContextViewModel
    private lateinit var speech: OfflineSpeechInput
    private var rendering = false
    private var speechActive = false
    private var pendingSpeech = false
    private var displayedReview: DescriptionMatch? = null
    private var displayedResult: TriageResult? = null
    private val spinnerValues = linkedMapOf<Int, List<String>>()
    private val textIds = listOf(R.id.context_make, R.id.context_model, R.id.context_year, R.id.context_mileage)
    private val conditionLabels = linkedMapOf("cold" to R.string.context_condition_cold, "warm" to R.string.context_condition_warm,
        "idle" to R.string.context_condition_idle, "throttle" to R.string.context_condition_throttle,
        "braking" to R.string.context_condition_braking, "turning" to R.string.context_condition_turning)
    private val flagLabels = linkedMapOf("heavy_engine_knock" to R.string.context_flag_knock,
        "overheating" to R.string.context_flag_heat, "brake_change" to R.string.context_flag_brakes,
        "steering_change" to R.string.context_flag_steering, "wheel_play" to R.string.context_flag_wheel,
        "fades_when_warm" to R.string.context_flag_fade, "recent_debris" to R.string.context_flag_debris,
        "tyre_recent_change" to R.string.context_flag_tyres, "steam" to R.string.context_flag_steam,
        "temperature_rising" to R.string.context_flag_temperature, "sweet_smell" to R.string.context_flag_sweet,
        "engine_stalled" to R.string.context_flag_stalled)
    private val questionLabels = linkedMapOf("engine_stalled" to R.string.context_question_engine_stalled,
        "steam" to R.string.context_question_steam, "sweet_smell" to R.string.context_question_sweet_smell,
        "temperature_rising" to R.string.context_question_temperature_rising)
    private val followupSelectors = linkedMapOf<String, Spinner>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!pendingSpeech) return@registerForActivityResult
        if (!granted) {
            pendingSpeech = false
            onSpeechStatus(R.string.speech_permission_required, false)
        } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingSpeech = false
            speech.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsActivity.applyLocale(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.diagnosis_vehicle_context)
        ModernUiUtils.applyScreenInsets(this)
        WorkshopUi.prepare(this, getString(R.string.context_title))
        val serviceId = intent.getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1).takeIf { it > 0 }
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(VehicleContextViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return VehicleContextViewModel(application, serviceId) as T
            }
        })[VehicleContextViewModel::class.java]
        speech = OfflineSpeechInput(this, this)
        bindFields()
        onSpeechStatus(if (speech.isAvailable()) R.string.context_speech_ready else R.string.speech_unavailable, false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
        findViewById<View>(R.id.context_open_obd).setOnClickListener {
            val snapshot = model.state.value
            val result = snapshot.result ?: return@setOnClickListener
            val review = snapshot.review
            val context = RecordingContext(snapshot.profile, snapshot.temperature.ifBlank { review?.engineTempState.orEmpty() },
                snapshot.triggers + review?.triggerConditions.orEmpty(), snapshot.location.ifBlank { review?.location.orEmpty() },
                result.severity == Severity.STOP)
            speech.cancel()
            startActivity(Intent(this, AudioResultActivity::class.java)
                .putExtra(AudioResultActivity.EXTRA_CONTEXT, context.json().toString())
                .putStringArrayListExtra(AudioResultActivity.EXTRA_SYMPTOMS, ArrayList(snapshot.symptoms)))
        }
    }

    private fun bindFields() {
        findViewById<MaterialAutoCompleteTextView>(R.id.context_make).setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resources.getStringArray(R.array.context_make_options)))
        dropdown(R.id.context_fuel, R.array.context_fuel_options, listOf("", "petrol", "diesel", "hybrid", "electric"))
        dropdown(R.id.context_cylinders, R.array.context_cylinder_options, listOf("", "1", "2", "3", "4", "5", "6", "8", "10", "12"))
        dropdown(R.id.context_transmission, R.array.context_transmission_options, listOf("", "manual", "automatic", "cvt", "other"))
        dropdown(R.id.context_fuel_system, R.array.context_system_options, listOf("", "carburetor", "injection"))
        dropdown(R.id.context_steering, R.array.context_steering_options, listOf("", "hydraulic", "eps", "none"))
        dropdown(R.id.context_temperature, R.array.context_temperature_options, listOf("", "cold", "warm"))
        dropdown(R.id.context_location, R.array.context_location_options, listOf("", "front", "rear", "wheel", "under_hood", "cabin"))
        textIds.forEach { id -> findViewById<EditText>(id).addTextChangedListener { if (!rendering) model.updateProfile(profile()) } }
        findViewById<EditText>(R.id.context_description).apply {
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            addTextChangedListener { if (!rendering) model.updateText(it?.toString().orEmpty()) }
        }
        val group = findViewById<ChipGroup>(R.id.context_triggers)
        conditionLabels.forEach { (value, label) ->
            group.addView(Chip(this).apply {
                id = ViewCompat.generateViewId()
                tag = value
                setText(label)
                isCheckable = true
                minHeight = resources.getDimensionPixelSize(R.dimen.ui_touch_min)
                // Material draws its own chip outline; the theme attribute alone is not enough here.
                chipStrokeWidth = 0f
                chipStrokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                setOnCheckedChangeListener { _, _ -> if (!rendering) conditionsChanged() }
            })
        }
        findViewById<MaterialCheckBox>(R.id.context_remember).setOnCheckedChangeListener { _, checked ->
            if (!rendering) model.remember(checked)
        }
        findViewById<View>(R.id.context_forget).setOnClickListener { speech.cancel(); model.newVehicle() }
        findViewById<View>(R.id.context_details_toggle).setOnClickListener {
            val details = findViewById<View>(R.id.context_details)
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.context_speak).setOnClickListener {
            if (speechActive) speech.stop() else startSpeech()
        }
        findViewById<View>(R.id.context_cancel_speech).setOnClickListener { speech.cancel() }
        findViewById<View>(R.id.context_review).setOnClickListener {
            speech.cancel()
            WorkshopUi.hideKeyboard(this)
            model.review()
        }
        findViewById<View>(R.id.context_assess).setOnClickListener {
            WorkshopUi.hideKeyboard(this)
            model.assess()
        }
        findViewById<View>(R.id.context_retry).setOnClickListener { model.load() }
        findViewById<View>(R.id.context_record_audio).setOnClickListener {
            speech.cancel()
            WorkshopUi.hideKeyboard(this)
            val snapshot = model.state.value
            val knowledge = snapshot.knowledge ?: return@setOnClickListener
            if (snapshot.profile.invalidFields().isNotEmpty()) {
                findViewById<TextView>(R.id.context_notice).setText(R.string.recording_invalid_vehicle)
                return@setOnClickListener
            }
            it.isEnabled = false
            lifecycleScope.launch {
                try {
                    val context = withContext(Dispatchers.Default) {
                        val parsed = SymptomLexiconLoader.load(applicationContext).parse(snapshot.text)
                        val flags = snapshot.flags + parsed.flags + snapshot.observations.filterValues { it == "yes" }.keys
                        val result = DiagnosisScorer.rank(knowledge, snapshot.profile.context(
                            snapshot.temperature.ifBlank { parsed.engineTempState },
                            snapshot.triggers + parsed.triggerConditions,
                            snapshot.location.ifBlank { parsed.location }, flags, parsed.temperaturePattern),
                            snapshot.symptoms + parsed.symptomIds)
                        RecordingContext(snapshot.profile, snapshot.temperature.ifBlank { parsed.engineTempState.orEmpty() },
                            snapshot.triggers + parsed.triggerConditions, snapshot.location.ifBlank { parsed.location.orEmpty() },
                            result.severity == Severity.STOP)
                    }
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.state.value === snapshot) {
                        startActivity(Intent(this@VehicleContextActivity, GuidedRecordingActivity::class.java)
                            .putExtra(RecordingContext.EXTRA_CONTEXT, context.json().toString()))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    findViewById<TextView>(R.id.context_notice).setText(R.string.recording_load_failed)
                } finally {
                    findViewById<View>(R.id.context_record_audio).isEnabled = true
                }
            }
        }
    }

    private fun dropdown(id: Int, labels: Int, values: List<String>) {
        spinnerValues[id] = values
        findViewById<Spinner>(id).apply {
            adapter = ArrayAdapter.createFromResource(this@VehicleContextActivity, labels, android.R.layout.simple_spinner_item)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, row: Long) {
                    if (rendering) return
                    if (id == R.id.context_temperature || id == R.id.context_location) conditionsChanged()
                    else if (profile() != model.state.value.profile) model.updateProfile(profile())
                }
            }
        }
    }

    private fun choice(id: Int): String = spinnerValues.getValue(id)
        .getOrElse(findViewById<Spinner>(id).selectedItemPosition) { "" }
    private fun text(id: Int): String = findViewById<EditText>(id).text.toString()
    private fun profile() = VehicleProfile(text(R.id.context_make), text(R.id.context_model), text(R.id.context_year),
        choice(R.id.context_fuel), choice(R.id.context_cylinders), choice(R.id.context_transmission),
        text(R.id.context_mileage), choice(R.id.context_fuel_system), choice(R.id.context_steering))
    private fun conditionsChanged() {
        val temperature = choice(R.id.context_temperature)
        val triggers = selectedChips(findViewById(R.id.context_triggers))
        val location = choice(R.id.context_location)
        val current = model.state.value
        if (temperature != current.temperature || triggers != current.triggers || location != current.location) {
            model.updateConditions(temperature, triggers, location)
        }
    }

    private fun render(state: VehicleContextViewModel.State) {
        rendering = true
        try {
            val profile = state.profile
            setTextIfChanged(R.id.context_make, profile.make)
            setTextIfChanged(R.id.context_model, profile.model)
            setTextIfChanged(R.id.context_year, profile.year)
            setTextIfChanged(R.id.context_mileage, profile.mileageKm)
            setTextIfChanged(R.id.context_description, state.text)
            select(R.id.context_fuel, profile.fuelType)
            select(R.id.context_cylinders, profile.cylinderCount)
            select(R.id.context_transmission, profile.transmission)
            select(R.id.context_fuel_system, profile.fuelSystem)
            select(R.id.context_steering, profile.powerSteeringType)
            select(R.id.context_temperature, state.temperature)
            select(R.id.context_location, state.location)
            val triggers = findViewById<ChipGroup>(R.id.context_triggers)
            (0 until triggers.childCount).forEach { (triggers.getChildAt(it) as Chip).apply {
                isChecked = tag in state.triggers
                isEnabled = !state.loading
            } }
            findViewById<MaterialCheckBox>(R.id.context_remember).isChecked = state.remembered
            findViewById<View>(R.id.context_recalled).visibility = if (state.recalled) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.context_notice).text = when {
                state.loading -> getString(R.string.context_loading)
                state.busy -> getString(R.string.context_working)
                state.notice != 0 -> getString(state.notice)
                else -> ""
            }
            findViewById<TextInputLayout>(R.id.context_year_field).error =
                if ("year" in state.invalidFields) getString(R.string.context_invalid_year) else null
            findViewById<TextInputLayout>(R.id.context_mileage_field).error =
                if ("mileage" in state.invalidFields) getString(R.string.context_invalid_mileage) else null
            if (state.invalidFields.isNotEmpty()) findViewById<View>(R.id.context_details).visibility = View.VISIBLE
            val ready = !state.loading && !state.busy && state.knowledge != null
            findViewById<View>(R.id.context_review).isEnabled = ready
            findViewById<View>(R.id.context_assess).isEnabled = ready && state.review != null
            findViewById<View>(R.id.context_record_audio).isEnabled = ready
            findViewById<View>(R.id.context_forget).isEnabled = !state.loading && !state.busy
            findViewById<View>(R.id.context_retry).visibility = if (!state.loading && state.knowledge == null) View.VISIBLE else View.GONE
            findViewById<View>(R.id.context_remember).isEnabled = !state.loading && !state.busy
            textIds.forEach { findViewById<View>(it).isEnabled = !state.loading }
            spinnerValues.keys.forEach { findViewById<View>(it).isEnabled = !state.loading }
            updateReview(state)
            updateResults(state.result)
        } finally { rendering = false }
    }

    private fun updateReview(state: VehicleContextViewModel.State) {
        val review = state.review
        findViewById<View>(R.id.context_review_panel).visibility = if (review == null) View.GONE else View.VISIBLE
        if (review == null) { displayedReview = null; return }
        if (displayedReview !== review) {
            displayedReview = review
            findViewById<TextView>(R.id.context_interpretation).text = buildString {
                append(getString(R.string.context_review_hint))
                if (review.needsClarification) append("\n\n").append(getString(R.string.context_clarify))
                review.explanations.forEach { append("\n• ").append(it) }
                when (review.temperaturePattern) {
                    "fades_when_warm" -> append("\n").append(getString(R.string.context_pattern_fades))
                    "cold_only" -> append("\n").append(getString(R.string.context_pattern_cold_only))
                }
                if ("engine_stalled" in review.followUpQuestions) append("\n\n").append(getString(R.string.context_combined_warning))
            }
            val followups = findViewById<LinearLayout>(R.id.context_followups)
            followups.removeAllViews()
            followupSelectors.clear()
            if (review.followUpQuestions.isNotEmpty()) addText(followups, getString(R.string.context_question_note))
            questionLabels.forEach { (key, question) ->
                if (key in review.followUpQuestions) {
                    val selector = Spinner(this).apply {
                        id = ViewCompat.generateViewId()
                        isSaveEnabled = false
                        minimumHeight = resources.getDimensionPixelSize(R.dimen.ui_touch_primary)
                        adapter = ArrayAdapter.createFromResource(this@VehicleContextActivity,
                            R.array.context_observation_options, android.R.layout.simple_spinner_item)
                            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    }
                    followups.addView(TextView(this).apply {
                        setText(question); labelFor = selector.id; setTextAppearance(R.style.UiText_Body)
                    }, LinearLayout.LayoutParams(-1, -2))
                    followups.addView(selector, LinearLayout.LayoutParams(-1, -2))
                    followupSelectors[key] = selector
                    selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val answer = listOf("", "yes", "no")[position]
                            if (!rendering && model.state.value.observations[key].orEmpty() != answer) {
                                model.answerObservation(key, answer)
                            }
                        }
                    }
                }
            }
            val symptoms = findViewById<LinearLayout>(R.id.context_symptoms)
            symptoms.removeAllViews()
            state.knowledge?.entries?.forEach { entry ->
                symptoms.addView(check(entry.id, entry.symptomName) {
                    model.selectSymptoms(selectedChecks(symptoms))
                })
            }
            val flags = findViewById<LinearLayout>(R.id.context_flags)
            flags.removeAllViews()
            flagLabels.forEach { (value, label) -> flags.addView(check(value, getString(label)) {
                model.selectFlags(selectedChecks(flags))
            }) }
        }
        setChecks(findViewById(R.id.context_symptoms), state.symptoms, !state.busy)
        setChecks(findViewById(R.id.context_flags), state.flags, !state.busy)
        followupSelectors.forEach { (key, selector) ->
            val selected = listOf("", "yes", "no").indexOf(state.observations[key].orEmpty()).coerceAtLeast(0)
            if (selector.selectedItemPosition != selected) selector.setSelection(selected)
            selector.isEnabled = !state.busy
        }
    }

    private fun updateResults(result: TriageResult?) {
        val root = findViewById<LinearLayout>(R.id.context_results)
        findViewById<View>(R.id.context_open_obd).isEnabled = result != null
        root.visibility = if (result == null) View.GONE else View.VISIBLE
        if (result === displayedResult) return
        displayedResult = result
        root.removeAllViews()
        if (result == null) return
        addText(root, getString(R.string.context_result_heading), true)
        addText(root, result.severity.label, true)
        addText(root, result.message)
        addText(root, result.disclaimer)
        addText(root, result.reviewNotice)
        result.safetyReasons.forEach { addText(root, it, true) }
        result.causes.forEach { ranked ->
            addText(root, ranked.cause.name, true)
            addText(root, getString(R.string.context_score, ranked.score.toString()))
            addText(root, ranked.reason)
            addText(root, getString(R.string.context_verification), true)
            ranked.entry.verificationTests.forEach { addText(root, "• $it") }
            addText(root, getString(R.string.context_action), true)
            addText(root, ranked.entry.recommendedAction)
            val cost = when (ranked.entry.costCategory) {
                "none" -> R.string.context_cost_none
                "low" -> R.string.context_cost_low
                "medium" -> R.string.context_cost_medium
                "high" -> R.string.context_cost_high
                else -> R.string.context_cost_unknown
            }
            addText(root, getString(cost))
        }
        if (result.causes.isEmpty() && model.state.value.profile.supportsDraftCauseRanking()) {
            model.state.value.knowledge?.entries?.filter { !it.diagnostic && it.id in model.state.value.symptoms }?.forEach { entry ->
                addText(root, entry.symptomName, true)
                entry.verificationTests.forEach { addText(root, "• $it") }
                addText(root, entry.recommendedAction)
            }
        }
        root.post { if (!isFinishing && !isDestroyed && displayedResult === result) {
            findViewById<ScrollView>(R.id.context_scroll).smoothScrollTo(0, root.top)
        } }
    }

    private fun addText(root: LinearLayout, value: String, heading: Boolean = false) {
        root.addView(TextView(this).apply {
            text = value
            setTextAppearance(if (heading) R.style.UiText_Section else R.style.UiText_Body)
            if (heading) ViewCompat.setAccessibilityHeading(this, true)
            setPadding(0, resources.getDimensionPixelSize(R.dimen.ui_space_3), 0, 0)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun check(value: String, label: String, onChange: () -> Unit) = MaterialCheckBox(this).apply {
        id = ViewCompat.generateViewId(); tag = value; text = label; isSaveEnabled = false
        minHeight = resources.getDimensionPixelSize(R.dimen.ui_touch_min)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnCheckedChangeListener { _, _ -> if (!rendering) onChange() }
    }
    private fun selectedChecks(group: ViewGroup): Set<String> = (0 until group.childCount)
        .map { group.getChildAt(it) as MaterialCheckBox }.filter { it.isChecked }.mapTo(linkedSetOf()) { it.tag as String }
    private fun selectedChips(group: ChipGroup): Set<String> = (0 until group.childCount)
        .map { group.getChildAt(it) as Chip }.filter { it.isChecked }.mapTo(linkedSetOf()) { it.tag as String }
    private fun setChecks(group: ViewGroup, values: Set<String>, enabled: Boolean) {
        (0 until group.childCount).forEach { (group.getChildAt(it) as MaterialCheckBox).apply {
            isChecked = tag in values; isEnabled = enabled
        } }
    }
    private fun select(id: Int, value: String) {
        val target = spinnerValues.getValue(id).indexOf(value).coerceAtLeast(0)
        findViewById<Spinner>(id).apply { if (selectedItemPosition != target) setSelection(target) }
    }
    private fun setTextIfChanged(id: Int, value: String) {
        findViewById<EditText>(id).apply { if (text.toString() != value) setText(value) }
    }

    private fun startSpeech() {
        if (!speech.isAvailable()) { onSpeechStatus(R.string.speech_unavailable, false); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.start()
        else { pendingSpeech = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    override fun onSpeechStatus(messageRes: Int, active: Boolean) {
        speechActive = active
        findViewById<TextView>(R.id.context_speech_status).setText(messageRes)
        findViewById<MaterialButton>(R.id.context_speak).apply {
            setText(if (active) R.string.context_stop_speech else R.string.context_speak)
            isEnabled = messageRes != R.string.speech_processing
        }
        findViewById<View>(R.id.context_cancel_speech).visibility = if (active) View.VISIBLE else View.GONE
    }
    override fun onTranscript(text: String) {
        if (text.length > SymptomDescriptionParser.MAX_INPUT_CHARS) {
            onSpeechStatus(R.string.context_transcript_too_long, false)
        } else model.updateText(text)
    }
    override fun onResume() {
        super.onResume()
        if (pendingSpeech && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingSpeech = false
            speech.start()
        }
    }
    override fun onStop() { pendingSpeech = false; speech.cancel(); super.onStop() }
    override fun onDestroy() { speech.destroy(); super.onDestroy() }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
