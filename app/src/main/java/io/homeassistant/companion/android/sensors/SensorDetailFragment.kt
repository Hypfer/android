package io.homeassistant.companion.android.sensors

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import androidx.preference.contains
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.dagger.GraphComponentAccessor
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.database.sensor.Sensor
import io.homeassistant.companion.android.database.sensor.SensorDao
import io.homeassistant.companion.android.database.sensor.Setting

class SensorDetailFragment(
    private val sensorManager: SensorManager,
    private val basicSensor: SensorManager.BasicSensor
) :
    PreferenceFragmentCompat() {

    companion object {
        fun newInstance(
            sensorManager: SensorManager,
            basicSensor: SensorManager.BasicSensor
        ): SensorDetailFragment {
            return SensorDetailFragment(sensorManager, basicSensor)
        }
    }

    private lateinit var sensorDao: SensorDao
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshSensorData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        DaggerSensorComponent
            .builder()
            .appComponent((activity?.application as GraphComponentAccessor).appComponent)
            .build()
            .inject(this)
        sensorDao = AppDatabase.getInstance(requireContext()).sensorDao()

        addPreferencesFromResource(R.xml.sensor_detail)

        findPreference<SwitchPreference>("enabled")?.let {
            val dao = sensorDao.get(basicSensor.id)
            val perm = sensorManager.checkPermission(requireContext(), basicSensor.id)
            if (dao == null && sensorManager.enabledByDefault) {
                it.isChecked = perm
            }
            if (dao != null) {
                it.isChecked = dao.enabled && perm
            }
            updateSensorEntity(it.isChecked)

            it.setOnPreferenceChangeListener { _, newState ->
                val isEnabled = newState as Boolean

                if (isEnabled && !sensorManager.checkPermission(requireContext(), basicSensor.id)) {
                    val permissions = sensorManager.requiredPermissions(basicSensor.id)
                    when {
                        permissions.any { perm -> perm == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE } ->
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
                            requestPermissions(permissions.toSet()
                                .minus(Manifest.permission.ACCESS_BACKGROUND_LOCATION).toTypedArray(), 0)
                        else -> requestPermissions(permissions, 0)
                    }
                    return@setOnPreferenceChangeListener false
                }

                updateSensorEntity(isEnabled)

                if (isEnabled)
                    sensorManager.requestSensorUpdate(requireContext())
                return@setOnPreferenceChangeListener true
            }
        }
        findPreference<Preference>("description")?.let {
            it.summary = getString(basicSensor.descriptionId)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(refresh, 0)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun refreshSensorData() {
        SensorWorker.start(requireContext())

        val sensorDao = AppDatabase.getInstance(requireContext()).sensorDao()
        val fullData = sensorDao.getFull(basicSensor.id)
        val sensorSettings = sensorDao.getSettings(basicSensor.id)
        if (fullData?.sensor == null)
            return
        val sensorData = fullData.sensor
        val attributes = fullData.attributes

        findPreference<Preference>("unique_id")?.let {
            it.isCopyingEnabled = true
            it.summary = basicSensor.id
        }
        findPreference<Preference>("state")?.let {
            it.isCopyingEnabled = true
            when {
                !sensorData.enabled ->
                    it.summary = "Disabled"
                sensorData.unitOfMeasurement.isNullOrBlank() ->
                    it.summary = sensorData.state
                else ->
                    it.summary = sensorData.state + " " + sensorData.unitOfMeasurement
            }
        }
        findPreference<Preference>("device_class")?.let {
            if (sensorData.enabled && sensorData.deviceClass != null) {
                it.summary = sensorData.deviceClass
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }
        findPreference<Preference>("icon")?.let {
            if (sensorData.enabled && sensorData.icon != "") {
                it.summary = sensorData.icon
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }

        findPreference<PreferenceCategory>("attributes")?.let {
            if (sensorData.enabled && !attributes.isNullOrEmpty()) {
                attributes.forEach { attribute ->
                    val key = "attribute_${attribute.name}"
                    val pref = findPreference(key) ?: Preference(requireContext())
                    pref.isCopyingEnabled = true
                    pref.key = key
                    pref.title = attribute.name
                    pref.summary = attribute.value
                    pref.isIconSpaceReserved = false

                    if (!it.contains(pref))
                        it.addPreference(pref)
                }
                it.isVisible = true
            } else
                it.isVisible = false
        }

        findPreference<PreferenceCategory>("sensor_settings")?.let {
            if (sensorData.enabled && !sensorSettings.isNullOrEmpty()) {
                sensorSettings.forEach { setting ->
                    val key = "setting_${basicSensor.id}_${setting.name}"
                    if (setting.valueType == "toggle") {
                        val pref = findPreference(key) ?: SwitchPreference(requireContext())
                        pref.key = key
                        pref.title = setting.name
                        pref.isChecked = setting.value == "true"
                        pref.isIconSpaceReserved = false
                        pref.setOnPreferenceChangeListener { _, newState ->
                            val isEnabled = newState as Boolean

                            sensorDao.add(Setting(basicSensor.id, setting.name, isEnabled.toString(), "toggle"))
                            sensorManager.requestSensorUpdate(requireContext())
                            return@setOnPreferenceChangeListener true
                        }
                        if (!it.contains(pref))
                            it.addPreference(pref)
                    } else if (setting.valueType == "string" || setting.valueType == "number") {
                        val pref = findPreference(key) ?: EditTextPreference(requireContext())
                        pref.key = key
                        pref.title = setting.name
                        pref.dialogTitle = setting.name
                        if (pref.text != null)
                            pref.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                        else {
                            pref.summary = setting.value
                            pref.text = setting.value
                        }
                        pref.isIconSpaceReserved = false

                        pref.setOnBindEditTextListener { fieldType ->
                            if (setting.valueType == "number")
                                fieldType.inputType = InputType.TYPE_CLASS_NUMBER
                        }

                        pref.setOnPreferenceChangeListener { _, newValue ->
                            sensorDao.add(
                                Setting(
                                    basicSensor.id,
                                    setting.name,
                                    newValue as String,
                                    setting.valueType
                                )
                            )
                            sensorManager.requestSensorUpdate(requireContext())
                            return@setOnPreferenceChangeListener true
                        }
                    if (!it.contains(pref))
                        it.addPreference(pref)
                    } else if (setting.valueType == "list-apps") {
                        val packageManager: PackageManager? = context?.packageManager
                        val packages = packageManager?.getInstalledApplications(PackageManager.GET_META_DATA)
                        val packageName: MutableList<String> = ArrayList()
                        if (packages != null) {
                            for (packageItem in packages) {
                                packageName.add(packageItem.packageName)
                            }
                            packageName.sort()
                        }
                        val pref = findPreference(key) ?: MultiSelectListPreference(requireContext())
                        pref.key = key
                        pref.title = setting.name
                        pref.entries = packageName.toTypedArray()
                        pref.entryValues = packageName.toTypedArray()
                        pref.dialogTitle = setting.name
                        pref.isIconSpaceReserved = false
                        pref.setOnPreferenceChangeListener { _, newValue ->
                            sensorDao.add(
                                Setting(
                                    basicSensor.id,
                                    setting.name,
                                    newValue.toString().replace("[", "").replace("]", ""),
                                    "list-apps"
                                )
                            )
                            sensorManager.requestSensorUpdate(requireContext())
                            return@setOnPreferenceChangeListener true
                        }
                        if (pref.values != null)
                            pref.summary = pref.values.toString()
                        else
                            pref.summary = setting.value
                        if (!it.contains(pref))
                            it.addPreference(pref)
                    }
                }
                it.isVisible = true
            } else
                it.isVisible = false
        }
    }

    private fun updateSensorEntity(
        isEnabled: Boolean
    ) {
        var sensorEntity = sensorDao.get(basicSensor.id)
        if (sensorEntity != null) {
            sensorEntity.enabled = isEnabled
            sensorEntity.lastSentState = ""
            sensorDao.update(sensorEntity)
        } else {
            sensorEntity = Sensor(basicSensor.id, isEnabled, false, "")
            sensorDao.add(sensorEntity)
        }
        refreshSensorData()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 0)
        }

        findPreference<SwitchPreference>("enabled")?.run {
            isChecked = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            updateSensorEntity(isChecked)
        }
    }
}
