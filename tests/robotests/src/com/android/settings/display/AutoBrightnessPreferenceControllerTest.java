/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settings.display;

import android.content.Context;
import android.provider.Settings;
import com.android.settings.SettingsRobolectricTestRunner;
import com.android.settings.TestConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static com.google.common.truth.Truth.assertThat;

@RunWith(SettingsRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class AutoBrightnessPreferenceControllerTest {
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mContext;
    private AutoBrightnessPreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mController = new AutoBrightnessPreferenceController(mContext);
    }

    @Test
    public void testOnPreferenceChange_TurnOnAuto_ReturnAuto() {
        mController.onPreferenceChange(null, true);

        final int mode = Settings.System.getInt(mContext.getContentResolver(),
                SCREEN_BRIGHTNESS_MODE, SCREEN_BRIGHTNESS_MODE_MANUAL);
        assertThat(mode).isEqualTo(SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
    }

    @Test
    public void testOnPreferenceChange_TurnOffAuto_ReturnManual() {
        mController.onPreferenceChange(null, false);

        final int mode = Settings.System.getInt(mContext.getContentResolver(),
                SCREEN_BRIGHTNESS_MODE, SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        assertThat(mode).isEqualTo(SCREEN_BRIGHTNESS_MODE_MANUAL);
    }
}
