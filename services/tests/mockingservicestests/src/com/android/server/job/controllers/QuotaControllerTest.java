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

package com.android.server.job.controllers;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.ACTIVE_INDEX;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.WORKING_INDEX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.job.JobInfo;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.controllers.QuotaController.ExecutionStats;
import com.android.server.job.controllers.QuotaController.TimingSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class QuotaControllerTest {
    private static final long SECOND_IN_MILLIS = 1000L;
    private static final long MINUTE_IN_MILLIS = 60 * SECOND_IN_MILLIS;
    private static final long HOUR_IN_MILLIS = 60 * MINUTE_IN_MILLIS;
    private static final String TAG_CLEANUP = "*job.cleanup*";
    private static final String TAG_QUOTA_CHECK = "*job.quota_check*";
    private static final int CALLING_UID = 1000;
    private static final String SOURCE_PACKAGE = "com.android.frameworks.mockingservicestests";
    private static final int SOURCE_USER_ID = 0;

    private BroadcastReceiver mChargingReceiver;
    private Constants mConstants;
    private QuotaController mQuotaController;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManager;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        // Make sure constants turn on QuotaController.
        mConstants = new Constants();
        mConstants.USE_HEARTBEATS = false;

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        when(mJobSchedulerService.getConstants()).thenReturn(mConstants);
        // Called in QuotaController constructor.
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
        doReturn(mock(BatteryManagerInternal.class))
                .when(() -> LocalServices.getService(BatteryManagerInternal.class));
        doReturn(mUsageStatsManager)
                .when(() -> LocalServices.getService(UsageStatsManagerInternal.class));
        // Used in JobStatus.
        doReturn(mock(PackageManagerInternal.class))
                .when(() -> LocalServices.getService(PackageManagerInternal.class));

        // Freeze the clocks at 24 hours after this moment in time. Several tests create sessions
        // in the past, and QuotaController sometimes floors values at 0, so if the test time
        // causes sessions with negative timestamps, they will fail.
        JobSchedulerService.sSystemClock =
                getAdvancedClock(Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC),
                        24 * HOUR_IN_MILLIS);
        JobSchedulerService.sUptimeMillisClock = getAdvancedClock(
                Clock.fixed(SystemClock.uptimeMillisClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        mQuotaController = new QuotaController(mJobSchedulerService);

        verify(mContext).registerReceiver(receiverCaptor.capture(), any());
        mChargingReceiver = receiverCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private Clock getAdvancedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                JobSchedulerService.sElapsedRealtimeClock, incrementMs);
    }

    private void setCharging() {
        Intent intent = new Intent(BatteryManager.ACTION_CHARGING);
        mChargingReceiver.onReceive(mContext, intent);
    }

    private void setDischarging() {
        Intent intent = new Intent(BatteryManager.ACTION_DISCHARGING);
        mChargingReceiver.onReceive(mContext, intent);
    }

    private void setStandbyBucket(int bucketIndex) {
        int bucket;
        switch (bucketIndex) {
            case ACTIVE_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE;
                break;
            case WORKING_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
                break;
            case FREQUENT_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_FREQUENT;
                break;
            case RARE_INDEX:
                bucket = UsageStatsManager.STANDBY_BUCKET_RARE;
                break;
            default:
                bucket = UsageStatsManager.STANDBY_BUCKET_NEVER;
        }
        when(mUsageStatsManager.getAppStandbyBucket(eq(SOURCE_PACKAGE), eq(SOURCE_USER_ID),
                anyLong())).thenReturn(bucket);
    }

    private void setStandbyBucket(int bucketIndex, JobStatus job) {
        setStandbyBucket(bucketIndex);
        job.setStandbyBucket(bucketIndex);
    }

    private JobStatus createJobStatus(String testTag, int jobId) {
        JobInfo jobInfo = new JobInfo.Builder(jobId,
                new ComponentName(mContext, "TestQuotaJobService"))
                .setMinimumLatency(Math.abs(jobId) + 1)
                .build();
        return JobStatus.createFromJobInfo(
                jobInfo, CALLING_UID, SOURCE_PACKAGE, SOURCE_USER_ID, testTag);
    }

    private TimingSession createTimingSession(long start, long duration, int count) {
        return new TimingSession(start, start + duration, count);
    }

    @Test
    public void testSaveTimingSession() {
        assertNull(mQuotaController.getTimingSessions(0, "com.android.test"));

        List<TimingSession> expected = new ArrayList<>();
        TimingSession one = new TimingSession(1, 10, 1);
        TimingSession two = new TimingSession(11, 20, 2);
        TimingSession thr = new TimingSession(21, 30, 3);

        mQuotaController.saveTimingSession(0, "com.android.test", one);
        expected.add(one);
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));

        mQuotaController.saveTimingSession(0, "com.android.test", two);
        expected.add(two);
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));

        mQuotaController.saveTimingSession(0, "com.android.test", thr);
        expected.add(thr);
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));
    }

    @Test
    public void testDeleteObsoleteSessionsLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        TimingSession thr = createTimingSession(
                now - (3 * HOUR_IN_MILLIS + 10 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        // Overlaps 24 hour boundary.
        TimingSession fou = createTimingSession(
                now - (24 * HOUR_IN_MILLIS + 2 * MINUTE_IN_MILLIS), 7 * MINUTE_IN_MILLIS, 1);
        // Way past the 24 hour boundary.
        TimingSession fiv = createTimingSession(
                now - (25 * HOUR_IN_MILLIS), 5 * MINUTE_IN_MILLIS, 4);
        List<TimingSession> expected = new ArrayList<>();
        // Added in correct (chronological) order.
        expected.add(fou);
        expected.add(thr);
        expected.add(two);
        expected.add(one);
        mQuotaController.saveTimingSession(0, "com.android.test", fiv);
        mQuotaController.saveTimingSession(0, "com.android.test", fou);
        mQuotaController.saveTimingSession(0, "com.android.test", thr);
        mQuotaController.saveTimingSession(0, "com.android.test", two);
        mQuotaController.saveTimingSession(0, "com.android.test", one);

        mQuotaController.deleteObsoleteSessionsLocked();

        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test"));
    }

    @Test
    public void testOnAppRemovedLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test.remove",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1));
        // Test that another app isn't affected.
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        List<TimingSession> expected = new ArrayList<>();
        // Added in correct (chronological) order.
        expected.add(two);
        expected.add(one);
        mQuotaController.saveTimingSession(0, "com.android.test.stay", two);
        mQuotaController.saveTimingSession(0, "com.android.test.stay", one);

        ExecutionStats expectedStats = new ExecutionStats();
        expectedStats.invalidTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;

        mQuotaController.onAppRemovedLocked("com.android.test.remove", 10001);
        assertNull(mQuotaController.getTimingSessions(0, "com.android.test.remove"));
        assertEquals(expected, mQuotaController.getTimingSessions(0, "com.android.test.stay"));
        assertEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test.remove", RARE_INDEX));
        assertNotEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test.stay", RARE_INDEX));
    }

    @Test
    public void testOnUserRemovedLocked() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1));
        // Test that another user isn't affected.
        TimingSession one = createTimingSession(
                now - 10 * MINUTE_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3);
        TimingSession two = createTimingSession(
                now - (70 * MINUTE_IN_MILLIS), 9 * MINUTE_IN_MILLIS, 1);
        List<TimingSession> expected = new ArrayList<>();
        // Added in correct (chronological) order.
        expected.add(two);
        expected.add(one);
        mQuotaController.saveTimingSession(10, "com.android.test", two);
        mQuotaController.saveTimingSession(10, "com.android.test", one);

        ExecutionStats expectedStats = new ExecutionStats();
        expectedStats.invalidTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;

        mQuotaController.onUserRemovedLocked(0);
        assertNull(mQuotaController.getTimingSessions(0, "com.android.test"));
        assertEquals(expected, mQuotaController.getTimingSessions(10, "com.android.test"));
        assertEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test", RARE_INDEX));
        assertNotEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(10, "com.android.test", RARE_INDEX));
    }

    @Test
    public void testUpdateExecutionStatsLocked_NoTimer() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Added in chronological order.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (2 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS), 6 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(
                        now - (HOUR_IN_MILLIS - 10 * MINUTE_IN_MILLIS), MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 4 * MINUTE_IN_MILLIS, 3));

        // Test an app that hasn't had any activity.
        ExecutionStats expectedStats = new ExecutionStats();
        ExecutionStats inputStats = new ExecutionStats();

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 12 * HOUR_IN_MILLIS;
        // Invalid time is now +24 hours since there are no sessions at all for the app.
        expectedStats.invalidTimeElapsed = now + 24 * HOUR_IN_MILLIS;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test.not.run", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = MINUTE_IN_MILLIS;
        // Invalid time is now +18 hours since there are no sessions in the window but the earliest
        // session is 6 hours ago.
        expectedStats.invalidTimeElapsed = now + 18 * HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 0;
        expectedStats.bgJobCountInWindow = 0;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 3 * MINUTE_IN_MILLIS;
        // Invalid time is now since the session straddles the window cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 2 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 3;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 5 * MINUTE_IN_MILLIS;
        // Invalid time is now since the start of the session is at the very edge of the window
        // cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 4 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 3;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 49 * MINUTE_IN_MILLIS;
        // Invalid time is now +44 minutes since the earliest session in the window is now-5
        // minutes.
        expectedStats.invalidTimeElapsed = now + 44 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 4 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 3;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 50 * MINUTE_IN_MILLIS;
        // Invalid time is now since the session is at the very edge of the window cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 5 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 4;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = HOUR_IN_MILLIS;
        // Invalid time is now since the start of the session is at the very edge of the window
        // cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 6 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 5;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        // Invalid time is now since the session straddles the window cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 11 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 10;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 3 * HOUR_IN_MILLIS;
        // Invalid time is now +59 minutes since the earliest session in the window is now-121
        // minutes.
        expectedStats.invalidTimeElapsed = now + 59 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 12 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 10;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        inputStats.windowSizeMs = expectedStats.windowSizeMs = 6 * HOUR_IN_MILLIS;
        // Invalid time is now since the start of the session is at the very edge of the window
        // cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 15;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        // Make sure invalidTimeElapsed is set correctly when it's dependent on the max period.
        mQuotaController.getTimingSessions(0, "com.android.test")
                .add(0,
                        createTimingSession(now - (23 * HOUR_IN_MILLIS), MINUTE_IN_MILLIS, 3));
        inputStats.windowSizeMs = expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        // Invalid time is now +1 hour since the earliest session in the max period is 1 hour
        // before the end of the max period cutoff time.
        expectedStats.invalidTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 23 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 18;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);

        mQuotaController.getTimingSessions(0, "com.android.test")
                .add(0,
                        createTimingSession(now - (24 * HOUR_IN_MILLIS + MINUTE_IN_MILLIS),
                                2 * MINUTE_IN_MILLIS, 2));
        inputStats.windowSizeMs = expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        // Invalid time is now since the earlist session straddles the max period cutoff time.
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 22 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 24 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.updateExecutionStatsLocked(0, "com.android.test", inputStats);
        assertEquals(expectedStats, inputStats);
    }

    /**
     * Tests that getExecutionStatsLocked returns the correct stats.
     */
    @Test
    public void testGetExecutionStatsLocked_Values() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (23 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (7 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (2 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5));

        ExecutionStats expectedStats = new ExecutionStats();

        // Active
        expectedStats.windowSizeMs = 10 * MINUTE_IN_MILLIS;
        expectedStats.invalidTimeElapsed = now + 4 * MINUTE_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 3 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 5;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        assertEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test", ACTIVE_INDEX));

        // Working
        expectedStats.windowSizeMs = 2 * HOUR_IN_MILLIS;
        expectedStats.invalidTimeElapsed = now;
        expectedStats.executionTimeInWindowMs = 13 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 10;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - 3 * MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        assertEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test", WORKING_INDEX));

        // Frequent
        expectedStats.windowSizeMs = 8 * HOUR_IN_MILLIS;
        expectedStats.invalidTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 23 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 15;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - 3 * MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        assertEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test", FREQUENT_INDEX));

        // Rare
        expectedStats.windowSizeMs = 24 * HOUR_IN_MILLIS;
        expectedStats.invalidTimeElapsed = now + HOUR_IN_MILLIS;
        expectedStats.executionTimeInWindowMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInWindow = 20;
        expectedStats.executionTimeInMaxPeriodMs = 33 * MINUTE_IN_MILLIS;
        expectedStats.bgJobCountInMaxPeriod = 20;
        expectedStats.quotaCutoffTimeElapsed = now - (2 * HOUR_IN_MILLIS - 3 * MINUTE_IN_MILLIS)
                + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        assertEquals(expectedStats,
                mQuotaController.getExecutionStatsLocked(0, "com.android.test", RARE_INDEX));
    }

    /**
     * Tests that getExecutionStatsLocked properly caches the stats and returns the cached object.
     */
    @Test
    public void testGetExecutionStatsLocked_Caching() {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (23 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (7 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (2 * HOUR_IN_MILLIS), 10 * MINUTE_IN_MILLIS, 5));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (6 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 5));
        final ExecutionStats originalStatsActive = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", ACTIVE_INDEX);
        final ExecutionStats originalStatsWorking = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", WORKING_INDEX);
        final ExecutionStats originalStatsFrequent = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", FREQUENT_INDEX);
        final ExecutionStats originalStatsRare = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", RARE_INDEX);

        // Advance clock so that the working stats shouldn't be the same.
        advanceElapsedClock(MINUTE_IN_MILLIS);
        // Change frequent bucket size so that the stats need to be recalculated.
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = 6 * HOUR_IN_MILLIS;
        mQuotaController.onConstantsUpdatedLocked();

        ExecutionStats expectedStats = new ExecutionStats();
        expectedStats.windowSizeMs = originalStatsActive.windowSizeMs;
        expectedStats.invalidTimeElapsed = originalStatsActive.invalidTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsActive.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsActive.bgJobCountInWindow;
        expectedStats.executionTimeInMaxPeriodMs = originalStatsActive.executionTimeInMaxPeriodMs;
        expectedStats.bgJobCountInMaxPeriod = originalStatsActive.bgJobCountInMaxPeriod;
        expectedStats.quotaCutoffTimeElapsed = originalStatsActive.quotaCutoffTimeElapsed;
        final ExecutionStats newStatsActive = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", ACTIVE_INDEX);
        // Stats for the same bucket should use the same object.
        assertTrue(originalStatsActive == newStatsActive);
        assertEquals(expectedStats, newStatsActive);

        expectedStats.windowSizeMs = originalStatsWorking.windowSizeMs;
        expectedStats.invalidTimeElapsed = originalStatsWorking.invalidTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsWorking.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsWorking.bgJobCountInWindow;
        expectedStats.quotaCutoffTimeElapsed = originalStatsWorking.quotaCutoffTimeElapsed;
        final ExecutionStats newStatsWorking = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", WORKING_INDEX);
        assertTrue(originalStatsWorking == newStatsWorking);
        assertNotEquals(expectedStats, newStatsWorking);

        expectedStats.windowSizeMs = originalStatsFrequent.windowSizeMs;
        expectedStats.invalidTimeElapsed = originalStatsFrequent.invalidTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsFrequent.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsFrequent.bgJobCountInWindow;
        expectedStats.quotaCutoffTimeElapsed = originalStatsFrequent.quotaCutoffTimeElapsed;
        final ExecutionStats newStatsFrequent = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", FREQUENT_INDEX);
        assertTrue(originalStatsFrequent == newStatsFrequent);
        assertNotEquals(expectedStats, newStatsFrequent);

        expectedStats.windowSizeMs = originalStatsRare.windowSizeMs;
        expectedStats.invalidTimeElapsed = originalStatsRare.invalidTimeElapsed;
        expectedStats.executionTimeInWindowMs = originalStatsRare.executionTimeInWindowMs;
        expectedStats.bgJobCountInWindow = originalStatsRare.bgJobCountInWindow;
        expectedStats.quotaCutoffTimeElapsed = originalStatsRare.quotaCutoffTimeElapsed;
        final ExecutionStats newStatsRare = mQuotaController.getExecutionStatsLocked(0,
                "com.android.test", RARE_INDEX);
        assertTrue(originalStatsRare == newStatsRare);
        assertEquals(expectedStats, newStatsRare);
    }

    @Test
    public void testMaybeScheduleCleanupAlarmLocked() {
        // No sessions saved yet.
        mQuotaController.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_CLEANUP), any(), any());

        // Test with only one timing session saved.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long end = now - (6 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        mQuotaController.saveTimingSession(0, "com.android.test",
                new TimingSession(now - 6 * HOUR_IN_MILLIS, end, 1));
        mQuotaController.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(end + 24 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());

        // Test with new (more recent) timing sessions saved. AlarmManger shouldn't be called again.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleCleanupAlarmLocked();
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(end + 24 * HOUR_IN_MILLIS), eq(TAG_CLEANUP), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Active() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Active window size is 10 minutes.
        final int standbyBucket = ACTIVE_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(SOURCE_USER_ID, SOURCE_PACKAGE,
                standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Test with timing sessions out of window but still under max execution limit.
        final long expectedAlarmTime =
                (now - 18 * HOUR_IN_MILLIS) + 24 * HOUR_IN_MILLIS
                        + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 18 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 12 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 7 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(SOURCE_USER_ID, SOURCE_PACKAGE,
                standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 2 * HOUR_IN_MILLIS, 55 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(SOURCE_USER_ID, SOURCE_PACKAGE,
                standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        JobStatus jobStatus = createJobStatus("testMaybeScheduleStartAlarmLocked_Active", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * MINUTE_IN_MILLIS);
        // Timer has only been going for 5 minutes in the past 10 minutes, which is under the window
        // size limit, but the total execution time for the past 24 hours is 6 hours, so the job no
        // longer has quota.
        assertEquals(0, mQuotaController.getRemainingExecutionTimeLocked(jobStatus));
        mQuotaController.maybeScheduleStartAlarmLocked(SOURCE_USER_ID, SOURCE_PACKAGE,
                standbyBucket);
        verify(mAlarmManager, times(1)).set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK),
                any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_WorkingSet() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long end = now - (2 * HOUR_IN_MILLIS - 5 * MINUTE_IN_MILLIS);
        // Counting backwards, the quota will come back one minute before the end.
        final long expectedAlarmTime =
                end - MINUTE_IN_MILLIS + 2 * HOUR_IN_MILLIS
                        + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                new TimingSession(now - 2 * HOUR_IN_MILLIS, end, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - (50 * MINUTE_IN_MILLIS), 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 30 * MINUTE_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Frequent() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Frequent window size is 8 hours.
        final int standbyBucket = FREQUENT_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 10 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        final long expectedAlarmTime =
                start + 8 * HOUR_IN_MILLIS + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_Rare() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        // Rare window size is 24 hours.
        final int standbyBucket = RARE_INDEX;

        // No sessions saved yet.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions out of window.
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 25 * HOUR_IN_MILLIS, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test with timing sessions in window but still in quota.
        final long start = now - (6 * HOUR_IN_MILLIS);
        // Counting backwards, the first minute in the session is over the allowed time, so it
        // needs to be excluded.
        final long expectedAlarmTime =
                start + MINUTE_IN_MILLIS + 24 * HOUR_IN_MILLIS
                        + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(start, 5 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Add some more sessions, but still in quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 3 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, 1));
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());

        // Test when out of quota.
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - HOUR_IN_MILLIS, 2 * MINUTE_IN_MILLIS, 1));
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // Alarm already scheduled, so make sure it's not scheduled again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that the start alarm is properly rescheduled if the app's bucket is changed. */
    @Test
    public void testMaybeScheduleStartAlarmLocked_BucketChange() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        // Affects rare bucket
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 12 * HOUR_IN_MILLIS, 9 * MINUTE_IN_MILLIS, 3));
        // Affects frequent and rare buckets
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 4 * HOUR_IN_MILLIS, 4 * MINUTE_IN_MILLIS, 3));
        // Affects working, frequent, and rare buckets
        final long outOfQuotaTime = now - HOUR_IN_MILLIS;
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(outOfQuotaTime, 7 * MINUTE_IN_MILLIS, 10));
        // Affects all buckets
        mQuotaController.saveTimingSession(0, "com.android.test",
                createTimingSession(now - 5 * MINUTE_IN_MILLIS, 3 * MINUTE_IN_MILLIS, 3));

        InOrder inOrder = inOrder(mAlarmManager);

        // Start in ACTIVE bucket.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", ACTIVE_INDEX);
        inOrder.verify(mAlarmManager, never())
                .set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(), any());
        inOrder.verify(mAlarmManager, never()).cancel(any(AlarmManager.OnAlarmListener.class));

        // And down from there.
        final long expectedWorkingAlarmTime =
                outOfQuotaTime + (2 * HOUR_IN_MILLIS)
                        + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", WORKING_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedWorkingAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedFrequentAlarmTime =
                outOfQuotaTime + (8 * HOUR_IN_MILLIS)
                        + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", FREQUENT_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedFrequentAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        final long expectedRareAlarmTime =
                outOfQuotaTime + (24 * HOUR_IN_MILLIS)
                        + mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS;
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", RARE_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedRareAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        // And back up again.
        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", FREQUENT_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedFrequentAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", WORKING_INDEX);
        inOrder.verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedWorkingAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());

        mQuotaController.maybeScheduleStartAlarmLocked(0, "com.android.test", ACTIVE_INDEX);
        inOrder.verify(mAlarmManager, never()).set(anyInt(), anyLong(), eq(TAG_QUOTA_CHECK), any(),
                any());
        inOrder.verify(mAlarmManager, times(1)).cancel(any(AlarmManager.OnAlarmListener.class));
    }


    /**
     * Tests that the start alarm is properly rescheduled if the earliest session that contributes
     * to the app being out of quota contributes less than the quota buffer time.
     */
    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_DefaultValues() {
        // Use the default values
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedBufferSize() {
        // Make sure any new value is used correctly.
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS *= 2;
        mQuotaController.onConstantsUpdatedLocked();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedAllowedTime() {
        // Make sure any new value is used correctly.
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS /= 2;
        mQuotaController.onConstantsUpdatedLocked();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedMaxTime() {
        // Make sure any new value is used correctly.
        mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS /= 2;
        mQuotaController.onConstantsUpdatedLocked();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    @Test
    public void testMaybeScheduleStartAlarmLocked_SmallRollingQuota_UpdatedEverything() {
        // Make sure any new value is used correctly.
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS *= 2;
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS /= 2;
        mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS /= 2;
        mQuotaController.onConstantsUpdatedLocked();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck();
        mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE).clear();
        runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck();
    }

    private void runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_AllowedTimeCheck() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;
        final long contributionMs = mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS / 2;
        final long remainingTimeMs =
                mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS - contributionMs;

        // Session straddles edge of bucket window. Only the contribution should be counted towards
        // the quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (2 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS + contributionMs, 3));
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - HOUR_IN_MILLIS, remainingTimeMs, 2));
        // Expected alarm time should be when the app will have QUOTA_BUFFER_MS time of quota, which
        // is 2 hours + (QUOTA_BUFFER_MS - contributionMs) after the start of the second session.
        final long expectedAlarmTime = now - HOUR_IN_MILLIS + 2 * HOUR_IN_MILLIS
                + (mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS - contributionMs);
        mQuotaController.maybeScheduleStartAlarmLocked(SOURCE_USER_ID, SOURCE_PACKAGE,
                standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }


    private void runTestMaybeScheduleStartAlarmLocked_SmallRollingQuota_MaxTimeCheck() {
        // saveTimingSession calls maybeScheduleCleanupAlarmLocked which interferes with these tests
        // because it schedules an alarm too. Prevent it from doing so.
        spyOn(mQuotaController);
        doNothing().when(mQuotaController).maybeScheduleCleanupAlarmLocked();

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        // Working set window size is 2 hours.
        final int standbyBucket = WORKING_INDEX;
        final long contributionMs = mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS / 2;
        final long remainingTimeMs =
                mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS - contributionMs;

        // Session straddles edge of 24 hour window. Only the contribution should be counted towards
        // the quota.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - (24 * HOUR_IN_MILLIS + 3 * MINUTE_IN_MILLIS),
                        3 * MINUTE_IN_MILLIS + contributionMs, 3));
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 20 * HOUR_IN_MILLIS, remainingTimeMs, 300));
        // Expected alarm time should be when the app will have QUOTA_BUFFER_MS time of quota, which
        // is 24 hours + (QUOTA_BUFFER_MS - contributionMs) after the start of the second session.
        final long expectedAlarmTime = now - 20 * HOUR_IN_MILLIS
                //+ mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS
                + 24 * HOUR_IN_MILLIS
                + (mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS - contributionMs);
        mQuotaController.maybeScheduleStartAlarmLocked(SOURCE_USER_ID, SOURCE_PACKAGE,
                standbyBucket);
        verify(mAlarmManager, times(1))
                .set(anyInt(), eq(expectedAlarmTime), eq(TAG_QUOTA_CHECK), any(), any());
    }

    /** Tests that QuotaController doesn't throttle if throttling is turned off. */
    @Test
    public void testThrottleToggling() throws Exception {
        setDischarging();
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS, 4));
        JobStatus jobStatus = createJobStatus("testThrottleToggling", 1);
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        mConstants.USE_HEARTBEATS = true;
        mQuotaController.onConstantsUpdatedLocked();
        Thread.sleep(SECOND_IN_MILLIS); // Job updates are done in the background.
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));

        mConstants.USE_HEARTBEATS = false;
        mQuotaController.onConstantsUpdatedLocked();
        Thread.sleep(SECOND_IN_MILLIS); // Job updates are done in the background.
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS = 5 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS = 2 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS = 15 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS = 30 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = 45 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS = 60 * MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS = 3 * HOUR_IN_MILLIS;

        mQuotaController.onConstantsUpdatedLocked();

        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs());
        assertEquals(2 * MINUTE_IN_MILLIS, mQuotaController.getInQuotaBufferMs());
        assertEquals(15 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(30 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(45 * MINUTE_IN_MILLIS,
                mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(60 * MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
        assertEquals(3 * HOUR_IN_MILLIS, mQuotaController.getMaxExecutionTimeMs());
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS = -MINUTE_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS = -MINUTE_IN_MILLIS;

        mQuotaController.onConstantsUpdatedLocked();

        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs());
        assertEquals(0, mQuotaController.getInQuotaBufferMs());
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(MINUTE_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
        assertEquals(HOUR_IN_MILLIS, mQuotaController.getMaxExecutionTimeMs());

        // Test larger than a day. Controller should cap at one day.
        mConstants.QUOTA_CONTROLLER_ALLOWED_TIME_PER_PERIOD_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_IN_QUOTA_BUFFER_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_ACTIVE_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_WORKING_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_FREQUENT_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_WINDOW_SIZE_RARE_MS = 25 * HOUR_IN_MILLIS;
        mConstants.QUOTA_CONTROLLER_MAX_EXECUTION_TIME_MS = 25 * HOUR_IN_MILLIS;

        mQuotaController.onConstantsUpdatedLocked();

        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getAllowedTimePerPeriodMs());
        assertEquals(5 * MINUTE_IN_MILLIS, mQuotaController.getInQuotaBufferMs());
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[ACTIVE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[WORKING_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[FREQUENT_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getBucketWindowSizes()[RARE_INDEX]);
        assertEquals(24 * HOUR_IN_MILLIS, mQuotaController.getMaxExecutionTimeMs());
    }

    /** Tests that TimingSessions aren't saved when the device is charging. */
    @Test
    public void testTimerTracking_Charging() {
        setCharging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_Charging", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when the device is discharging. */
    @Test
    public void testTimerTracking_Discharging() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_Discharging", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_Discharging", 2);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);

        JobStatus jobStatus3 = createJobStatus("testTimerTracking_Discharging", 3);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus3);
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that TimingSessions are saved properly when the device alternates between
     * charging and discharging.
     */
    @Test
    public void testTimerTracking_ChargingAndDischarging() {
        JobStatus jobStatus = createJobStatus("testTimerTracking_ChargingAndDischarging", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_ChargingAndDischarging", 2);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        JobStatus jobStatus3 = createJobStatus("testTimerTracking_ChargingAndDischarging", 3);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // A job starting while charging. Only the portion that runs during the discharging period
        // should be counted.
        setCharging();

        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, jobStatus, true);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // One job starts while discharging, spans a charging session, and ends after the charging
        // session. Only the portions during the discharging periods should be counted. This should
        // result in two TimingSessions. A second job starts while discharging and ends within the
        // charging session. Only the portion during the first discharging portion should be
        // counted. A third job starts and ends within the charging session. The third job
        // shouldn't be included in either job count.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setCharging();
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 2));
        mQuotaController.prepareForExecutionLocked(jobStatus3);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // A job starting while discharging and ending while charging. Only the portion that runs
        // during the discharging period should be counted.
        setDischarging();
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        setCharging();
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that TimingSessions are saved properly when all the jobs are background jobs. */
    @Test
    public void testTimerTracking_AllBackground() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_AllBackground", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        List<TimingSession> expected = new ArrayList<>();

        // Test single job.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        expected.add(createTimingSession(start, 5 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        // Test overlapping jobs.
        JobStatus jobStatus2 = createJobStatus("testTimerTracking_AllBackground", 2);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus2, null);

        JobStatus jobStatus3 = createJobStatus("testTimerTracking_AllBackground", 3);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus3, null);

        advanceElapsedClock(SECOND_IN_MILLIS);

        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobStatus3);
        advanceElapsedClock(20 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus2, null, false);
        expected.add(createTimingSession(start, MINUTE_IN_MILLIS, 3));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /** Tests that Timers don't count foreground jobs. */
    @Test
    public void testTimerTracking_AllForeground() {
        setDischarging();

        JobStatus jobStatus = createJobStatus("testTimerTracking_AllForeground", 1);
        jobStatus.uidActive = true;
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);

        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(5 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobStatus, null, false);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that Timers properly track overlapping foreground and background jobs.
     */
    @Test
    public void testTimerTracking_ForegroundAndBackground() {
        setDischarging();

        JobStatus jobBg1 = createJobStatus("testTimerTracking_ForegroundAndBackground", 1);
        JobStatus jobBg2 = createJobStatus("testTimerTracking_ForegroundAndBackground", 2);
        JobStatus jobFg3 = createJobStatus("testTimerTracking_ForegroundAndBackground", 3);
        jobFg3.uidActive = true;
        mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
        mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
        mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        assertNull(mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
        List<TimingSession> expected = new ArrayList<>();

        // UID starts out inactive.
        long start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobBg1);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        expected.add(createTimingSession(start, 10 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job starts while inactive, spans an entire active session, and ends after the
        // active session.
        // Fg job starts after the bg job and ends before the bg job.
        // Entire bg job duration should be counted since it started before active session. However,
        // count should only be 1 since Timer shouldn't count fg jobs.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
        mQuotaController.prepareForExecutionLocked(jobBg2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobFg3);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        expected.add(createTimingSession(start, 30 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));

        advanceElapsedClock(SECOND_IN_MILLIS);

        // Bg job 1 starts, then fg job starts. Bg job 1 job ends. Shortly after, uid goes
        // "inactive" and then bg job 2 starts. Then fg job ends.
        // This should result in two TimingSessions with a count of one each.
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.maybeStartTrackingJobLocked(jobBg1, null);
        mQuotaController.maybeStartTrackingJobLocked(jobBg2, null);
        mQuotaController.maybeStartTrackingJobLocked(jobFg3, null);
        mQuotaController.prepareForExecutionLocked(jobBg1);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.prepareForExecutionLocked(jobFg3);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg1, jobBg1, true);
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        advanceElapsedClock(10 * SECOND_IN_MILLIS); // UID "inactive" now
        start = JobSchedulerService.sElapsedRealtimeClock.millis();
        mQuotaController.prepareForExecutionLocked(jobBg2);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobFg3, null, false);
        advanceElapsedClock(10 * SECOND_IN_MILLIS);
        mQuotaController.maybeStopTrackingJobLocked(jobBg2, null, false);
        expected.add(createTimingSession(start, 20 * SECOND_IN_MILLIS, 1));
        assertEquals(expected, mQuotaController.getTimingSessions(SOURCE_USER_ID, SOURCE_PACKAGE));
    }

    /**
     * Tests that a job is properly updated and JobSchedulerService is notified when a job reaches
     * its quota.
     */
    @Test
    public void testTracking_OutOfQuota() {
        JobStatus jobStatus = createJobStatus("testTracking_OutOfQuota", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        // Now the package only has two seconds to run.
        final long remainingTimeMs = 2 * SECOND_IN_MILLIS;
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(
                        JobSchedulerService.sElapsedRealtimeClock.millis() - HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS - remainingTimeMs, 1));

        // Start the job.
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(1))
                .onControllerStateChanged();
        assertFalse(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
    }

    /**
     * Tests that a job is properly handled when it's at the edge of its quota and the old quota is
     * being phased out.
     */
    @Test
    public void testTracking_RollingQuota() {
        JobStatus jobStatus = createJobStatus("testTracking_OutOfQuota", 1);
        mQuotaController.maybeStartTrackingJobLocked(jobStatus, null);
        setStandbyBucket(WORKING_INDEX, jobStatus); // 2 hour window
        Handler handler = mQuotaController.getHandler();
        spyOn(handler);

        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final long remainingTimeMs = SECOND_IN_MILLIS;
        // The package only has one second to run, but this session is at the edge of the rolling
        // window, so as the package "reaches its quota" it will have more to keep running.
        mQuotaController.saveTimingSession(SOURCE_USER_ID, SOURCE_PACKAGE,
                createTimingSession(now - 2 * HOUR_IN_MILLIS,
                        10 * MINUTE_IN_MILLIS - remainingTimeMs, 1));

        assertEquals(remainingTimeMs, mQuotaController.getRemainingExecutionTimeLocked(jobStatus));
        // Start the job.
        mQuotaController.prepareForExecutionLocked(jobStatus);
        advanceElapsedClock(remainingTimeMs);

        // Wait for some extra time to allow for job processing.
        verify(mJobSchedulerService,
                timeout(remainingTimeMs + 2 * SECOND_IN_MILLIS).times(0))
                .onControllerStateChanged();
        assertTrue(jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA));
        // The job used up the remaining quota, but in that time, the same amount of time in the
        // old TimingSession also fell out of the quota window, so it should still have the same
        // amount of remaining time left its quota.
        assertEquals(remainingTimeMs,
                mQuotaController.getRemainingExecutionTimeLocked(SOURCE_USER_ID, SOURCE_PACKAGE));
        verify(handler, atLeast(1)).sendMessageDelayed(any(), eq(remainingTimeMs));
    }
}
