/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.tts;

import android.support.v7.preference.ListPreference;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.speech.tts.TtsEngines;
import android.speech.tts.UtteranceProgressListener;
import android.support.v7.preference.Preference;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import java.util.Comparator;
import java.util.Collections;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.SettingsPreferenceFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Set;

import static android.provider.Settings.Secure.TTS_DEFAULT_SYNTH;
import static android.provider.Settings.Secure.TTS_DEFAULT_PITCH;

public class TextToSpeechSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final String STATE_KEY_LOCALE_ENTRIES = "locale_entries";
    private static final String STATE_KEY_LOCALE_ENTRY_VALUES = "locale_entry_values";
    private static final String STATE_KEY_LOCALE_VALUE = "locale_value";

    private static final String TAG = "TextToSpeechSettings";
    private static final boolean DBG = false;

    /** Preference key for the "play TTS example" preference. */
    private static final String KEY_PLAY_EXAMPLE = "tts_play_example";;

    /** Preference key for the TTS status field. */
    private static final String KEY_STATUS = "tts_status";

    /**
     * These look like birth years, but they aren't mine. I'm much younger than this.
     */
    private static final int GET_SAMPLE_TEXT = 1983;
    private static final int VOICE_DATA_INTEGRITY_CHECK = 1977;

    private Preference mPlayExample;
    private Preference mEngineStatus;


    private static final String KEY_ENGINE_LOCALE = "tts_default_lang";
    private static final String KEY_ENGINE_SETTINGS = "tts_engine_settings";
    private static final String KEY_INSTALL_DATA = "tts_install_data";

    private int mSelectedLocaleIndex = -1;

    /** The currently selected engine. */
    private String mCurrentEngine;

    private TextToSpeech mTts = null;
    private TtsEngines mEnginesHelper = null;

    private String mSampleText = null;

    private ListPreference mLocalePreference;
    private Preference mEngineSettingsPreference;
    private Preference mInstallVoicesPreference;

    /**
     * Default locale used by selected TTS engine, null if not connected to any engine.
     */
    private Locale mCurrentDefaultLocale;

    /**
     * List of available locals of selected TTS engine, as returned by
     * {@link TextToSpeech.Engine#ACTION_CHECK_TTS_DATA} activity. If empty, then activity
     * was not yet called.
     */
    private List<String> mAvailableStrLocals;

    /**
     * The initialization listener used when we are initalizing the settings
     * screen for the first time (as opposed to when a user changes his choice
     * of engine).
     */
    private final TextToSpeech.OnInitListener mInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            onInitEngine(status);
        }
    };

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.TTS_TEXT_TO_SPEECH;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.tts_settings);

        getActivity().setVolumeControlStream(TextToSpeech.Engine.DEFAULT_STREAM);

        mPlayExample = findPreference(KEY_PLAY_EXAMPLE);
        mPlayExample.setOnPreferenceClickListener(this);
        mPlayExample.setEnabled(false);

        mEnginesHelper = new TtsEngines(getActivity().getApplicationContext());

        mLocalePreference = (ListPreference) findPreference(KEY_ENGINE_LOCALE);
        mLocalePreference.setOnPreferenceChangeListener(this);

        if (savedInstanceState == null) {
            mLocalePreference.setEnabled(false);
            mLocalePreference.setEntries(new CharSequence[0]);
            mLocalePreference.setEntryValues(new CharSequence[0]);
        } else {
            // Repopulate mLocalePreference with saved state. Will be updated later with
            // up-to-date values when checkTtsData() calls back with results.
            final CharSequence[] entries =
                    savedInstanceState.getCharSequenceArray(STATE_KEY_LOCALE_ENTRIES);
            final CharSequence[] entryValues =
                    savedInstanceState.getCharSequenceArray(STATE_KEY_LOCALE_ENTRY_VALUES);
            final CharSequence value = savedInstanceState.getCharSequence(STATE_KEY_LOCALE_VALUE);

            mLocalePreference.setEntries(entries);
            mLocalePreference.setEntryValues(entryValues);
            mLocalePreference.setValue(value != null ? value.toString() : null);
            mLocalePreference.setEnabled(entries.length > 0);
        }

        mEngineSettingsPreference = findPreference(KEY_ENGINE_SETTINGS);
        mEngineSettingsPreference.setOnPreferenceClickListener(this);
        mInstallVoicesPreference = findPreference(KEY_INSTALL_DATA);
        mInstallVoicesPreference.setOnPreferenceClickListener(this);
        mInstallVoicesPreference.setEnabled(false);

        mEngineStatus = findPreference(KEY_STATUS);
        updateEngineStatus(R.string.tts_status_checking);

        mTts = new TextToSpeech(getActivity().getApplicationContext(), mInitListener);

        setTtsUtteranceProgressListener();
        initSettings();

        // Prevent restarting the TTS connection on rotation
        setRetainInstance(true);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mTts == null || mCurrentDefaultLocale == null) {
            return;
        }
        if (!mTts.getDefaultEngine().equals(mTts.getCurrentEngine())) {
            try {
                mTts.shutdown();
                mTts = null;
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down TTS engine" + e);
            }
            mTts = new TextToSpeech(getActivity().getApplicationContext(), mInitListener);
            setTtsUtteranceProgressListener();
            initSettings();
        } else {
            // Do set pitch correctly after it may have changed, and unlike speed, it doesn't change
            // immediately.
            final ContentResolver resolver = getContentResolver();
            mTts.setPitch(android.provider.Settings.Secure.getInt(resolver, TTS_DEFAULT_PITCH, TextToSpeech.Engine.DEFAULT_PITCH)/100.0f);
        }

        Locale ttsDefaultLocale = mTts.getDefaultLanguage();
        if (mCurrentDefaultLocale != null && !mCurrentDefaultLocale.equals(ttsDefaultLocale)) {
            updateWidgetState(false);
            checkDefaultLocale();
        }
    }

    private void setTtsUtteranceProgressListener() {
        if (mTts == null) {
            return;
        }
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {}

            @Override
            public void onDone(String utteranceId) {}

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "Error while trying to synthesize sample text");
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTts != null) {
            mTts.shutdown();
            mTts = null;
        }
    }

    private void initSettings() {
        final ContentResolver resolver = getContentResolver();

        if (mTts != null) {
            mCurrentEngine = mTts.getCurrentEngine();
            mTts.setPitch(android.provider.Settings.Secure.getInt(resolver, TTS_DEFAULT_PITCH, TextToSpeech.Engine.DEFAULT_PITCH)/100.0f);
        }

        SettingsActivity activity = null;
        if (getActivity() instanceof SettingsActivity) {
            activity = (SettingsActivity) getActivity();
        } else {
            throw new IllegalStateException("TextToSpeechSettings used outside a " +
                    "Settings");
        }

        if (mCurrentEngine != null) {
            EngineInfo info = mEnginesHelper.getEngineInfo(mCurrentEngine);
            mEngineSettingsPreference.setSummary(info.label);
            final Intent settingsIntent = mEnginesHelper.getSettingsIntent(info.name);
            mEngineSettingsPreference.setIntent(settingsIntent);
            if (settingsIntent == null) {
                mEngineSettingsPreference.setEnabled(false);
            }

            Preference mEnginePreference = findPreference("tts_engine_preference");
            mEnginePreference.setSummary(info.label);
        }

        checkVoiceData(mCurrentEngine);
    }

    /**
     * Called when the TTS engine is initialized.
     */
    public void onInitEngine(int status) {
        if (status == TextToSpeech.SUCCESS) {
            if (DBG) Log.d(TAG, "TTS engine for settings screen initialized.");
            checkDefaultLocale();
            getActivity()
                    .runOnUiThread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    mLocalePreference.setEnabled(true);
                                }
                            });
        } else {
            if (DBG) Log.d(TAG, "TTS engine for settings screen failed to initialize successfully.");
            updateWidgetState(false);
        }
    }

    private void checkDefaultLocale() {
        Locale defaultLocale = mTts.getDefaultLanguage();
        if (defaultLocale == null) {
            Log.e(TAG, "Failed to get default language from engine " + mCurrentEngine);
            updateWidgetState(false);
            updateEngineStatus(R.string.tts_status_not_supported);
            return;
        }

        // ISO-3166 alpha 3 country codes are out of spec. If we won't normalize,
        // we may end up with English (USA)and German (DEU).
        final Locale oldDefaultLocale = mCurrentDefaultLocale;
        mCurrentDefaultLocale = mEnginesHelper.parseLocaleString(defaultLocale.toString());
        if (!Objects.equals(oldDefaultLocale, mCurrentDefaultLocale)) {
            mSampleText = null;
        }

        int defaultAvailable = mTts.setLanguage(defaultLocale);
        if (evaluateDefaultLocale() && mSampleText == null) {
            getSampleText();
        }
    }

    private boolean evaluateDefaultLocale() {
        // Check if we are connected to the engine, and CHECK_VOICE_DATA returned list
        // of available languages.
        if (mCurrentDefaultLocale == null || mAvailableStrLocals == null) {
            return false;
        }

        boolean notInAvailableLangauges = true;
        try {
            // Check if language is listed in CheckVoices Action result as available voice.
            String defaultLocaleStr = mCurrentDefaultLocale.getISO3Language();
            if (!TextUtils.isEmpty(mCurrentDefaultLocale.getISO3Country())) {
                defaultLocaleStr += "-" + mCurrentDefaultLocale.getISO3Country();
            }
            if (!TextUtils.isEmpty(mCurrentDefaultLocale.getVariant())) {
                defaultLocaleStr += "-" + mCurrentDefaultLocale.getVariant();
            }

            for (String loc : mAvailableStrLocals) {
                if (loc.equalsIgnoreCase(defaultLocaleStr)) {
                  notInAvailableLangauges = false;
                  break;
                }
            }
        } catch (MissingResourceException e) {
            if (DBG) Log.wtf(TAG, "MissingResourceException", e);
            updateEngineStatus(R.string.tts_status_not_supported);
            updateWidgetState(false);
            return false;
        }

        int defaultAvailable = mTts.setLanguage(mCurrentDefaultLocale);
        if (defaultAvailable == TextToSpeech.LANG_NOT_SUPPORTED ||
                defaultAvailable == TextToSpeech.LANG_MISSING_DATA ||
                notInAvailableLangauges) {
            if (DBG) Log.d(TAG, "Default locale for this TTS engine is not supported.");
            updateEngineStatus(R.string.tts_status_not_supported);
            updateWidgetState(false);
            return false;
        } else {
            if (isNetworkRequiredForSynthesis()) {
                updateEngineStatus(R.string.tts_status_requires_network);
            } else {
                updateEngineStatus(R.string.tts_status_ok);
            }
            updateWidgetState(true);
            return true;
        }
    }

    /**
     * Ask the current default engine to return a string of sample text to be
     * spoken to the user.
     */
    private void getSampleText() {
        String currentEngine = mTts.getCurrentEngine();

        if (TextUtils.isEmpty(currentEngine)) currentEngine = mTts.getDefaultEngine();

        // TODO: This is currently a hidden private API. The intent extras
        // and the intent action should be made public if we intend to make this
        // a public API. We fall back to using a canned set of strings if this
        // doesn't work.
        Intent intent = new Intent(TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT);

        intent.putExtra("language", mCurrentDefaultLocale.getLanguage());
        intent.putExtra("country", mCurrentDefaultLocale.getCountry());
        intent.putExtra("variant", mCurrentDefaultLocale.getVariant());
        intent.setPackage(currentEngine);

        try {
            if (DBG) Log.d(TAG, "Getting sample text: " + intent.toUri(0));
            startActivityForResult(intent, GET_SAMPLE_TEXT);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Failed to get sample text, no activity found for " + intent + ")");
        }
    }

    /**
     * Called when voice data integrity check returns
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == GET_SAMPLE_TEXT) {
            onSampleTextReceived(resultCode, data);
        } else if (requestCode == VOICE_DATA_INTEGRITY_CHECK) {
            onVoiceDataIntegrityCheckDone(data);
            if (resultCode != TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL) {
                updateDefaultLocalePref(data);
            }
        }
    }

    private void updateDefaultLocalePref(Intent data) {
        final ArrayList<String> availableLangs =
                data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES);

        final ArrayList<String> unavailableLangs =
                data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES);

        if (availableLangs != null && unavailableLangs.size() > 0) {
            mInstallVoicesPreference.setEnabled(true);
        } else {
            mInstallVoicesPreference.setEnabled(false);
        }

        if (availableLangs == null || availableLangs.size() == 0) {
            mLocalePreference.setEnabled(false);
            return;
        }
        Locale currentLocale = null;
        if (!mEnginesHelper.isLocaleSetToDefaultForEngine(mTts.getCurrentEngine())) {
            currentLocale = mEnginesHelper.getLocalePrefForEngine(mTts.getCurrentEngine());
        }

        ArrayList<Pair<String, Locale>> entryPairs =
                new ArrayList<Pair<String, Locale>>(availableLangs.size());
        for (int i = 0; i < availableLangs.size(); i++) {
            Locale locale = mEnginesHelper.parseLocaleString(availableLangs.get(i));
            if (locale != null) {
                entryPairs.add(new Pair<String, Locale>(locale.getDisplayName(), locale));
            }
        }

        // Sort it
        Collections.sort(
                entryPairs,
                new Comparator<Pair<String, Locale>>() {
                    @Override
                    public int compare(Pair<String, Locale> lhs, Pair<String, Locale> rhs) {
                        return lhs.first.compareToIgnoreCase(rhs.first);
                    }
                });

        // Get two arrays out of one of pairs
        mSelectedLocaleIndex = 0; // Will point to the R.string.tts_lang_use_system value
        CharSequence[] entries = new CharSequence[availableLangs.size() + 1];
        CharSequence[] entryValues = new CharSequence[availableLangs.size() + 1];

        entries[0] = getActivity().getString(R.string.tts_lang_use_system);
        entryValues[0] = "";

        int i = 1;
        for (Pair<String, Locale> entry : entryPairs) {
            if (entry.second.equals(currentLocale)) {
                mSelectedLocaleIndex = i;
            }
            entries[i] = entry.first;
            entryValues[i++] = entry.second.toString();
        }

        mLocalePreference.setEntries(entries);
        mLocalePreference.setEntryValues(entryValues);
        mLocalePreference.setEnabled(true);
        setLocalePreference(mSelectedLocaleIndex);
    }

    /** Set entry from entry table in mLocalePreference */
    private void setLocalePreference(int index) {
        if (index < 0) {
            mLocalePreference.setValue("");
            mLocalePreference.setSummary(R.string.tts_lang_not_selected);
        } else {
            mLocalePreference.setValueIndex(index);
            mLocalePreference.setSummary(mLocalePreference.getEntries()[index]);
        }
    }


    private String getDefaultSampleString() {
        if (mTts != null && mTts.getLanguage() != null) {
            try {
                final String currentLang = mTts.getLanguage().getISO3Language();
                String[] strings = getActivity().getResources().getStringArray(
                        R.array.tts_demo_strings);
                String[] langs = getActivity().getResources().getStringArray(
                        R.array.tts_demo_string_langs);

                for (int i = 0; i < strings.length; ++i) {
                    if (langs[i].equals(currentLang)) {
                        return strings[i];
                    }
                }
            } catch (MissingResourceException e) {
                if (DBG) Log.wtf(TAG, "MissingResourceException", e);
                // Ignore and fall back to default sample string
            }
        }
        return getString(R.string.tts_default_sample_string);
    }

    private boolean isNetworkRequiredForSynthesis() {
        Set<String> features = mTts.getFeatures(mCurrentDefaultLocale);
        if (features == null) {
          return false;
        }
        return features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) &&
                !features.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
    }

    private void onSampleTextReceived(int resultCode, Intent data) {
        String sample = getDefaultSampleString();

        if (resultCode == TextToSpeech.LANG_AVAILABLE && data != null) {
            if (data != null && data.getStringExtra("sampleText") != null) {
                sample = data.getStringExtra("sampleText");
            }
            if (DBG) Log.d(TAG, "Got sample text: " + sample);
        } else {
            if (DBG) Log.d(TAG, "Using default sample text :" + sample);
        }

        mSampleText = sample;
        if (mSampleText != null) {
            updateWidgetState(true);
        } else {
            Log.e(TAG, "Did not have a sample string for the requested language. Using default");
        }
    }

    private void speakSampleText() {
        final boolean networkRequired = isNetworkRequiredForSynthesis();
        if (!networkRequired || networkRequired &&
                (mTts.isLanguageAvailable(mCurrentDefaultLocale) >= TextToSpeech.LANG_AVAILABLE)) {
            HashMap<String, String> params = new HashMap<String, String>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "Sample");

            mTts.speak(mSampleText, TextToSpeech.QUEUE_FLUSH, params);
        } else {
            Log.w(TAG, "Network required for sample synthesis for requested language");
            displayNetworkAlert();
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mLocalePreference) {
            String localeString = (String) objValue;
            updateLanguageTo(
                    (!TextUtils.isEmpty(localeString)
                            ? mEnginesHelper.parseLocaleString(localeString)
                            : null));
            checkDefaultLocale();
            return true;
        }
        return true;
    }

    private void updateLanguageTo(Locale locale) {
        int selectedLocaleIndex = -1;
        String localeString = (locale != null) ? locale.toString() : "";
        for (int i = 0; i < mLocalePreference.getEntryValues().length; i++) {
            if (localeString.equalsIgnoreCase(mLocalePreference.getEntryValues()[i].toString())) {
                selectedLocaleIndex = i;
                break;
            }
        }

        if (selectedLocaleIndex == -1) {
            Log.w(TAG, "updateLanguageTo called with unknown locale argument");
            return;
        }
        mLocalePreference.setSummary(mLocalePreference.getEntries()[selectedLocaleIndex]);
        mSelectedLocaleIndex = selectedLocaleIndex;

        mEnginesHelper.updateLocalePrefForEngine(mTts.getCurrentEngine(), locale);

        // Null locale means "use system default"
        mTts.setLanguage((locale != null) ? locale : Locale.getDefault());
    }

    /**
     * Ask the current default engine to launch the matching INSTALL_TTS_DATA activity so the
     * required TTS files are properly installed.
     */
    private void installVoiceData() {
        if (TextUtils.isEmpty(mCurrentEngine)) return;
        Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        intent.setPackage(mCurrentEngine);
        try {
            Log.v(TAG, "Installing voice data: " + intent.toUri(0));
            startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Failed to install TTS data, no acitivty found for " + intent + ")");
        }
    }

    /**
     * Called when mPlayExample, mInstallVoicesPreference is clicked.
     */
    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mPlayExample) {
            // Get the sample text from the TTS engine; onActivityResult will do
            // the actual speaking
            speakSampleText();
            return true;
        } else if (preference == mInstallVoicesPreference) {
            installVoiceData();
            return true;
        }
        return false;
    }

    private void updateWidgetState(boolean enable) {
        mPlayExample.setEnabled(enable);
        mEngineStatus.setEnabled(enable);
    }

    private void updateEngineStatus(int resourceId) {
        Locale locale = mCurrentDefaultLocale;
        if (locale == null) {
            locale = Locale.getDefault();
        }
        mEngineStatus.setSummary(getString(resourceId, locale.getDisplayName()));
    }

    private void displayNetworkAlert() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(android.R.string.dialog_alert_title)
                .setMessage(getActivity().getString(R.string.tts_engine_network_required))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /** Check whether the voice data for the engine is ok. */
    private void checkVoiceData(String engine) {
        Intent intent = new Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
        intent.setPackage(engine);
        try {
            if (DBG) Log.d(TAG, "Updating engine: Checking voice data: " + intent.toUri(0));
            startActivityForResult(intent, VOICE_DATA_INTEGRITY_CHECK);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Failed to check TTS data, no activity found for " + intent + ")");
        }
    }

    /** The voice data check is complete. */
    private void onVoiceDataIntegrityCheckDone(Intent data) {
        final String engine = mTts.getCurrentEngine();

        if (engine == null) {
            Log.e(TAG, "Voice data check complete, but no engine bound");
            return;
        }

        if (data == null){
            Log.e(TAG, "Engine failed voice data integrity check (null return)" +
                    mTts.getCurrentEngine());
            return;
        }

        android.provider.Settings.Secure.putString(getContentResolver(), TTS_DEFAULT_SYNTH, engine);

        mAvailableStrLocals = data.getStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES);
        if (mAvailableStrLocals == null) {
            Log.e(TAG, "Voice data check complete, but no available voices found");
            // Set mAvailableStrLocals to empty list
            mAvailableStrLocals = new ArrayList<String>();
        }
        if (evaluateDefaultLocale()) {
            getSampleText();
        }
    }
}
