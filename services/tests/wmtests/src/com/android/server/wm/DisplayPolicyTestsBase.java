/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.server.wm.utils.CoordinateTransforms.transformPhysicalToLogicalCoordinates;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.IBinder;
import android.testing.TestableResources;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManagerGlobal;

import com.android.internal.R;
import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Before;

public class DisplayPolicyTestsBase extends WindowTestsBase {

    static final int DISPLAY_WIDTH = 500;
    static final int DISPLAY_HEIGHT = 1000;
    static final int DISPLAY_DENSITY = 320;

    static final int STATUS_BAR_HEIGHT = 10;
    static final int NAV_BAR_HEIGHT = 15;
    static final int DISPLAY_CUTOUT_HEIGHT = 8;

    DisplayPolicy mDisplayPolicy;

    @Before
    public void setUpBase() {
        super.setUpBase();
        mDisplayPolicy = spy(mDisplayContent.getDisplayPolicy());

        final TestContextWrapper context =
                new TestContextWrapper(mDisplayPolicy.getSystemUiContext());
        final TestableResources resources = context.getResourceMocker();
        resources.addOverride(R.dimen.status_bar_height_portrait, STATUS_BAR_HEIGHT);
        resources.addOverride(R.dimen.status_bar_height_landscape, STATUS_BAR_HEIGHT);
        resources.addOverride(R.dimen.navigation_bar_height, NAV_BAR_HEIGHT);
        resources.addOverride(R.dimen.navigation_bar_height_landscape, NAV_BAR_HEIGHT);
        resources.addOverride(R.dimen.navigation_bar_width, NAV_BAR_HEIGHT);
        when(mDisplayPolicy.getSystemUiContext()).thenReturn(context);
        when(mDisplayPolicy.hasNavigationBar()).thenReturn(true);
        when(mDisplayPolicy.hasStatusBar()).thenReturn(true);

        final int shortSizeDp =
                Math.min(DISPLAY_WIDTH, DISPLAY_HEIGHT) * DENSITY_DEFAULT / DISPLAY_DENSITY;
        final int longSizeDp =
                Math.min(DISPLAY_WIDTH, DISPLAY_HEIGHT) * DENSITY_DEFAULT / DISPLAY_DENSITY;
        mDisplayContent.getDisplayRotation().configure(
                DISPLAY_WIDTH, DISPLAY_HEIGHT, shortSizeDp, longSizeDp);
        mDisplayPolicy.configure(DISPLAY_WIDTH, DISPLAY_HEIGHT, shortSizeDp);
        mDisplayPolicy.onConfigurationChanged();

        mStatusBarWindow.mAttrs.gravity = Gravity.TOP;
        addWindow(mStatusBarWindow);
        mDisplayPolicy.mLastSystemUiFlags |= View.STATUS_BAR_TRANSPARENT;

        mNavBarWindow.mAttrs.gravity = Gravity.BOTTOM;
        addWindow(mNavBarWindow);
        mDisplayPolicy.mLastSystemUiFlags |= View.NAVIGATION_BAR_TRANSPARENT;
    }

    void addWindow(WindowState win) {
        mDisplayPolicy.adjustWindowParamsLw(win, win.mAttrs, true /* hasStatusBarPermission */);
        assertEquals(WindowManagerGlobal.ADD_OKAY,
                mDisplayPolicy.prepareAddWindowLw(win, win.mAttrs));
        win.mHasSurface = true;
    }

    static Pair<DisplayInfo, WmDisplayCutout> displayInfoAndCutoutForRotation(int rotation,
            boolean withDisplayCutout) {
        final DisplayInfo info = new DisplayInfo();
        WmDisplayCutout cutout = null;

        final boolean flippedDimensions = rotation == ROTATION_90 || rotation == ROTATION_270;
        info.logicalWidth = flippedDimensions ? DISPLAY_HEIGHT : DISPLAY_WIDTH;
        info.logicalHeight = flippedDimensions ? DISPLAY_WIDTH : DISPLAY_HEIGHT;
        info.rotation = rotation;
        if (withDisplayCutout) {
            cutout = WmDisplayCutout.computeSafeInsets(
                    displayCutoutForRotation(rotation), info.logicalWidth,
                    info.logicalHeight);
            info.displayCutout = cutout.getDisplayCutout();
        } else {
            info.displayCutout = null;
        }
        return Pair.create(info, cutout);
    }

    private static DisplayCutout displayCutoutForRotation(int rotation) {
        final RectF rectF =
                new RectF(DISPLAY_WIDTH / 4, 0, DISPLAY_WIDTH * 3 / 4, DISPLAY_CUTOUT_HEIGHT);

        final Matrix m = new Matrix();
        transformPhysicalToLogicalCoordinates(rotation, DISPLAY_WIDTH, DISPLAY_HEIGHT, m);
        m.mapRect(rectF);

        int pos = -1;
        switch (rotation) {
            case ROTATION_0:
                pos = BOUNDS_POSITION_TOP;
                break;
            case ROTATION_90:
                pos = BOUNDS_POSITION_LEFT;
                break;
            case ROTATION_180:
                pos = BOUNDS_POSITION_BOTTOM;
                break;
            case ROTATION_270:
                pos = BOUNDS_POSITION_RIGHT;
                break;
        }

        return DisplayCutout.fromBoundingRect((int) rectF.left, (int) rectF.top,
                (int) rectF.right, (int) rectF.bottom, pos);
    }

    static class TestContextWrapper extends ContextWrapper {
        private final TestableResources mResourceMocker;

        TestContextWrapper(Context targetContext) {
            super(targetContext);
            mResourceMocker = new TestableResources(targetContext.getResources());
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid, IBinder callerToken) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public Resources getResources() {
            return mResourceMocker.getResources();
        }

        TestableResources getResourceMocker() {
            return mResourceMocker;
        }
    }

}
