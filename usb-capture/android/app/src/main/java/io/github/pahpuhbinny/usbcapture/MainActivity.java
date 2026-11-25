package io.github.pahpuhbinny.usbcapture;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.log.CrashHandler;
import io.github.pahpuhbinny.usbcapture.render.DisplayParams;
import io.github.pahpuhbinny.usbcapture.render.Geometry;
import io.github.pahpuhbinny.usbcapture.ui.OverlayView;
import io.github.pahpuhbinny.usbcapture.ui.PanelBuilder;
import io.github.pahpuhbinny.usbcapture.ui.SettingsPanels;
import io.github.pahpuhbinny.usbcapture.uvc.UvcControls;

/**
 * The one screen of the app: full-screen video with auto-hiding controls.
 *
 * Play mode keeps things simple (big buttons, quick settings sheet).
 * Expert mode adds a side panel with every setting, picture controls,
 * exposure/focus aids and live diagnostics.
 */
public final class MainActivity extends AppCompatActivity implements CaptureController.Ui, SettingsPanels.Host {
    private static final String TAG = "Main";
    private static final int REQ_START = 10, REQ_AUDIO = 11, REQ_STORAGE = 12, REQ_CAMERA = 13;

    private CaptureController ctl;
    private Prefs prefs;
    private SettingsPanels panels;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private SurfaceView video;
    private OverlayView overlay;
    private View touch;
    private View status;
    private ImageView statusIcon;
    private View statusProgress;
    private TextView statusTitle, statusDetail;
    private LinearLayout statusButtons;
    private TextView stats;
    private View recBadge;
    private TextView recTime;
    private View topBar;
    private TextView title, subtitle;
    private MaterialButton btnMode;
    private View bottomBar;
    private ImageButton btnShot, btnRecord, btnReplay, btnMute, btnMic, btnScale, btnFullscreen, btnLock, btnPip, btnQuick;
    private View panel;
    private ChipGroup panelTabs;
    private LinearLayout panelContent;
    private ScrollView panelScroll;
    private MaterialButton btnUnlock;
    private TextView toastView;
    private View flash;

    private boolean expert, fullscreen, controlsShown = true, locked, panelOpen, inPip, started;
    private String panelTab = "Video";
    private CaptureController.State state = CaptureController.State.NO_DEVICE;
    private CaptureController.StreamInfo info;
    private UvcControls controls;
    private String audioStatus = "";
    private boolean recording;
    private BottomSheetDialog quickSheet;
    private LinearLayout quickContent;
    private ScrollView quickScroll;
    private Insets insets = Insets.NONE;
    private GestureDetector gestures;
    private ScaleGestureDetector scaleGestures;
    private boolean gestureDirty;
    private Runnable pendingStorageAction;

    // ==================================================================
    // Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        prefs = new Prefs(this);
        bindViews();
        ctl = new CaptureController(this);
        panels = new SettingsPanels(this);
        video.getHolder().addCallback(ctl.renderer);
        expert = prefs.expertMode();
        fullscreen = prefs.startFullscreen();
        setupInsets();
        setupButtons();
        setupGestures();
        setupBack();
        applyUiPrefs();
        applyMode();
        applyFullscreen();
        ctl.attach(this);
        askStartupPermissions();
        ui.postDelayed(this::checkCrashReports, 900);
        Intent i = getIntent();
        AppLog.i(TAG, "Activity created (" + (i != null ? i.getAction() : "no intent") + "), "
                + (expert ? "Expert" : "Play") + " mode");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.i(TAG, "New intent: " + intent.getAction());
        ctl.onDeviceIntent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        ctl.onStart();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ui.removeCallbacks(tick);
        if (!inPip) ctl.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        ctl.detach();
        ctl.release();
        AppLog.i(TAG, "Activity destroyed");
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        layoutPanel();
        overlay.invalidate();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && prefs.autoPip() && isShowingVideo()) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        inPip = inPictureInPictureMode;
        AppLog.i(TAG, inPip ? "Entered picture-in-picture" : "Left picture-in-picture");
        if (inPip) {
            if (quickSheet != null) quickSheet.dismiss();
            closePanel();
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            stats.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            toastView.setVisibility(View.GONE);
        } else {
            if (!started) ctl.onStop();
            onState(state, statusTitle.getText().toString(), statusDetail.getText().toString());
            applyUiPrefs();
            showControls();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recording) {
                long ms = SystemClock.elapsedRealtime() - ctl.recorder.recordingStartMs();
                long s = ms / 1000;
                recTime.setText(String.format(Locale.US, "REC %d:%02d:%02d · %.0f MB", s / 3600, (s / 60) % 60, s % 60,
                        ctl.recorder.recordedBytes() / 1e6));
            }
            if (prefs.meters()) overlay.setLevels(ctl.audio.levels());
            if (started) ui.postDelayed(this, prefs.meters() ? 66 : 250);
        }
    };

    // ==================================================================
    // Setup

    private void bindViews() {
        root = findViewById(R.id.root);
        video = findViewById(R.id.video);
        overlay = findViewById(R.id.overlay);
        touch = findViewById(R.id.touch);
        status = findViewById(R.id.status);
        statusIcon = findViewById(R.id.statusIcon);
        statusProgress = findViewById(R.id.statusProgress);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        statusButtons = findViewById(R.id.statusButtons);
        stats = findViewById(R.id.stats);
        recBadge = findViewById(R.id.recBadge);
        recTime = findViewById(R.id.recTime);
        topBar = findViewById(R.id.topBar);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        btnMode = findViewById(R.id.btnMode);
        bottomBar = findViewById(R.id.bottomBar);
        btnShot = findViewById(R.id.btnShot);
        btnRecord = findViewById(R.id.btnRecord);
        btnReplay = findViewById(R.id.btnReplay);
        btnMute = findViewById(R.id.btnMute);
        btnMic = findViewById(R.id.btnMic);
        btnScale = findViewById(R.id.btnScale);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnLock = findViewById(R.id.btnLock);
        btnPip = findViewById(R.id.btnPip);
        btnQuick = findViewById(R.id.btnQuick);
        panel = findViewById(R.id.panel);
        panelTabs = findViewById(R.id.panelTabs);
        panelContent = findViewById(R.id.panelContent);
        panelScroll = findViewById(R.id.panelScroll);
        btnUnlock = findViewById(R.id.btnUnlock);
        toastView = findViewById(R.id.toast);
        flash = findViewById(R.id.flash);
    }

    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, wi) -> {
            insets = wi.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            layoutForInsets();
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void layoutForInsets() {
        Insets in = insets;
        topBar.setPadding(in.left + dp(12), in.top + dp(8), in.right + dp(12), dp(8));
        View inner = ((ViewGroup) bottomBar).getChildAt(0);
        inner.setPadding(in.left + dp(8), dp(20), in.right + dp(8), in.bottom + dp(10));
        panel.setPadding(0, in.top, in.right, in.bottom);
        Insets vi = fullscreen ? Insets.NONE : in;
        setMargins(video, vi.left, vi.top, vi.right, vi.bottom);
        setMargins(overlay, vi.left, vi.top, vi.right, vi.bottom);
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) stats.getLayoutParams();
        sp.leftMargin = in.left + dp(12);
        sp.topMargin = in.top + dp(72);
        stats.setLayoutParams(sp);
        FrameLayout.LayoutParams rp = (FrameLayout.LayoutParams) recBadge.getLayoutParams();
        rp.topMargin = in.top + dp(12);
        recBadge.setLayoutParams(rp);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) toastView.getLayoutParams();
        tp.bottomMargin = in.bottom + dp(104);
        toastView.setLayoutParams(tp);
        overlay.setInsets(dp(64) + in.top - vi.top, 0);
        layoutPanel();
    }

    private static void setMargins(View v, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (p.leftMargin == l && p.topMargin == t && p.rightMargin == r && p.bottomMargin == b) return;
        p.setMargins(l, t, r, b);
        v.setLayoutParams(p);
    }

    private void layoutPanel() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (h > w) {
            p.width = ViewGroup.LayoutParams.MATCH_PARENT;
            p.height = (int) (h * 0.58f);
            p.gravity = Gravity.BOTTOM;
        } else {
            p.width = Math.min(dp(400), (int) (w * 0.5f));
            p.height = ViewGroup.LayoutParams.MATCH_PARENT;
            p.gravity = Gravity.END;
        }
        panel.setLayoutParams(p);
    }

    private void haptic(View v) {
        if (prefs.hapticFeedback()) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void setupButtons() {
        btnShot.setOnClickListener(v -> {
            haptic(v);
            takeScreenshot();
        });
        btnRecord.setOnClickListener(v -> {
            haptic(v);
            toggleRecording();
        });
        btnReplay.setOnClickListener(v -> {
            haptic(v);
            withStorage(() -> {
                toast("Saving instant replay…");
                ctl.saveReplay();
            });
        });
        btnMute.setOnClickListener(v -> {
            haptic(v);
            toggleMute();
        });
        btnMic.setOnClickListener(v -> {
            haptic(v);
            boolean mute = !prefs.audio().micMute;
            prefs.put("mic_mute", mute);
            ctl.updateAudio();
            applyUiPrefs();
            toast(mute ? "Commentary mic muted" : "Commentary mic on");
        });
        btnScale.setOnClickListener(v -> {
            haptic(v);
            cycleScale();
        });
        btnFullscreen.setOnClickListener(v -> {
            haptic(v);
            fullscreen = !fullscreen;
            applyFullscreen();
        });
        btnLock.setOnClickListener(v -> {
            haptic(v);
            setLocked(true);
        });
        btnPip.setOnClickListener(v -> {
            haptic(v);
            enterPip();
        });
        btnQuick.setOnClickListener(v -> {
            haptic(v);
            openSettings();
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnLog).setOnClickListener(v -> openLogs());
        findViewById(R.id.btnPanelClose).setOnClickListener(v -> closePanel());
        btnMode.setOnClickListener(v -> setExpert(!expert));
        btnUnlock.setOnClickListener(v -> toast("Press and hold to unlock"));
        btnUnlock.setOnLongClickListener(v -> {
            haptic(v);
            setLocked(false);
            return true;
        });
        // Long-press shortcuts on the bottom bar.
        btnRecord.setOnLongClickListener(v -> {
            setExpert(true);
            panelTab = "Record";
            openPanel();
            return true;
        });
        btnMute.setOnLongClickListener(v -> {
            if (expert) {
                panelTab = "Audio";
                openPanel();
            } else {
                openSettings();
            }
            return true;
        });
        btnScale.setOnLongClickListener(v -> {
            if (expert) {
                panelTab = "Display";
                openPanel();
            } else {
                openSettings();
            }
            return true;
        });
    }

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (locked) {
                    showUnlockHint();
                    return true;
                }
                if (panelOpen && h() > w()) {
                    closePanel();
                    return true;
                }
                if (controlsShown) hideControls(true);
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (locked) return true;
                DisplayParams p = ctl.displayParams();
                if (p.zoom > 1.01f) {
                    p.zoom = 1f;
                    p.panX = p.panY = 0f;
                    toast("Zoom reset");
                } else {
                    p.scaleMode = p.scaleMode == DisplayParams.SCALE_FILL ? DisplayParams.SCALE_FIT : DisplayParams.SCALE_FILL;
                    toast(p.scaleMode == DisplayParams.SCALE_FILL ? "Fill screen (edges cropped)" : "Fit whole picture");
                }
                ctl.setDisplayParams(p);
                overlay.setParams(p);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                if (locked) return false;
                DisplayParams p = ctl.displayParams();
                if (p.zoom <= 1.01f || info == null || info.width <= 0) return false;
                Geometry g = Geometry.compute(p, video.getWidth(), video.getHeight(), info.width, info.height);
                float maxX = Math.max(1f, (g.rect.width() - video.getWidth()) / 2f);
                float maxY = Math.max(1f, (g.rect.height() - video.getHeight()) / 2f);
                p.panX = Math.max(-1f, Math.min(1f, p.panX + dx / maxX));
                p.panY = Math.max(-1f, Math.min(1f, p.panY + dy / maxY));
                preview(p);
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector d) {
                if (locked) return false;
                DisplayParams p = ctl.displayParams();
                p.zoom = Math.max(1f, Math.min(8f, p.zoom * d.getScaleFactor()));
                if (p.zoom <= 1.001f) p.panX = p.panY = 0f;
                preview(p);
                return true;
            }
        });
        touch.setOnTouchListener((v, e) -> {
            scaleGestures.onTouchEvent(e);
            if (!scaleGestures.isInProgress()) gestures.onTouchEvent(e);
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && gestureDirty) {
                gestureDirty = false;
                ctl.setDisplayParams(ctl.displayParams());
            }
            return true;
        });
    }

    private void preview(DisplayParams p) {
        gestureDirty = true;
        ctl.setDisplayParams(p);
        overlay.setParams(p);
    }

    private int w() {
        return root.getWidth();
    }

    private int h() {
        return root.getHeight();
    }

    private void setupBack() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (locked) {
                    showUnlockHint();
                } else if (panelOpen) {
                    closePanel();
                } else {
                    finish();
                }
            }
        });
    }

    // ==================================================================
    // Mode, fullscreen, controls

    @Override
    public void setExpert(boolean on) {
        if (expert == on) {
            applyMode();
            return;
        }
        expert = on;
        prefs.put("expert", on);
        if (quickSheet != null) quickSheet.dismiss();
        if (!on) closePanel();
        applyMode();
        applyUiPrefs();
        AppLog.i(TAG, "Switched to " + (on ? "Expert" : "Play") + " mode");
        toast(on ? "Expert mode: every setting, picture controls and diagnostics" : "Play mode: simple controls");
    }

    private void applyMode() {
        btnMode.setText(expert ? R.string.expert_mode : R.string.play_mode);
        btnMode.setIconResource(expert ? R.drawable.ic_build : R.drawable.ic_gamepad);
        applyStatsVisibility();
    }

    private void applyStatsVisibility() {
        boolean show = prefs.showStats() && !inPip && (state == CaptureController.State.STREAMING
                || state == CaptureController.State.NO_SIGNAL || state == CaptureController.State.TEST_PATTERN);
        stats.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void applyFullscreen() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), root);
        if (fullscreen) {
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
        }
        btnFullscreen.setImageResource(fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        layoutForInsets();
    }

    private final Runnable hideRunnable = () -> hideControls(false);

    private void showControls() {
        if (inPip) return;
        controlsShown = true;
        if (!locked) {
            fade(topBar, true);
            fade(bottomBar, true);
        }
        scheduleHide();
    }

    private void hideControls(boolean userAsked) {
        if (panelOpen || inPip) return;
        if (!userAsked && !isShowingVideo()) return;
        controlsShown = false;
        fade(topBar, false);
        fade(bottomBar, false);
        if (fullscreen) applyFullscreen();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideRunnable);
        int s = prefs.autoHideSeconds();
        if (s > 0 && !panelOpen) ui.postDelayed(hideRunnable, s * 1000L);
    }

    private static void fade(View v, boolean in) {
        v.animate().cancel();
        if (in) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).setDuration(150).start();
        } else {
            v.animate().alpha(0f).setDuration(200).withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
        }
    }

    private boolean isShowingVideo() {
        return state == CaptureController.State.STREAMING || state == CaptureController.State.TEST_PATTERN
                || state == CaptureController.State.NO_SIGNAL;
    }

    private void setLocked(boolean on) {
        locked = on;
        if (on) {
            closePanel();
            if (quickSheet != null) quickSheet.dismiss();
            fade(topBar, false);
            fade(bottomBar, false);
            showUnlockHint();
            toast("Touch locked. Press and hold the button to unlock.");
        } else {
            btnUnlock.setVisibility(View.GONE);
            showControls();
            toast("Unlocked");
        }
        AppLog.i(TAG, on ? "Touch locked" : "Touch unlocked");
    }

    private final Runnable hideUnlock = () -> btnUnlock.setVisibility(View.GONE);

    private void showUnlockHint() {
        btnUnlock.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideUnlock);
        ui.postDelayed(hideUnlock, 2500);
    }

    // ==================================================================
    // Actions

    private void takeScreenshot() {
        if (!isShowingVideo()) {
            toast("No picture to capture yet");
            return;
        }
        withStorage(() -> {
            flash.setAlpha(0.75f);
            flash.animate().alpha(0f).setDuration(280).start();
            ctl.takeScreenshot();
        });
    }

    private void toggleRecording() {
        if (!recording && !isShowingVideo()) {
            toast("Nothing to record yet");
            return;
        }
        withStorage(() -> {
            if (!recording && prefs.recordAudio() && !ctl.hasAudioPermission() && prefs.audio().enabled) {
                toast("Recording without sound (microphone permission not granted)");
            }
            ctl.toggleRecording();
        });
    }

    private void toggleMute() {
        boolean on = !(prefs.audio().enabled && prefs.audio().monitor);
        if (on && !ctl.hasAudioPermission()) {
            requestAudioPermission();
            return;
        }
        prefs.put("audio_monitor", on);
        if (on) prefs.put("audio_on", true);
        ctl.updateAudio();
        applyUiPrefs();
        toast(on ? "Sound on" : "Sound muted");
    }

    private void cycleScale() {
        DisplayParams p = ctl.displayParams();
        int[] order = {DisplayParams.SCALE_FIT, DisplayParams.SCALE_FILL, DisplayParams.SCALE_STRETCH, DisplayParams.SCALE_INTEGER};
        String[] names = {"Fit whole picture", "Fill screen (edges cropped)", "Stretch to screen", "Pixel-perfect (integer scale)"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == p.scaleMode) idx = i;
        idx = (idx + 1) % order.length;
        p.scaleMode = order[idx];
        p.zoom = 1f;
        p.panX = p.panY = 0f;
        ctl.setDisplayParams(p);
        overlay.setParams(p);
        toast(names[idx]);
        rebuildPanel();
    }

    private void openSettings() {
        if (expert) {
            if (panelOpen) closePanel();
            else openPanel();
        } else {
            openQuickSheet();
        }
    }

    private void withStorage(Runnable r) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            r.run();
            return;
        }
        pendingStorageAction = r;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    private void enterPip() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toast("Picture-in-picture isn't available on this phone");
            return;
        }
        try {
            enterPictureInPictureMode(pipParams());
        } catch (Throwable t) {
            AppLog.w(TAG, "Could not enter picture-in-picture", t);
            toast("Picture-in-picture is turned off for this app in Android settings");
        }
    }

    private PictureInPictureParams pipParams() {
        int w = 16, h = 9;
        if (info != null && info.width > 0 && info.height > 0) {
            w = info.width;
            h = info.height;
        }
        float r = (float) w / h;
        if (r > 2.39f) {
            w = 239;
            h = 100;
        } else if (r < 1 / 2.39f) {
            w = 100;
            h = 239;
        }
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(w, h));
        android.graphics.Rect hint = new android.graphics.Rect();
        if (video.getGlobalVisibleRect(hint) && !hint.isEmpty()) b.setSourceRectHint(hint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(prefs.autoPip() && isShowingVideo());
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    private void updatePipParams() {
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                setPictureInPictureParams(pipParams());
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================================================================
    // Settings panel / sheet

    private void openPanel() {
        if (inPip) return;
        panelOpen = true;
        layoutPanel();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(150).start();
        buildPanelTabs();
        buildPanel();
        ui.removeCallbacks(hideRunnable);
        fade(topBar, true);
        fade(bottomBar, true);
        controlsShown = true;
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        panel.setVisibility(View.GONE);
        scheduleHide();
    }

    private void buildPanelTabs() {
        panelTabs.removeAllViews();
        for (String t : SettingsPanels.TABS) {
            Chip c = new Chip(this);
            c.setText(t);
            c.setCheckable(true);
            c.setCheckedIconVisible(false);
            c.setId(View.generateViewId());
            c.setChecked(t.equals(panelTab));
            c.setOnClickListener(v -> {
                panelTab = t;
                c.setChecked(true);
                buildPanel();
                panelScroll.scrollTo(0, 0);
            });
            panelTabs.addView(c);
        }
    }

    private void buildPanel() {
        panelContent.removeAllViews();
        try {
            panels.buildTab(new PanelBuilder(this, panelContent), panelTab);
        } catch (Throwable t) {
            AppLog.e(TAG, "Building the " + panelTab + " panel failed", t);
        }
    }

    private void openQuickSheet() {
        if (inPip) return;
        if (quickSheet != null) quickSheet.dismiss();
        BottomSheetDialog d = new BottomSheetDialog(this);
        quickScroll = new ScrollView(this);
        quickContent = new LinearLayout(this);
        quickContent.setOrientation(LinearLayout.VERTICAL);
        quickContent.setPadding(dp(20), dp(8), dp(20), dp(16));
        quickScroll.addView(quickContent);
        buildQuick();
        d.setContentView(quickScroll);
        d.getBehavior().setSkipCollapsed(true);
        d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        d.setOnDismissListener(x -> {
            if (quickSheet == d) {
                quickSheet = null;
                quickContent = null;
                quickScroll = null;
            }
            if (fullscreen) applyFullscreen();
            scheduleHide();
        });
        quickSheet = d;
        ui.removeCallbacks(hideRunnable);
        d.show();
    }

    private void buildQuick() {
        if (quickContent == null) return;
        quickContent.removeAllViews();
        try {
            panels.buildQuick(new PanelBuilder(this, quickContent));
        } catch (Throwable t) {
            AppLog.e(TAG, "Building quick settings failed", t);
        }
    }

    @Override
    public void rebuildPanel() {
        if (panelOpen) {
            int y = panelScroll.getScrollY();
            buildPanel();
            panelScroll.post(() -> panelScroll.scrollTo(0, y));
        }
        if (quickSheet != null && quickScroll != null) {
            int y = quickScroll.getScrollY();
            buildQuick();
            ScrollView s = quickScroll;
            s.post(() -> s.scrollTo(0, y));
        }
    }

    @Override
    public void applyUiPrefs() {
        DisplayParams p = ctl.displayParams();
        overlay.setParams(p);
        overlay.setGuides(prefs.grid(), prefs.crosshair(), prefs.safeAreas());
        overlay.setHistogramMode(prefs.histogram());
        ctl.renderer.setAnalysisListener(prefs.histogram() != Prefs.HIST_OFF
                ? a -> ui.post(() -> overlay.setAnalysis(a)) : null);
        boolean mic = prefs.audio().micMix;
        overlay.setMeters(prefs.meters(), mic);
        applyStatsVisibility();
        switch (prefs.orientation()) {
            case Prefs.ORIENT_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                break;
            case Prefs.ORIENT_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = prefs.maxBrightness() ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        btnReplay.setVisibility(prefs.replay() ? View.VISIBLE : View.GONE);
        btnMic.setVisibility(mic ? View.VISIBLE : View.GONE);
        btnMic.setImageResource(prefs.audio().micMute ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        boolean sound = prefs.audio().enabled && prefs.audio().monitor && ctl.hasAudioPermission();
        btnMute.setImageResource(sound ? R.drawable.ic_volume : R.drawable.ic_volume_off);
        updatePipParams();
        scheduleHide();
    }

    // ==================================================================
    // Controller callbacks

    @Override
    public void onState(CaptureController.State s, String titleText, String detail) {
        state = s;
        statusButtons.removeAllViews();
        boolean showCard = !inPip;
        statusProgress.setVisibility(View.GONE);
        statusIcon.setVisibility(View.VISIBLE);
        switch (s) {
            case STREAMING:
            case TEST_PATTERN:
                showCard = false;
                break;
            case NO_DEVICE:
                statusIcon.setImageResource(R.drawable.ic_usb);
                if (!"Paused".equals(titleText)) {
                    addStatusButton("Show test pattern", false, () -> ctl.setTestPattern(true));
                    addStatusButton("Help", false, this::showHelp);
                }
                break;
            case NEED_CAMERA_PERMISSION:
                statusIcon.setImageResource(R.drawable.ic_videocam);
                addStatusButton("Grant permission", true, this::requestCameraPermission);
                break;
            case NEED_USB_PERMISSION:
                statusIcon.setImageResource(R.drawable.ic_usb);
                addStatusButton("Allow", true, ctl::requestAccess);
                break;
            case CONNECTING:
                statusIcon.setVisibility(View.GONE);
                statusProgress.setVisibility(View.VISIBLE);
                break;
            case NO_SIGNAL:
                statusIcon.setImageResource(R.drawable.ic_videocam_off);
                addStatusButton("Restart video", false, ctl::restartStream);
                break;
            case ERROR:
                statusIcon.setImageResource(R.drawable.ic_error);
                addStatusButton("Try again", true, ctl::retry);
                addStatusButton("Safest mode", false, () -> ctl.selectPreset(prefs.preset()));
                addStatusButton("View log", false, this::openLogs);
                break;
            default:
                break;
        }
        if (showCard) {
            statusTitle.setText(titleText);
            statusDetail.setText(detail);
            statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        boolean video = isShowingVideo();
        btnShot.setEnabled(video);
        btnShot.setAlpha(video ? 1f : 0.4f);
        btnRecord.setEnabled(video || recording);
        btnRecord.setAlpha(video || recording ? 1f : 0.4f);
        applyStatsVisibility();
        if (s == CaptureController.State.STREAMING || s == CaptureController.State.TEST_PATTERN) scheduleHide();
        else if (!locked) showControls();
        updatePipParams();
    }

    private void addStatusButton(String text, boolean primary, Runnable r) {
        MaterialButton b = primary ? new MaterialButton(this)
                : new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (statusButtons.getChildCount() > 0) lp.leftMargin = dp(8);
        statusButtons.addView(b, lp);
    }

    @Override
    public void onStreamInfo(CaptureController.StreamInfo i) {
        info = i;
        if (i == null) {
            title.setText(R.string.app_name);
            subtitle.setText("");
            overlay.setSource(0, 0);
        } else {
            title.setText(i.deviceName);
            String speed = i.usbSpeed.isEmpty() ? "" : " · " + i.usbSpeed.replaceAll(" \\(.*\\)", "");
            subtitle.setText(i.summary() + speed);
            overlay.setSource(i.width, i.height);
            setSurfaceFrameRate(i.fps);
        }
        rebuildPanel();
        updatePipParams();
    }

    private void setSurfaceFrameRate(double fps) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || fps <= 0) return;
        try {
            Surface s = video.getHolder().getSurface();
            if (s != null && s.isValid()) s.setFrameRate((float) fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        } catch (Throwable t) {
            AppLog.d(TAG, "setFrameRate: " + t);
        }
    }

    @Override
    public void onStats(CaptureController.LiveStats s) {
        if (stats.getVisibility() == View.VISIBLE) stats.setText(s.text);
    }

    @Override
    public void onToast(String message) {
        toast(message);
    }

    @Override
    public void onRecordingState(boolean rec) {
        recording = rec;
        btnRecord.setImageResource(rec ? R.drawable.ic_stop : R.drawable.ic_record);
        btnRecord.setBackgroundResource(rec ? R.drawable.bg_round_button_active : R.drawable.bg_round_button);
        recBadge.setVisibility(rec && !inPip ? View.VISIBLE : View.GONE);
        if (rec) {
            recTime.setText("REC 0:00:00");
            toast("Recording…");
        }
        onState(state, statusTitle.getText().toString(), statusDetail.getText().toString());
    }

    @Override
    public void onAudioStatus(String s, boolean ok) {
        audioStatus = s;
    }

    @Override
    public void onControlsReady(UvcControls c) {
        controls = c;
        if (panelOpen && "Image".equals(panelTab)) rebuildPanel();
    }

    // ==================================================================
    // Host

    @Override
    public Context context() {
        return this;
    }

    @Override
    public CaptureController controller() {
        return ctl;
    }

    @Override
    public Prefs prefs() {
        return prefs;
    }

    @Override
    public CaptureController.StreamInfo info() {
        return info;
    }

    @Override
    public UvcControls controls() {
        return controls;
    }

    @Override
    public String audioStatus() {
        return audioStatus.isEmpty() ? ctl.audio.status() : audioStatus;
    }

    @Override
    public void openLogs() {
        startActivity(new Intent(this, LogActivity.class));
    }

    private final Runnable hideToast = () -> toastView.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> toastView.setVisibility(View.GONE)).start();

    @Override
    public void toast(String s) {
        if (s == null || s.isEmpty() || inPip) return;
        toastView.animate().cancel();
        toastView.setText(s);
        toastView.setAlpha(1f);
        toastView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, Math.min(7000, 2500 + s.length() * 35L));
    }

    @Override
    public void showHelp() {
        String text = "CONNECTING\n"
                + "• Plug the capture card or USB camera into the phone's USB-C port, directly or through a USB-C OTG adapter or hub. The video starts by itself.\n"
                + "• When Android asks, allow access and tick \"Always open\" so it connects instantly next time.\n"
                + "• Capture cards need an HDMI source (console, camera, PC) that is switched on. \"No signal\" means the card is connected but receives no picture.\n"
                + "• Some cards and phones need extra power: use a powered USB-C hub if the card keeps disconnecting.\n\n"
                + "QUALITY\n"
                + "• Best quality picks the highest resolution, then the highest frame rate. Smoothest prefers 60 fps. Lowest latency prefers uncompressed video.\n"
                + "• USB 2 capture cards (most cheap ones) do 1080p30 or 720p60 using MJPEG. USB 3 cards can do 1080p60 uncompressed if the phone port and cable are USB 3.\n"
                + "• If a mode doesn't work the app automatically falls back to the next best one.\n\n"
                + "SOUND\n"
                + "• Android sees the capture card's sound as a USB microphone, so the app asks for the Microphone permission.\n"
                + "• Use Sound delay if the sound comes before the picture. Cheap MS2109 cards sound sped-up without the MS2109 fix (on by default).\n"
                + "• Commentary mic mixes your voice into recordings.\n\n"
                + "CONTROLS\n"
                + "• Tap to show or hide controls, double-tap to switch Fit/Fill, pinch to zoom and drag to move.\n"
                + "• Lock stops accidental touches while you play. Press and hold to unlock.\n"
                + "• Keyboard: F full screen, S screenshot, R record, M mute, P picture-in-picture, E expert mode, I statistics, Z scaling, G grid.\n\n"
                + "FILES\n"
                + "• Recordings: Movies/USB Capture. Screenshots: Pictures/USB Capture.\n"
                + "• Instant replay keeps the last moments in memory; press the replay button to save them.\n\n"
                + "PROBLEMS\n"
                + "• Every crash, freeze, stutter and USB error is written to the log (Logs button). Use Share to send it.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("How to use USB Capture")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("Open logs", (d, w) -> openLogs())
                .show();
    }

    // ==================================================================
    // Permissions

    private boolean granted(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askStartupPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !granted(Manifest.permission.CAMERA)) {
            need.add(Manifest.permission.CAMERA);
        }
        if (!granted(Manifest.permission.RECORD_AUDIO) && !prefs.raw().getBoolean("asked_audio", false)) {
            need.add(Manifest.permission.RECORD_AUDIO);
            prefs.put("asked_audio", true);
        }
        if (!need.isEmpty()) ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_START);
    }

    private void requestCameraPermission() {
        if (!granted(Manifest.permission.CAMERA)
                && prefs.raw().getBoolean("camera_denied", false)
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            openAppSettings("Turn on the Camera permission for USB Capture, then come back.");
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void requestAudioPermission() {
        if (!granted(Manifest.permission.RECORD_AUDIO)
                && prefs.raw().getBoolean("audio_denied", false)
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            openAppSettings("Turn on the Microphone permission for USB Capture to hear the capture card.");
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void openAppSettings(String why) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Permission needed")
                .setMessage(why)
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null)));
                    } catch (Throwable t) {
                        toast("Couldn't open Android settings");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        StringBuilder sb = new StringBuilder("Permissions:");
        for (int i = 0; i < permissions.length; i++) {
            boolean ok = i < results.length && results[i] == PackageManager.PERMISSION_GRANTED;
            sb.append(' ').append(permissions[i].replace("android.permission.", "")).append('=').append(ok ? "granted" : "denied");
            if (!ok && Manifest.permission.CAMERA.equals(permissions[i])) prefs.put("camera_denied", true);
            if (!ok && Manifest.permission.RECORD_AUDIO.equals(permissions[i])) prefs.put("audio_denied", true);
        }
        AppLog.i(TAG, sb.toString());
        if (requestCode == REQ_STORAGE) {
            Runnable r = pendingStorageAction;
            pendingStorageAction = null;
            if (r != null && granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) r.run();
            else toast("Storage permission is needed to save files");
            return;
        }
        ctl.onPermissionsChanged();
        applyUiPrefs();
        if (requestCode == REQ_AUDIO && granted(Manifest.permission.RECORD_AUDIO)) {
            prefs.put("audio_on", true);
            ctl.updateAudio();
        }
    }

    // ==================================================================
    // Crash reports

    private void checkCrashReports() {
        if (isFinishing()) return;
        List<File> list = CrashHandler.unseenReports(this);
        if (list.isEmpty()) return;
        String kinds = "";
        for (File f : list) {
            if (f.getName().startsWith("anr")) kinds = "stopped responding";
            else if (kinds.isEmpty()) kinds = "closed unexpectedly";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Problem report saved")
                .setMessage("USB Capture " + kinds + " last time. A report with the details was saved ("
                        + list.size() + (list.size() == 1 ? " report" : " reports")
                        + "). You can view it or share it to get help.")
                .setPositiveButton("View", (d, w) -> {
                    CrashHandler.markReportsSeen(this);
                    Intent i = new Intent(this, LogActivity.class);
                    i.putExtra(LogActivity.EXTRA_SHOW_CRASHES, true);
                    startActivity(i);
                })
                .setNegativeButton("Dismiss", (d, w) -> CrashHandler.markReportsSeen(this))
                .setOnCancelListener(d -> CrashHandler.markReportsSeen(this))
                .show();
    }

    // ==================================================================
    // Keyboard / remote shortcuts

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_F11:
                fullscreen = !fullscreen;
                applyFullscreen();
                return true;
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_SYSRQ:
                takeScreenshot();
                return true;
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                toggleRecording();
                return true;
            case KeyEvent.KEYCODE_M:
                toggleMute();
                return true;
            case KeyEvent.KEYCODE_P:
                enterPip();
                return true;
            case KeyEvent.KEYCODE_E:
                setExpert(!expert);
                return true;
            case KeyEvent.KEYCODE_I:
            case KeyEvent.KEYCODE_F3:
                prefs.put("show_stats", !prefs.showStats());
                applyUiPrefs();
                return true;
            case KeyEvent.KEYCODE_Z:
                cycleScale();
                return true;
            case KeyEvent.KEYCODE_G:
                prefs.put("grid", (prefs.grid() + 1) % 4);
                applyUiPrefs();
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (controlsShown) hideControls(true);
                else showControls();
                return true;
            case KeyEvent.KEYCODE_MENU:
                openSettings();
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                if (panelOpen) closePanel();
                else if (fullscreen) {
                    fullscreen = false;
                    applyFullscreen();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !locked && controlsShown) scheduleHide();
        return super.dispatchTouchEvent(ev);
    }
}
