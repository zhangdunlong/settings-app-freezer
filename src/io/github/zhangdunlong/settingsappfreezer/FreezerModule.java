package io.github.zhangdunlong.settingsappfreezer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * settings-app-freezer — 在系统设置的应用信息页注入「冻结 / 解冻」按钮的 Xposed 模块。
 *
 * <p>版本：1.0.0（见 {@code version.properties}，此注释需与之一致）
 * <p>许可证：GPL-3.0
 *
 * <p>Modern rewrite of the legacy "Native Freezer" Xposed module, which hooked AOSP Settings
 * internal classes that no longer exist since Android 8 and were heavily rewritten by
 * ColorOS / RealmeUI — that is why those modules silently stopped working on modern devices.
 *
 * <p>Design goals (ROM-agnostic, survives OEM skinning):
 * <ul>
 *   <li>Do NOT hook AOSP Settings internal classes (gone since Android 8, and heavily
 *       rewritten by ColorOS / RealmeUI). Instead hook {@code Activity.onCreate} inside the
 *       whole Settings process.</li>
 *   <li>Detect the "App info" page purely from its {@link Intent} (scheme {@code package:},
 *       {@code EXTRA_PACKAGE_NAME}, {@code EXTRA_COMPONENT_NAME}, or a content:// last path
 *       segment), then verify the candidate really is an installed package before acting.</li>
 *   <li>Inject a floating Freeze / Unfreeze pill into {@code android.R.id.content} using
 *       retry + OnGlobalLayoutListener, so it works no matter how slow the OEM layout is.</li>
 *   <li>Freezing uses the framework-level
 *       {@code PackageManager.setApplicationEnabledSetting(..., DISABLED_USER)} — the same
 *       thing the Settings UI itself does, so it is ROM independent.</li>
 *   <li>Hard-block freezing of critical packages (Settings, SystemUI, launcher, IME, this
 *       module, the package installer) so a mis-tap can never brick the device UI.</li>
 * </ul>
 *
 * <p>Implements BOTH the modern {@link IXposedHookLoadPackage} and the legacy
 * {@link IXposedHookZygoteInit} entry points, because different LSPosed/ZygiskNext builds
 * prefer one or the other. Both funnel into the same {@link #hookActivityOnCreate} logic.
 */
public class FreezerModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "FreezerModule";
    private static final String MODULE_PKG = "io.github.zhangdunlong.settingsappfreezer";
    private static final int FROZEN_TAG = 0x5f465252; // "FRZR"
    private static final int FROZEN_BAR_TAG = 0x5f465242;  // "FRRB" - the horizontal bar
    private static final int FROZEN_LIST_TAG = 0x5f464c53; // "FRLS" - frozen-list entry button
    private static final int HEADER_TAG = 0x5f484452;      // "HRDR" - list header (count)

    private static final String ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS";

    /** Extra laid-out retry attempts after the first shot (delays grow adaptively). */
    private static final int MAX_ATTEMPTS = 20;

    /**
     * Hand-over retry budget.
     *
     * <p>Deliberately separate from {@link #MAX_ATTEMPTS}: the hand-over is not waiting for a
     * layout pass, it is waiting for the SURVIVING Activity to be created at all. Measured on
     * ColorOS the transient host dies immediately and the real page only appears well after the
     * whole settings stack has been rebuilt — hundreds of milliseconds later. Reusing the
     * injection budget (20 x 150ms = 3s) was not always enough and the button silently vanished.
     * 60 x 200ms = 12s covers the observed worst case with room to spare.
     */
    private static final int MAX_HANDOVER_ATTEMPTS = 60;
    private static final long HANDOVER_RETRY_MS = 200L;

    private static final int ACCENT = 0xFF1AA6C4;   // teal, matches the author's theme
    private static final int ACCENT_PRESSED = 0xFF15849B;
    private static final int ACCENT_FROZEN = 0xFF3FBFA8;          // mint = currently frozen
    private static final int ACCENT_FROZEN_PRESSED = 0xFF33998A;
    private static final int LIST_BADGE = 0xFF3A6EA5;       // entry button for the frozen list

    // CRITICAL: do NOT create a static Handler here. The class is initialised during
    // Zygote.specializeAppProcess(), before the main Looper exists, so
    // `new Handler(Looper.getMainLooper())` throws NPE (Looper.mQueue on a null Looper),
    // which aborts <clinit> and makes LSPosed silently drop the whole module with
    // "Failed to load class io.github.zhangdunlong.settingsappfreezer.FreezerModule".
    // Resolve it lazily instead.
    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    /**
     * Known Settings packages. Matching is done on the process name with any ":sub" suffix
     * stripped, and anything that looks like a settings process is accepted too — we log
     * unknown ones so the user can widen the LSPosed scope and re-test.
     */
    private static final Set<String> SETTINGS_PKGS = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.android.settings.overlay",
            "com.coloros.settings",
            "com.oppo.settings",
            "com.oplus.settings",
            "com.realme.settings",
            "com.oneplus.settings",
            "com.miui.securitycenter",
            "com.huawei.android.systemmanager"
    ));

    /** Never freezable — freezing these would break the launcher, IME or the Settings UI. */
    private static final Set<String> CRITICAL_PKGS = new HashSet<>(Arrays.asList(
            MODULE_PKG,
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.shell",
            "com.android.packageinstaller",
            "com.android.providers.settings",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.permissioncontroller",
            "com.android.vending",
            "com.google.android.gms"
    ));

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log(TAG + ": initZygote, module loaded, API=" + android.os.Build.VERSION.SDK_INT
                + " user=" + currentProcessUser()
                + (isCloneUser() ? " (CLONE/SECONDARY user — actions are scoped to it)" : ""));
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String raw = lpparam.packageName;
        if (raw == null) return;

        // ColorOS runs helper processes such as "com.android.settings:background" and
        // "com.android.settings.intelligence". They never host the app-info UI, so hooking them
        // only wastes work and floods the log.
        if (isHelperProcess(raw)) {
            return;
        }

        // Settings may run in a secondary process such as "com.android.settings:remote".
        final String process = stripProcessSuffix(raw);
        if (!isSettingsProcess(process)) {
            return;
        }

        XposedBridge.log(TAG + ": hooked settings process raw=" + raw + " base=" + process);
        hookActivityLifecycle(lpparam.classLoader, raw);
    }

    private static boolean isHelperProcess(String name) {
        int idx = name.indexOf(':');
        if (idx <= 0) return false;
        String suffix = name.substring(idx + 1);
        // NOTE: do NOT exclude "remote" — on some ColorOS builds the app-info page lives there.
        return suffix.equals("background") || suffix.equals("intelligence")
                || suffix.equals("crypto");
    }

    private static String stripProcessSuffix(String name) {
        int idx = name.indexOf(':');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static boolean isSettingsProcess(String base) {
        if (SETTINGS_PKGS.contains(base)) return true;
        // Be permissive: any process whose base package looks like a settings app. LSPosed
        // still gates execution by scope, so this cannot broaden exposure on its own.
        return base.contains("settings") || base.contains("secure") || base.contains("systemui");
    }

    /**
     * Hooks both onCreate and onResume.
     *
     * <p>ColorOS/RealmeUI implements the app-info screen as a TWO-STAGE flow: a transient
     * {@code InstalledAppDetailsTop} activity starts and immediately finishes while the real
     * {@code InstalledAppDetails} takes over. Hooking only onCreate therefore lands on the
     * dying transition activity (isFinishing()==true) and the injection is dropped. Hooking
     * onResume as well guarantees we also see the surviving, laid-out activity.
     */
    private void hookActivityLifecycle(final ClassLoader cl, final String tagCtx) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    String cls = activity.getClass().getName();
                    // Unconditional trace: reveals which Activity actually hosts the app-info
                    // page when reached through the "App management" list, and whether it is
                    // rejected by isAppInfoHost() below. This is the only way to identify the
                    // OEM-specific host class without guessing.
                    XposedBridge.log(TAG + ": TRACE onCreate " + cls
                            + " intent=" + describeIntent(activity)
                            + " host=" + isAppInfoHost(cls));
                    final String pkg = resolvePkgForLifecycle(activity);
                    if (pkg == null) {
                        // Even when the package is still unresolved the page may be an app-info
                        // screen whose content only becomes readable after layout — keep probing
                        // instead of giving up, otherwise the button silently never appears.
                        probeForAppInfoPage(activity, tagCtx + ":onCreate");
                        XposedBridge.log(TAG + ": TRACE   -> no pkg yet, probe scheduled");
                        return;
                    }
                    if (activity.isFinishing()) {
                        XposedBridge.log(TAG + ": [" + tagCtx + "] transient activity for "
                                + pkg + " (" + activity.getClass().getSimpleName()
                                + "), skipping direct inject");
                        return;
                    }
                    XposedBridge.log(TAG + ": [" + tagCtx + "] app-info detected for " + pkg
                            + " activity=" + activity.getClass().getName()
                            + " finishing=" + activity.isFinishing());
                    scheduleInject(activity, pkg, 0);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": lifecycle hook error: " + t);
                }
            }
        };

        // onCreate(Bundle)
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onCreate",
                    Bundle.class, hook);
        } catch (Throwable t1) {
            try {
                XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, hook);
                XposedBridge.log(TAG + ": onCreate hooked via boot Activity.class fallback");
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": FAILED to hook onCreate (" + t1 + " / " + t2 + ")");
            }
        }

        // onResume() — the reliable injection point on OEM two-stage app-info pages
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) return;
                                final Activity activity = (Activity) param.thisObject;
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                // onResume is the first callback after the content view exists but
                                // BEFORE it is necessarily measured, so run the fast path (which
                                // self-guards on a measured layout) instead of the retry ladder,
                                // whose first tick may land after this Activity is already gone.
                                tryDirectInjectOnFocus(activity);
                                final String pkg = resolvePkgForLifecycle(activity);
                                if (pkg == null) return;
                                // Skip if we already placed the button on this activity.
                                ViewGroup content = resolveContentParent(activity);
                                if (content != null && content.findViewWithTag(FROZEN_TAG) != null) {
                                    return;
                                }
                                XposedBridge.log(TAG + ": onResume hit for " + pkg
                                        + " activity=" + activity.getClass().getName());
                                scheduleInject(activity, pkg, 0);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": onResume hook error: " + t);
                            }
                        }
                    });
        } catch (Throwable t3) {
            try {
                XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            final String pkg = resolvePkgForLifecycle(activity);
                            if (pkg == null) return;
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            ViewGroup content = resolveContentParent(activity);
                            if (content != null && content.findViewWithTag(FROZEN_TAG) != null) {
                                return;
                            }
                            XposedBridge.log(TAG + ": onResume hit (fallback) for " + pkg);
                            scheduleInject(activity, pkg, 0);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": onResume fallback error: " + t);
                        }
                    }
                });
                XposedBridge.log(TAG + ": onResume hooked via boot Activity.class fallback");
            } catch (Throwable t4) {
                XposedBridge.log(TAG + ": FAILED to hook onResume (" + t3 + " / " + t4 + ")");
            }
        }

        // ---- Page-switch triggers -------------------------------------------------
        // OPlus-style shells keep ONE Activity alive and swap pages inside it, so onCreate
        // fires only once for the whole session and onWindowFocusChanged may not fire either.
        // onPostResume runs after every resume/fragment transaction, which makes it the most
        // reliable trigger for "the user may be on a new page now".
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onPostResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) return;
                                probeForAppInfoPage((Activity) param.thisObject, "postResume");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": onPostResume hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAILED to hook onPostResume: " + t);
        }

        // A reused singleTask Activity gets a fresh Intent through onNewIntent.
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onNewIntent",
                    Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) return;
                                Activity a = (Activity) param.thisObject;
                                // A new Intent is a strong signal; clear the dedup mark so a
                                // re-used shell can host a different app's page.
                                clearBarHost(a);
                                XposedBridge.log(TAG + ": onNewIntent for " + a.getClass().getName()
                                        + " intent=" + describeIntent(a));
                                probeForAppInfoPage(a, "newIntent");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": onNewIntent hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAILED to hook onNewIntent: " + t);
        }

        // onWindowFocusChanged(boolean) — the authoritative "view tree is laid out and
        // visible" signal. On ColorOS the app-info content view reports 0x0 through the whole
        // onCreate/onResume window, but window focus is only gained once the real layout is up,
        // so this is where the button reliably goes in.
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onWindowFocusChanged", boolean.class, buildFocusHook());
            XposedBridge.log(TAG + ": onWindowFocusChanged hooked");
        } catch (Throwable t5) {
            try {
                XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                        boolean.class, buildFocusHook());
                XposedBridge.log(TAG + ": onWindowFocusChanged hooked via boot fallback");
            } catch (Throwable t6) {
                XposedBridge.log(TAG + ": FAILED to hook onWindowFocusChanged ("
                        + t5 + " / " + t6 + ")");
            }
        }

        // ViewRootImpl.performTraversals — fires right after the window's view tree produced its
        // first real layout. This is the only signal that is guaranteed to see a measured
        // (non 0x0) container on ColorOS, where the app-info Activity's own content and decor
        // views stay 0x0 for their entire lifetime.
        hookWindowLayout(cl);
        hookActivityDestroy(cl);
    }

    /**
     * Last package name resolved from an app-info Intent.
     *
     * <p>On the ColorOS two-stage flow the surviving parent activity
     * ({@code InstalledAppDetails}) is often resumed WITHOUT the original intent extras, so
     * {@link #resolveTargetPackage} returns null there and the button would never appear.
     * We remember the package from the transient stage and let the parent reuse it, clearing
     * it as soon as any other app-info page takes over.
     */
    private static volatile String lastResolvedPkg;
    private static volatile long lastResolvedAt;

    private static final long PKG_MEMORY_MS = 60_000L;

    private static void rememberPkg(String pkg) {
        lastResolvedPkg = pkg;
        lastResolvedAt = System.currentTimeMillis();
    }

    /**
     * Host activities that may legitimately show the frozen-app bar.
     *
     * <p>This is a strict allow-list on purpose. It must be tight, because the remembered
     * package is only valid while the user is still ON an app-info page. With a permissive
     * check, returning to the Settings home screen within the memory window made the module
     * re-inject the bar there (the home Activity is a plain shell with no package in its
     * Intent, so it was silently borrowing the remembered package).
     *
     * <p>Accepted:
     * <ul>
     *   <li>{@code ...applications.InstalledAppDetails[Top]} — AOSP + ColorOS detail page</li>
     *   <li>anything containing {@code AppInfo} / {@code AppDetails}</li>
     * </ul>
     * Rejected on purpose: {@code OplusSettingsHomepageActivity} (home shell),
     * {@code AppListActivity} / {@code ApplicationsSettings} (the list),
     * and the generic {@code SubSettings} (used for a dozen unrelated pages).
     */
    private static boolean isAppInfoHost(String cls) {
        if (cls == null) return false;
        String c = cls.toLowerCase();
        // Hard denials first — these are the shells that caused the false positives.
        if (c.contains("homepage") || c.contains("applist") || c.contains("applicationsettings")
                || c.contains("dashboard") || c.contains("subsettings")) {
            return false;
        }
        if (c.contains("installedappdetails") || c.contains("appinfo")
                || c.contains("appdetails")) {
            return true;
        }
        // MIUI hosts the detail page in its own app-manager activity. It is safe to accept it
        // because the package is still verified against PackageManager before anything is
        // injected, and it is rejected again later by looksLikeAppInfoContent().
        if (c.contains("applicationsdetails")) {
            return true;
        }
        return false;
    }

    /**
     * Content-based fallback for app-info pages hosted by a generic shell (e.g. ColorOS may use
     * {@code SubSettings} for the detail screen). The AOSP app-info page always exposes the
     * "强行停止 / Force stop" action, so the presence of that button in the visible tree is a
     * reliable signal.
     */
    // ------------------------------------------------------------------
    // Localization
    // ------------------------------------------------------------------
    //
    // The build pipeline links the manifest only — there is no aapt2 resource compilation and no
    // generated R class — so localized strings cannot live in res/values-*. They are resolved in
    // Java against the host process locale instead.
    //
    // Locale.getDefault() is the process-wide default, which Android sets from the app's own
    // configuration. On Android 13+ that means a per-app language choice (Settings set to English
    // while the system runs Chinese) still resolves correctly here, matching the page the user is
    // actually looking at.
    //
    // Strings that MATCH the system UI (the force-stop label used to recognise an app-info page)
    // are deliberately NOT translated — they must keep every spelling we might encounter.
    // See containsForceStopLabel().

    /** True when the host locale is Chinese; the module's source strings are Chinese. */
    private static boolean isChinese() {
        try {
            Locale l = Locale.getDefault();
            if (l == null) return true;
            return "zh".equalsIgnoreCase(l.getLanguage());
        } catch (Throwable t) {
            return true;   // source language — never leave the UI blank
        }
    }

    /** Picks the localized variant of a UI string. */
    private static String tr(String zh, String en) {
        return isChinese() ? zh : en;
    }

    private static boolean looksLikeAppInfoContent(View decor) {
        try {
            return containsForceStopLabel(decor, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsForceStopLabel(View v, int depth) {
        if (v == null || depth > 12) return false;
        try {
            if (v instanceof android.widget.TextView) {
                CharSequence t = ((android.widget.TextView) v).getText();
                if (t != null) {
                    String s = t.toString();
                    if (s.contains("强行停止") || s.contains("Force stop")
                            || s.contains("强行停止应用") || s.contains("强停")) {
                        return true;
                    }
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount() && i < 200; i++) {
                    if (containsForceStopLabel(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** @return the remembered package if it is still fresh AND we are on an app-info page. */
    private static String recallPkg(Activity activity) {
        return recallPkg(activity, null);
    }

    private static String recallPkg(Activity activity, View decor) {
        String pkg = lastResolvedPkg;
        if (pkg == null) return null;
        if (System.currentTimeMillis() - lastResolvedAt > PKG_MEMORY_MS) {
            lastResolvedPkg = null;
            return null;
        }
        // Strict: the window must belong to a known app-info host. This prevents the bar from
        // reappearing on the Settings home screen, app list, or any other unrelated page.
        try {
            boolean host = isAppInfoHost(activity.getClass().getName())
                    || (decor != null && looksLikeAppInfoContent(decor));
            if (!host) {
                return null;
            }
            PackageManager pm = activity.getPackageManager();
            if (pm.getApplicationInfo(pkg, 0) == null) {
                lastResolvedPkg = null;
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return pkg;
    }


    private static String resolvePkgForLifecycle(Activity activity) {
        return resolvePkgForLifecycle(activity, null);
    }

    private static String resolvePkgForLifecycle(Activity activity, View decor) {
        // HARD GUARD: never touch a window that is not an app-info host. Without this, the
        // Settings home screen (and any other shell Activity) would be scanned and would
        // eventually resolve some package, making the bar appear all over the Settings app.
        boolean host = isAppInfoHost(activity.getClass().getName());
        if (!host && decor != null) {
            // A generic shell (e.g. SubSettings) is acceptable only when the visible content
            // is unmistakably the app-info screen.
            host = looksLikeAppInfoContent(decor);
            if (host) {
                logOnce("app-info content detected inside generic shell "
                        + activity.getClass().getName());
            }
        }
        if (!host) {
            return null;
        }

        String pkg = resolveTargetPackage(activity);
        if (pkg != null) {
            rememberPkg(pkg);
            return pkg;
        }
        // ColourOS app-info Activities frequently carry the target package in a private field
        // (mPackageName / mAppPackageName / mPackage) rather than in the Intent.
        pkg = extractPkgFromFields(activity);
        if (pkg != null) {
            rememberPkg(pkg);
            return pkg;
        }
        return recallPkg(activity, decor);
    }

    /** Reflectively scan the Activity's own fields for a plausible installed package name. */
    private static String extractPkgFromFields(Activity activity) {
        try {
            PackageManager pm = activity.getPackageManager();
            for (Class<?> c = activity.getClass(); c != null && c != Activity.class;
                 c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(activity);
                        if (v == null) continue;

                        // (a) direct String field, whatever its name — verified against the PM
                        if (v instanceof String) {
                            String s = normalizePackage((String) v);
                            if (looksLikePackage(s) && pm.getApplicationInfo(s, 0) != null) {
                                XposedBridge.log(TAG + ": recovered package '" + s
                                        + "' from field " + c.getSimpleName() + "." + f.getName());
                                return s;
                            }
                            continue;
                        }

                        // (b) Fragment field (ColorOS hosts the app-info screen as a fragment)
                        if (v instanceof android.app.Fragment) {
                            String got = scanFragmentForPkg((android.app.Fragment) v, pm);
                            if (got != null) {
                                XposedBridge.log(TAG + ": recovered package '" + got
                                        + "' from fragment in field "
                                        + c.getSimpleName() + "." + f.getName());
                                return got;
                            }
                            continue;
                        }

                        // (c) View field — walk the subtree for a package hint
                        if (v instanceof View) {
                            String got = describeViewForPackage((View) v, pm);
                            if (got != null) {
                                String p = normalizePackage(got.replaceAll("^.*(->|=) ", ""));
                                XposedBridge.log(TAG + ": recovered package '" + p
                                        + "' from view subtree in field "
                                        + c.getSimpleName() + "." + f.getName());
                                if (looksLikePackage(p)) return p;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            // Last resort: the activity's own content view.
            try {
                View content = activity.findViewById(android.R.id.content);
                if (content != null) {
                    String got = describeViewForPackage(content, pm);
                    if (got != null) {
                        String p = normalizePackage(got.replaceAll("^.*(->|=) ", ""));
                        if (looksLikePackage(p)) {
                            XposedBridge.log(TAG + ": recovered package '" + p
                                    + "' from content view subtree");
                            return p;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": extractPkgFromFields error: " + t);
        }
        return null;
    }

    /** Recursively search a fragment (and its child fragment manager) for the package name. */
    private static String scanFragmentForPkg(android.app.Fragment fr, PackageManager pm) {
        try {
            if (fr.getArguments() != null) {
                for (String k : fr.getArguments().keySet()) {
                    Object v = fr.getArguments().get(k);
                    if (v instanceof String) {
                        String a = normalizePackage((String) v);
                        if (looksLikePackage(a) && pm.getApplicationInfo(a, 0) != null) {
                            return a;
                        }
                    }
                }
            }
            if (fr.getView() != null) {
                String got = describeViewForPackage(fr.getView(), pm);
                if (got != null) {
                    String p = normalizePackage(got.replaceAll("^.*(->|=) ", ""));
                    if (looksLikePackage(p)) return p;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (fr instanceof android.app.ListFragment) {
                android.app.ListFragment lf = (android.app.ListFragment) fr;
                Object adapter = lf.getListAdapter();
                if (adapter != null) {
                    // App list adapters often expose the selected package through the clicked view.
                    String m = adapter.getClass().getName();
                    if (m != null && (m.contains("App") || m.contains("Package"))) {
                        logOnce("app-list adapter class = " + m);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Hooks Activity destroy so a still-open list dialog never outlives its window. */
    private void hookActivityDestroy(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject instanceof FreezerModule) return;
                                dismissListDialog();
                                if (param.thisObject instanceof Activity) {
                                    clearBarHost((Activity) param.thisObject);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": onDestroy hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAILED to hook onDestroy: " + t);
        }
    }

    private XC_MethodHook buildFocusHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof Activity)) return;
                    final Activity activity = (Activity) param.thisObject;
                    if (!Boolean.TRUE.equals(param.args[0])) return;   // only focus GAINED
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    // NOTE: no class-name gate here. OPlus uses a SINGLE-Activity multi-page
                    // shell (OplusSettingsHomepageActivity) that swaps pages in place, so the
                    // app-info screen can appear long after onCreate and with no new Intent.
                    // Class-based gating is done later by looksLikeAppInfoContent().
                    //
                    // Window-focus is the ONE signal that is guaranteed to arrive on the surviving
                    // Activity AFTER its content has been measured — exactly what the transient
                    // host misses. So try the remembered package first: if this really is an
                    // app-info page the bar goes in immediately, with no retry ladder involved.
                    tryDirectInjectOnFocus(activity);
                    probeForAppInfoPage(activity, "focus");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": focus hook error: " + t);
                }
            }
        };
    }

    /**
     * Fast path used on window-focus gain.
     *
     * <p>At that moment the content view is measured, so no retry ladder is needed. This is the
     * only hook that reliably reaches OEM app-info pages hosted by a transient-then-surviving
     * Activity pair, because {@code onCreate} fires while the layout is still 0x0 and the host
     * is destroyed before the first retry can run.
     *
     * <p>Safety: the package must still be resolved from this Activity's own Intent/fields or be
     * a freshly remembered one, and it is verified against PackageManager before injection.
     */
    private void tryDirectInjectOnFocus(Activity activity) {
        try {
            ViewGroup content = resolveContentParent(activity);
            if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return;
            if (content.findViewWithTag(FROZEN_TAG) != null) return;   // already there
            if (isBarAlreadyInWindow(activity, content) || isBarHost(activity)) return;

            String pkg = resolveTargetPackage(activity);
            if (pkg == null) pkg = extractPkgFromFields(activity);
            if (pkg == null) {
                String remembered = lastResolvedPkg;
                // Only trust the memory on a window whose content is unmistakably app-info.
                if (remembered != null
                        && System.currentTimeMillis() - lastResolvedAt <= PKG_MEMORY_MS * 5
                        && looksLikeAppInfoContent(content)
                        && activity.getPackageManager().getApplicationInfo(remembered, 0) != null) {
                    pkg = remembered;
                    XposedBridge.log(TAG + ": focus fast-path using remembered " + pkg);
                }
            }
            if (pkg == null) return;
            // Content gate for the remembered/fallback case only; a package parsed straight from
            // this Activity's own Intent is trustworthy enough on its own.
            if (pkg.equals(lastResolvedPkg) && !looksLikeAppInfoContent(content)) {
                XposedBridge.log(TAG + ": focus fast-path skipped, content is not app-info for " + pkg);
                return;
            }
            rememberPkg(pkg);
            resolveTargetUser(activity, pkg);
            XposedBridge.log(TAG + ": focus fast-path injecting for " + pkg
                    + " into " + activity.getClass().getSimpleName());
            if (!injectOrListen(activity, pkg)) {
                probeForAppInfoPage(activity, "fastpath");
            }
        } catch (Throwable t) {
            logOnce("tryDirectInjectOnFocus failed: " + t);
        }
    }

    /**
     * Page-switch probe.
     *
     * <p>Needed because the app-info page is not always a separate Activity: OPlus (and other
     * OEM shells) host many settings pages inside one long-lived Activity and swap the content
     * in place. Neither {@code onCreate} (fires once) nor {@code ViewRootImpl.performTraversals}
     * (not reached for that window) fires when the user navigates from the settings home screen
     * into the app list and then into an app's detail page.
     *
     * <p>Strategy: whenever this probe runs, look at what is actually on screen. If the visible
     * content is unmistakably an app-info page (the "强行停止 / Force stop" action is always
     * present there), resolve the package from the content and inject.
     */
    private void probeForAppInfoPage(final Activity activity, String trigger) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            ViewGroup decor = resolveContentParent(activity);
            if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                // Not measured yet — try again shortly.
                scheduleProbe(activity, trigger + "+retry", 2);
                return;
            }
            if (decor.findViewWithTag(FROZEN_TAG) != null) return;   // already injected
            if (isBarHost(activity)) return;

            if (!looksLikeAppInfoContent(decor)) {
                scheduleProbe(activity, trigger + "+watch", 2);
                return;
            }

            XposedBridge.log(TAG + ": PROBE(" + trigger + ") app-info content detected in "
                    + activity.getClass().getName());
            final String pkg = resolvePkgFromContent(activity, decor);
            if (pkg == null) {
                XposedBridge.log(TAG + ": PROBE content looks like app-info but package unresolved");
                return;
            }
            rememberPkg(pkg);
            resolveTargetUser(activity, pkg);
            injectButton(activity, decor, pkg);
        } catch (Throwable t) {
            logOnce("probeForAppInfoPage failed: " + t);
        }
    }

    /** Re-runs the probe a few times, because the page may still be swapping in. */
    private void scheduleProbe(final Activity activity, String trigger, final int remaining) {
        if (remaining <= 0) return;
        try {
            mainHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        ViewGroup decor = resolveContentParent(activity);
                        if (decor == null || decor.getWidth() <= 0) return;
                        if (decor.findViewWithTag(FROZEN_TAG) != null) return;
                        if (isBarHost(activity)) return;
                        if (!looksLikeAppInfoContent(decor)) {
                            if (remaining > 1) scheduleProbe(activity, trigger, remaining - 1);
                            return;
                        }
                        XposedBridge.log(TAG + ": PROBE(" + trigger + ") app-info content detected in "
                                + activity.getClass().getName());
                        String pkg = resolvePkgFromContent(activity, decor);
                        if (pkg == null) return;
                        rememberPkg(pkg);
                        resolveTargetUser(activity, pkg);
                        injectButton(activity, decor, pkg);
                    } catch (Throwable ignored) {
                    }
                }
            }, 220L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Extracts the target package by walking the visible content of an app-info page.
     *
     * <p>Used for OEM shells that render the detail page without putting the package in the
     * Intent. The package is recovered from the app icon's content description / tag, or from
     * any text that looks like an installed package and is verified via PackageManager.
     */
    private static String resolvePkgFromContent(Activity activity, View decor) {
        try {
            PackageManager pm = activity.getPackageManager();
            String fromView = describeViewForPackage(decor, pm);
            if (fromView != null) {
                String p = normalizePackage(fromView.replaceAll("^.*(->|=) ", ""));
                if (looksLikePackage(p) && pm.getApplicationInfo(p, 0) != null) return p;
            }
        } catch (Throwable ignored) {
        }
        // 2) The shell Intent. This MUST run here too: the only reliable carrier on ColorOS is the
        //    nested ":settings:show_fragment_args" Bundle, and the Intent-based resolver is gated
        //    away by isAppInfoHost() on the lifecycle path (SubSettings is hard-rejected there).
        //    The caller has already verified the content is an app-info page, so resolving from
        //    the Intent here is safe.
        try {
            String fromIntent = resolveTargetPackage(activity);
            if (fromIntent != null) {
                XposedBridge.log(TAG + ": PROBE resolved package from Intent = " + fromIntent);
                return fromIntent;
            }
        } catch (Throwable t) {
            logOnce("PROBE intent resolution error: " + t);
        }
        // 3) Private Activity fields (mPackageName / mAppPackageName / mPackage).
        try {
            String fromFields = extractPkgFromFields(activity);
            if (fromFields != null) {
                XposedBridge.log(TAG + ": PROBE resolved package from Activity fields = " + fromFields);
                return fromFields;
            }
        } catch (Throwable ignored) {
        }
        // 4) The remembered package is a good fallback here: this probe only fires when the screen
        //    really is an app-info page, and the shell reuses activities without new Intents.
        try {
            String remembered = lastResolvedPkg;
            if (remembered != null
                    && System.currentTimeMillis() - lastResolvedAt <= PKG_MEMORY_MS * 5
                    && activity.getPackageManager().getApplicationInfo(remembered, 0) != null) {
                XposedBridge.log(TAG + ": PROBE using remembered package " + remembered);
                return remembered;
            }
        } catch (Throwable ignored) {
        }
        // Nothing worked: dump the nested Bundle structure once, so the real key name can be
        // read straight off the log instead of guessed.
        dumpNestedExtras(activity);
        return null;
    }

    /** One-shot deep dump of the Activity Intent extras, recursing into nested Bundles. */
    private static void dumpNestedExtras(final Activity activity) {
        try {
            String key = activity.getClass().getName() + "@" + System.identityHashCode(activity);
            if (!DUMPED.add("nested:" + key)) return;
            XposedBridge.log(TAG + ": NESTED-EXTRAS " + describeIntentExtrasDeep(activity.getIntent(), 0));
        } catch (Throwable ignored) {
        }
    }

    private static String describeIntentExtrasDeep(Intent intent, int depth) {
        StringBuilder sb = new StringBuilder();
        if (intent == null) return "intent=null";
        sb.append("action=").append(intent.getAction());
        android.os.Bundle b;
        try {
            b = intent.getExtras();
        } catch (Throwable t) {
            return sb.append(" extras=<err ").append(t).append('>').toString();
        }
        if (b == null || b.isEmpty()) return sb.append(" extras{}").toString();
        sb.append('\n');
        sb.append(describeBundleDeep(b, 1));
        return sb.toString();
    }

    private static String describeBundleDeep(android.os.Bundle b, int indent) {
        StringBuilder sb = new StringBuilder();
        if (b == null) return "";
        if (indent > 6) return "...<too deep>\n";
        String pad = repeat("    ", indent);
        List<String> keys;
        try {
            keys = new ArrayList<>(b.keySet());
        } catch (Throwable t) {
            return pad + "<keySet err " + t + ">\n";
        }
        java.util.Collections.sort(keys);
        for (String k : keys) {
            Object v;
            try {
                v = b.get(k);
            } catch (Throwable t) {
                sb.append(pad).append(k).append(" = <err ").append(t).append(">\n");
                continue;
            }
            if (v instanceof android.os.Bundle) {
                sb.append(pad).append(k).append(" = Bundle{\n");
                sb.append(describeBundleDeep((android.os.Bundle) v, indent + 1));
                sb.append(pad).append("}\n");
            } else if (v == null) {
                sb.append(pad).append(k).append(" = null\n");
            } else {
                String vs;
                try {
                    vs = String.valueOf(v);
                } catch (Throwable t) {
                    vs = "<toString err>";
                }
                if (vs.length() > 220) vs = vs.substring(0, 220) + "…";
                sb.append(pad).append(k).append(" = ")
                  .append(v.getClass().getName()).append(" -> ").append(vs).append('\n');
            }
        }
        return sb.toString();
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < 16; i++) sb.append(s);
        return sb.toString();
    }

    /**
     * One-shot diagnostic: dump everything that might carry the target package name when we
     * land on a laid-out window but cannot resolve a package. This is how the OEM-specific
     * passing mechanism gets identified from a user-supplied log.
     */
    private static void dumpUnknownActivity(Activity act, View decor) {
        String key = act.getClass().getName() + "@" + System.identityHashCode(act);
        if (!DUMPED.add(key)) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n--- DUMP for unresolved app-info window ---\n");
        sb.append("activity : ").append(act.getClass().getName()).append('\n');
        try {
            sb.append("intent   : ").append(act.getIntent()).append('\n');
            android.os.Bundle b = act.getIntent() != null ? act.getIntent().getExtras() : null;
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    sb.append("  extra[").append(k).append("] = ")
                      .append(v == null ? "null" : v.getClass().getName() + " -> " + v).append('\n');
                }
            } else {
                sb.append("  (no extras)\n");
            }
        } catch (Throwable t) {
            sb.append("  intent read error: ").append(t).append('\n');
        }

        // Scan the Activity and its Fragment fields for the package.
        PackageManager pm = act.getPackageManager();
        for (Class<?> c = act.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(act);
                    if (v == null) continue;
                    String s;
                    if (v instanceof String) {
                        s = normalizePackage((String) v);
                        if (!looksLikePackage(s)) continue;
                    } else if (v instanceof android.app.Fragment) {
                        android.app.Fragment fr = (android.app.Fragment) v;
                        s = describeFragment(fr, pm);
                        if (s == null) continue;
                    } else if (v instanceof android.view.View) {
                        s = describeViewForPackage((View) v, pm);
                        if (s == null) continue;
                    } else {
                        continue;
                    }
                    sb.append("  field ").append(c.getSimpleName()).append('.')
                      .append(f.getName()).append(" -> ").append(s).append('\n');
                } catch (Throwable ignored) {
                }
            }
        }
        sb.append("--- END DUMP ---");
        XposedBridge.log(TAG + sb.toString());
    }

    private static final java.util.Set<String> DUMPED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static String describeFragment(android.app.Fragment fr, PackageManager pm) {
        try {
            String cls = fr.getClass().getName();
            if (!cls.contains("AppInfo") && !cls.contains("InstalledApp")
                    && !cls.contains("AppDetails") && !cls.contains("Applications")) {
                return null;
            }
            if (fr.getArguments() != null) {
                for (String k : fr.getArguments().keySet()) {
                    Object v = fr.getArguments().get(k);
                    if (v instanceof String) {
                        String a = normalizePackage((String) v);
                        if (looksLikePackage(a) && pm.getApplicationInfo(a, 0) != null) {
                            return "FRAGMENT " + cls + " arg[" + k + "]=" + a;
                        }
                    }
                }
            }
            return "FRAGMENT " + cls + " (matched class, no pkg arg)";
        } catch (Throwable t) {
            return null;
        }
    }

    /** Walk the view subtree looking for a child that references a real package (e.g. a
     *  package-icon ImageView whose content description or tag holds the package). */
    private static String describeViewForPackage(View v, PackageManager pm) {
        try {
            Object tag = v.getTag();
            if (tag instanceof String) {
                String g = normalizePackage((String) tag);
                if (looksLikePackage(g) && pm.getApplicationInfo(g, 0) != null) {
                    return "VIEW tag=" + g;
                }
            }
            if (v instanceof android.widget.TextView) {
                CharSequence t2 = ((android.widget.TextView) v).getText();
                if (t2 != null) {
                    String g = normalizePackage(t2.toString());
                    if (looksLikePackage(g) && pm.getApplicationInfo(g, 0) != null) {
                        return "VIEW text=" + g;
                    }
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    String r = describeViewForPackage(g.getChildAt(i), pm);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Last-resort injector: hook the framework's "view tree finished its first real layout"
     * callback. At that instant the decor view is guaranteed to have a non-zero size, which is
     * exactly the signal we could not get from the app-info Activity lifecycle on ColorOS.
     */
    private void hookWindowLayout(final ClassLoader cl) {
        try {
            Class<?> vri = XposedHelpers.findClass("android.view.ViewRootImpl", cl);
            for (String m : new String[]{"performTraversals", "doTraversal"}) {
                try {
                    XposedHelpers.findAndHookMethod(vri, m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object root = param.thisObject;
                                View v = getViewFromRoot(root);
                                if (v == null) {
                                    logOnce("getViewFromRoot returned null for "
                                            + root.getClass().getName());
                                    return;
                                }
                                if (!(v instanceof ViewGroup)) return;
                                ViewGroup decor = (ViewGroup) v;
                                if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
                                if (decor.findViewWithTag(FROZEN_TAG) != null) return;

                                Activity act = activityForDecor(decor);
                                if (act == null) {
                                    logOnce("layout: no Activity for decor "
                                            + decor.getClass().getName()
                                            + " ctx=" + decor.getContext().getClass().getName());
                                    return;
                                }
                                // Content-based fallback: a generic shell hosting the app-info
                                // screen is accepted when the page shows the "强行停止" action.
                                final String pkg = resolvePkgForLifecycle(act, decor);
                                XposedBridge.log(TAG + ": TRACE layout " + act.getClass().getName()
                                        + " host=" + isAppInfoHost(act.getClass().getName())
                                        + " contentLooksAppInfo=" + looksLikeAppInfoContent(decor)
                                        + " pkg=" + pkg);
                                if (pkg == null) {
                                    // Dump the full context once per Activity so the OEM-specific
                                    // package-passing mechanism can be identified from logs.
                                    dumpUnknownActivity(act, decor);
                                    return;
                                }
                                XposedBridge.log(TAG + ": layout done for " + pkg
                                        + " activity=" + act.getClass().getName()
                                        + " decor=" + decor.getWidth() + "x" + decor.getHeight());
                                injectButton(act, decor, pkg);
                            } catch (Throwable t) {
                                logOnce("layout hook error: " + t);
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked ViewRootImpl." + m);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": ViewRootImpl." + m + " hook failed: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAILED to hook ViewRootImpl: " + t);
        }
    }

    /**
     * {@code ViewRootImpl.getView()} may be declared on a superclass of the concrete instance
     * (ColorOS/ART often use a generated subclass), so walk the hierarchy instead of using
     * {@code getDeclaredMethod} on the runtime class only.
     */
    private static View getViewFromRoot(Object root) {
        for (Class<?> c = root.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getView");
                m.setAccessible(true);
                Object v = m.invoke(root);
                if (v instanceof View) return (View) v;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            } catch (Throwable t) {
                logOnce("getView invoke failed on " + c.getName() + ": " + t);
            }
        }
        // Fallback: the field that holds the decor view.
        for (Class<?> c = root.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("mView");
                f.setAccessible(true);
                Object v = f.get(root);
                if (v instanceof View) return (View) v;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable t) {
                logOnce("mView read failed on " + c.getName() + ": " + t);
            }
        }
        return null;
    }

    /**
     * Recursively searches a Bundle (and any nested Bundle / Parcelable inside it) for a value
     * that is a syntactically valid package AND is actually installed.
     *
     * <p>The hard verification is what makes this safe: a settings Intent carries plenty of
     * unrelated strings (titles, fragment class names, metric tags), so anything found here is
     * confirmed with {@code getApplicationInfo} before it is accepted.
     */
    private static String findPackageInExtras(android.os.Bundle b, PackageManager pm, int depth) {
        if (b == null || depth > 4) return null;
        try {
            for (String key : b.keySet()) {
                Object v;
                try {
                    v = b.get(key);
                } catch (Throwable t) {
                    continue;
                }
                if (v == null) continue;

                if (v instanceof String) {
                    String s = normalizePackage(((String) v));
                    if (looksLikePackage(s) && isInstalled(pm, s)) {
                        XposedBridge.log(TAG + ": package found in extras key '" + key
                                + "' (depth " + depth + ") = " + s);
                        return s;
                    }
                } else if (v instanceof CharSequence) {
                    // Some shells store the package as a SpannedString.
                    try {
                        String s = normalizePackage(v.toString());
                        if (looksLikePackage(s) && isInstalled(pm, s)) {
                            XposedBridge.log(TAG + ": package found in extras key '" + key
                                    + "' (depth " + depth + ") = " + s);
                            return s;
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (v instanceof android.os.Bundle) {
                    String got = findPackageInExtras((android.os.Bundle) v, pm, depth + 1);
                    if (got != null) return got;
                } else if (v instanceof android.os.Parcelable) {
                    // A Bundle that arrives wrapped (e.g. FragmentArgs) still carries the package.
                    try {
                        android.os.Parcelable p = (android.os.Parcelable) v;
                        java.lang.reflect.Field f = p.getClass().getDeclaredField("mBundle");
                        f.setAccessible(true);
                        Object inner = f.get(p);
                        if (inner instanceof android.os.Bundle) {
                            String got = findPackageInExtras((android.os.Bundle) inner, pm, depth + 1);
                            if (got != null) return got;
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (v instanceof java.util.List) {
                    try {
                        java.util.List list = (java.util.List) v;
                        for (int i = 0; i < list.size() && i < 50; i++) {
                            Object o = list.get(i);
                            if (o instanceof String) {
                                String s = normalizePackage((String) o);
                                if (looksLikePackage(s) && isInstalled(pm, s)) {
                                    return s;
                                }
                            }
                            if (o instanceof android.os.Bundle) {
                                String got = findPackageInExtras((android.os.Bundle) o, pm, depth + 1);
                                if (got != null) return got;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Compact, log-safe description of an Activity's Intent (action / data / extras keys). */
    private static String describeIntent(Activity a) {
        try {
            Intent i = a.getIntent();
            if (i == null) return "intent=null";
            StringBuilder sb = new StringBuilder();
            sb.append("action=").append(i.getAction());
            sb.append(" data=").append(i.getData());
            sb.append(" comp=").append(i.getComponent());
            android.os.Bundle b = i.getExtras();
            if (b != null && !b.isEmpty()) {
                sb.append(" extras{");
                boolean first = true;
                for (String k : b.keySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    Object v = b.get(k);
                    String vs = v == null ? "null"
                            : (v instanceof String ? (String) v : v.getClass().getSimpleName());
                    if (vs != null && vs.length() > 40) vs = vs.substring(0, 40) + "…";
                    sb.append(k).append('=').append(vs);
                }
                sb.append('}');
            } else {
                sb.append(" extras{}");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "intent=<err " + t + ">";
        }
    }

    private static final java.util.Set<String> LOGGED_ONCE = new java.util.HashSet<>();

    private static void logOnce(String msg) {
        try {
            if (LOGGED_ONCE.add(msg)) XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** Walk up from a view to find the Activity context it belongs to. */
    private static Activity activityForDecor(View view) {
        try {
            Context ctx = view.getContext();
            while (ctx != null) {
                if (ctx instanceof Activity) {
                    Activity a = (Activity) ctx;
                    if (!a.isFinishing() && !a.isDestroyed()) return a;
                }
                if (ctx instanceof ContextWrapper) {
                    ctx = ((ContextWrapper) ctx).getBaseContext();
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Target resolution — pure intent based, then verified
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Target resolution — pure intent based, then verified
    // ------------------------------------------------------------------

    private static String resolveTargetPackage(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null) return null;

        // Collect every plausible candidate; the verifier below filters out the noise.
        String pkg = null;
        Uri data = intent.getData();

        if (data != null) {
            if ("package".equals(data.getScheme())) {
                pkg = data.getSchemeSpecificPart();
            }
            if (isNull(pkg)) {
                String seg = normalizePackage(data.getLastPathSegment());
                if (looksLikePackage(seg)) pkg = seg;
            }
            if (isNull(pkg)) {
                // NOTE: do not gate on looksLikePackage(data.getPath()) first — "//com.x.y"
                // contains slashes and would fail the raw check before normalisation runs.
                pkg = normalizePackage(data.getPath());
                if (!looksLikePackage(pkg)) pkg = null;
            }
        }
        if (isNull(pkg)) {
            // OEM shells (ColorOS/OPlus) pass the target package inside a NESTED fragment-args
            // Bundle, e.g.
            //   extras{ :settings:show_fragment     = com.android.settings.applications.appinfo…
            //            :settings:show_fragment_args = Bundle{package_name=com.xx.yy, …} }
            // The inner key name varies per OEM and per fragment, so recurse through every
            // Bundle and keep the first value that is a verified installed package.
            pkg = findPackageInExtras(intent.getExtras(), activity.getPackageManager(), 0);
        }
        if (isNull(pkg)) pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (isNull(pkg)) {
            ComponentName cn = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME);
            if (cn != null) pkg = cn.getPackageName();
        }
        if (isNull(pkg)) {
            String cnStr = intent.getStringExtra(Intent.EXTRA_COMPONENT_NAME);
            if (cnStr != null && cnStr.contains(":")) {
                pkg = cnStr.substring(0, cnStr.indexOf(':'));
            }
        }

        if (isNull(pkg) && ACTION_APP_DETAILS.equals(intent.getAction())
                && intent.getData() == null) {
            XposedBridge.log(TAG + ": app-details action but no package resolved in "
                    + activity.getClass().getName());
            return null;
        }

        if (isNull(pkg) || !looksLikePackage(pkg)) return null;
        // Normalise BEFORE the identity checks: shells hand over "//com.x.y", "/com.x.y" and
        // "package:com.x.y", all of which would otherwise fail the getApplicationInfo() lookup
        // below and be silently discarded.
        pkg = normalizePackage(pkg);
        if (pkg == null || !looksLikePackage(pkg)) return null;
        if (MODULE_PKG.equals(pkg)) return null;

        // Hard verification: the candidate must be an installed package, otherwise this
        // intent is unrelated (e.g. a wallpaper picker, a file provider, a search action).
        try {
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai == null) return null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": candidate '" + pkg + "' not installed, ignored");
            return null;
        }
        return pkg;
    }

    private static boolean isNull(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean looksLikePackage(String s) {
        String t = normalizePackage(s);
        if (t == null) return false;
        if (t.length() < 3 || t.length() > 255) return false;
        if (t.indexOf(' ') >= 0) return false;
        int dot = t.indexOf('.');
        return dot > 0 && dot < t.length() - 1;
    }

    /**
     * Normalises a raw package candidate.
     *
     * <p>OEM shells hand the package over in several mangled forms, all of which used to defeat
     * {@link #looksLikePackage}:
     * <ul>
     *   <li>{@code //com.android.chrome}   — MIUI puts a slash in the {@code package_name} extra</li>
     *   <li>{@code /com.android.chrome}    — leftover path separator</li>
     *   <li>{@code package:com.x.y}        — a URI prefix</li>
     *   <li>{@code  com.x.y \n}            — stray whitespace</li>
     * </ul>
     * Every candidate — whether it came from an extra, a URI or a private field — is passed
     * through here before validation, so a leading slash can no longer silently reject a
     * perfectly good package.
     *
     * @return the cleaned package, or {@code null} if nothing usable is left
     */
    private static String normalizePackage(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        // Strip any scheme/URI prefix: package:com.x.y, content://…, etc.
        int colon = t.lastIndexOf(':');
        if (colon >= 0 && colon < t.length() - 1) {
            String after = t.substring(colon + 1).trim();
            // Only accept the suffix when it actually looks like a package, otherwise a colon
            // inside a genuine package name would be mangled.
            String cand = stripSlashes(after);
            if (looksLikePackageRaw(cand)) return cand;
        }
        return stripSlashes(t);
    }

    /** Removes leading/trailing slashes and whitespace; {@code //a.b} becomes {@code a.b}. */
    private static String stripSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '/' || s.charAt(start) == '\\'
                || s.charAt(start) == ' ' || s.charAt(start) == '\t'
                || s.charAt(start) == '\n' || s.charAt(start) == '\r')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '/' || s.charAt(end - 1) == '\\'
                || s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t'
                || s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return start >= end ? null : s.substring(start, end);
    }

    /** Package-shape check without re-entering {@link #normalizePackage}. */
    private static boolean looksLikePackageRaw(String t) {
        if (t == null) return false;
        if (t.length() < 3 || t.length() > 255) return false;
        int dot = t.indexOf('.');
        return dot > 0 && dot < t.length() - 1;
    }

    // ------------------------------------------------------------------
    // Injection — retry + layout signal so OEM timing cannot break it
    // ------------------------------------------------------------------

    private void scheduleInject(final Activity activity, final String pkg, final int attempt) {
        if (activity == null || activity.isFinishing()) return;
        if (activity.isDestroyed()) return;

        final long delay;
        if (attempt == 0) delay = 0L;                       // try inline on the UI thread first
        else if (attempt == 1) delay = 120L;
        else delay = Math.min(150L * (attempt - 1), 800L);

        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        // The transient host (e.g. InstalledAppDetailsTop) is dying while the
                        // REAL app-info activity is already on top. Retrying here is pointless —
                        // hand the work to the focused window, otherwise the retry window closes
                        // before the content is laid out and the button silently never appears.
                        XposedBridge.log(TAG + ": host " + activity.getClass().getSimpleName()
                                + " is finishing, handing over to focused window for " + pkg);
                        handOverToFocusedWindow(activity, pkg, attempt);
                        return;
                    }
                    XposedBridge.log(TAG + ": inject attempt " + attempt + " for " + pkg
                            + " parent=" + describeParent(activity));
                    if (injectOrListen(activity, pkg)) {
                        XposedBridge.log(TAG + ": inject succeeded on attempt " + attempt);
                        return;
                    }
                    if (attempt + 1 < MAX_ATTEMPTS) {
                        scheduleInject(activity, pkg, attempt + 1);
                    } else {
                        handOverToFocusedWindow(activity, pkg, attempt);
                        XposedBridge.log(TAG + ": GAVE UP injecting button for " + pkg
                                + " after " + MAX_ATTEMPTS + " attempts");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": inject task error for " + pkg + ": " + t);
                    try {
                        handOverToFocusedWindow(activity, pkg, attempt);
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        // NOTE (ColorOS): a bare Handler.postDelayed() is unreliable here — its message can be
        // deferred for minutes by the OEM scheduler, so retries silently never run. Keep the
        // delay tiny (sub-second) and always re-enter through runOnUiThread, which posts to the
        // front of the main looper queue.
        try {
            if (delay <= 0) {
                activity.runOnUiThread(task);
            } else {
                mainHandler().postDelayed(task, delay);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": scheduleInject failed for " + pkg + ": " + t);
        }
    }

    /**
     * Delayed re-try of {@link #handOverToFocusedWindow}.
     *
     * <p>Timing on the two-Activity app-info handoff: the transient host dies, the whole settings
     * stack is momentarily empty, and the surviving page is created a fraction of a second later.
     * A single synchronous look-up therefore always finds nothing.
     */
    private void scheduleDelayedHandOver(final Activity dying, final String pkg, final int attempt) {
        try {
            mainHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        handOverToFocusedWindow(dying, pkg, attempt);
                    } catch (Throwable ignored) {
                    }
                }
            }, HANDOVER_RETRY_MS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Re-targets the injection at whichever Activity currently owns the focused window.
     *
     * <p>Needed because OEM shells implement the app-info screen as a two-Activity handoff:
     * a transient host (e.g. AOSP/ColorOS {@code InstalledAppDetailsTop}) starts, immediately
     * finishes, and the surviving page lives in a different Activity that was never handed a
     * usable lifecycle callback. Without this transfer the retry ladder dies with the transient
     * host and the button is never injected.
     */
    private void handOverToFocusedWindow(final Activity dying, final String pkg, int attempt) {
        try {
            if (pkg == null || MODULE_PKG.equals(pkg)) return;
            Activity target = findFocusedActivity(dying);
            if (target == null) {
                // The transient host is destroyed BEFORE the real page is laid out, so the target
                // genuinely does not exist yet. Do not give up: the surviving Activity will get a
                // window-focus callback a moment later, and its own hook runs the fast path.
                // Retry a few times so the hand-over works even when focus never re-fires.
                if (attempt < MAX_HANDOVER_ATTEMPTS) {
                    if (attempt % 10 == 0) {
                        XposedBridge.log(TAG + ": handOver: still waiting for the surviving page "
                                + "(attempt " + attempt + "/" + MAX_HANDOVER_ATTEMPTS + ") for " + pkg);
                    }
                    scheduleDelayedHandOver(dying, pkg, attempt + 1);
                } else {
                    XposedBridge.log(TAG + ": handOver: giving up after " + MAX_HANDOVER_ATTEMPTS
                            + " attempts (~" + (MAX_HANDOVER_ATTEMPTS * HANDOVER_RETRY_MS / 1000)
                            + "s) for " + pkg);
                }
                return;
            }
            if (target == dying) {
                XposedBridge.log(TAG + ": handOver: focused window is still the dying host");
                return;
            }
            // CONTENT GATE (with retry).
            //
            // The hand-over target is whichever window is focused when the transient host dies.
            // On ColorOS that is the long-lived settings shell (OplusSettingsHomepageActivity),
            // which is ALSO what the user sees on the settings home screen — injecting there
            // without a content check put the freeze bar on the home screen. That is why the gate
            // exists.
            //
            // IMPORTANT: the gate must NOT end the retry ladder. The shell renders the app-info
            // page in place, so at the moment the dying host is detected the shell may still be
            // painting the settings home screen; the force-stop action only appears a few hundred
            // milliseconds later. Treating "not app-info yet" as final aborted the hand-over and
            // the button never appeared. Retry instead, and let the gate keep rejecting the page
            // for as long as it genuinely is not an app-info screen.
            ViewGroup content = resolveContentParent(target);
            if (content == null || !looksLikeAppInfoContent(content)) {
                if (attempt % 5 == 0) {
                    XposedBridge.log(TAG + ": handOver: focused window "
                            + target.getClass().getSimpleName()
                            + " is not an app-info page yet (attempt " + attempt + "/"
                            + MAX_HANDOVER_ATTEMPTS + "), retrying for " + pkg);
                }
                if (attempt < MAX_HANDOVER_ATTEMPTS) {
                    scheduleDelayedHandOver(dying, pkg, attempt + 1);
                } else {
                    XposedBridge.log(TAG + ": handOver: gave up, focused window never became an "
                            + "app-info page for " + pkg);
                }
                return;
            }
            rememberPkg(pkg);
            resolveTargetUser(target, pkg);
            XposedBridge.log(TAG + ": handOver to " + target.getClass().getName()
                    + " (attempt " + attempt + ") for " + pkg
                    + " laidOut=" + (content.getWidth() > 0 && content.getHeight() > 0));
            if (injectOrListen(target, pkg)) {
                XposedBridge.log(TAG + ": handOver inject succeeded for " + pkg);
            } else {
                probeForAppInfoPage(target, "handover");
            }
        } catch (Throwable t) {
            logOnce("handOverToFocusedWindow failed for " + pkg + ": " + t);
        }
    }

    /**
     * Best-effort lookup of the Activity that currently owns the focused window.
     *
     * <p>Uses the public {@code ActivityThread} → {@code ActivityClient} chain reflectively; it
     * is intentionally best-effort because every link may be absent or renamed on OEM builds.
     */
    private static Activity findFocusedActivity(Activity exclude) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentActivityThread").invoke(null);
            if (app == null) return null;

            // Android 9+ restricts ActivityThread.getActivities() to the caller PID. When the app
            // manager renders the detail page in a separate process, that method returns null, so
            // fall back to the mActivities field and filter by process id ourselves.
            Object[] activities = readActivities(at, app);

            java.lang.reflect.Method isFocused = null;
            try {
                isFocused = Class.forName("android.app.Activity").getMethod("hasWindowFocus");
            } catch (Throwable ignored) {
            }
            if (isFocused != null) {
                for (Object rec : activities) {
                    Activity a = activityOf(rec);
                    if (a == null || a == exclude) continue;
                    try {
                        Object foc = isFocused.invoke(a);
                        if (foc instanceof Boolean && (Boolean) foc) return a;
                    } catch (Throwable ignored) {
                    }
                }
            }
            // Prefer the resumed activity, then the most recently started one.
            Activity fallback = null;
            for (int i = activities.length - 1; i >= 0; i--) {
                Activity a = activityOf(activities[i]);
                if (a == null || a == exclude) continue;
                try {
                    if (a.isFinishing() || a.isDestroyed()) continue;
                    if (fallback == null) fallback = a;
                    java.lang.reflect.Method isRes = null;
                    try {
                        isRes = Class.forName("android.app.Activity")
                                .getMethod("isResumed");
                    } catch (Throwable ignored) {
                    }
                    if (isRes != null && (Boolean) isRes.invoke(a)) return a;
                } catch (Throwable ignored) {
                }
            }
            return fallback;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Reads the live Activity records of THIS process, bypassing the API-level restriction. */
    private static Object[] readActivities(Class<?> activityThread, Object app) {
        // Preferred: the public accessor, which works on our own process.
        try {
            Object[] a = (Object[]) activityThread.getMethod("getActivities").invoke(app);
            if (a != null) return a;
        } catch (Throwable ignored) {
        }
        // Fallback: walk mActivities (ArrayMap<IBinder, ActivityClientRecord>).
        try {
            java.lang.reflect.Field f = null;
            Class<?> c = activityThread;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("mActivities");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) return new Object[0];
            f.setAccessible(true);
            Object map = f.get(app);
            if (map == null) return new Object[0];

            java.util.List<Object> out = new java.util.ArrayList<>();
            if (map instanceof java.util.Map) {
                for (Object v : ((java.util.Map<?, ?>) map).values()) {
                    if (v != null) out.add(v);
                }
            } else {
                // android.util.ArrayMap: values() via reflection
                java.lang.reflect.Method values = map.getClass().getMethod("values");
                Object coll = values.invoke(map);
                if (coll instanceof Iterable) {
                    for (Object v : (Iterable<?>) coll) {
                        if (v != null) out.add(v);
                    }
                }
            }
            return out.toArray(new Object[0]);
        } catch (Throwable ignored) {
            return new Object[0];
        }
    }

    /** Extracts the Activity from an ActivityClientRecord (or returns it if it IS an Activity). */
    private static Activity activityOf(Object record) {
        if (record == null) return null;
        if (record instanceof Activity) return (Activity) record;
        try {
            java.lang.reflect.Field f = record.getClass().getDeclaredField("activity");
            f.setAccessible(true);
            Object a = f.get(record);
            return (a instanceof Activity) ? (Activity) a : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Diagnostic helper: describe the candidate containers and their measured sizes. */
    private static String describeParent(Activity activity) {
        try {
            StringBuilder sb = new StringBuilder();
            View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                sb.append("content(").append(content.getClass().getSimpleName())
                        .append(" ").append(content.getWidth()).append("x")
                        .append(content.getHeight()).append(") ");
            }
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor != null) {
                sb.append("decor(").append(decor.getClass().getSimpleName())
                        .append(" ").append(decor.getWidth()).append("x")
                        .append(decor.getHeight()).append(")");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "n/a";
        }
    }

    /** @return true when the button is present; otherwise arm a one-shot layout listener. */
    private boolean injectOrListen(Activity activity, String pkg) {
        try {
            ViewGroup content = resolveContentParent(activity);
            if (content == null) {
                XposedBridge.log(TAG + ": no content parent for " + pkg);
                return false;
            }
            if (content.findViewWithTag(FROZEN_TAG) != null) return true;

            // Still not laid out — arm a one-shot listener so the first real layout pass wins,
            // and let the retry loop keep going in parallel as a safety net.
            if (content.getWidth() <= 0 || content.getHeight() <= 0) {
                armLayoutListener(content, activity, pkg);
                XposedBridge.log(TAG + ": content not laid out yet for " + pkg
                        + " (w=" + content.getWidth() + " h=" + content.getHeight() + ")");
                return false;
            }
            return injectButton(activity, content, pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": injectOrListen error for " + pkg + ": " + t);
            return false;
        }
    }

    private void armLayoutListener(final ViewGroup content, final Activity activity,
                                   final String pkg) {
        try {
            ViewTreeObserver vto = content.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) return;
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    ViewTreeObserver cur = content.getViewTreeObserver();
                    if (cur != null && cur.isAlive()) {
                        cur.removeOnGlobalLayoutListener(this);
                    }
                    if (content.findViewWithTag(FROZEN_TAG) != null) return;
                    if (content.getWidth() <= 0) return;
                    injectButton(activity, content, pkg);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": armLayoutListener failed: " + t);
        }
    }

    /**
     * Resolves the container to inject into.
     *
     * <p>IMPORTANT (ColorOS): prefer the DecorView over {@code android.R.id.content}.
     * The two-stage app-info flow ({@code InstalledAppDetailsTop} -&gt;
     * {@code InstalledAppDetails}) leaves the transient activity's content view at
     * width/height 0 forever, so a content-view-based button never gets a layout pass and
     * the queued retries die with the process restart. The DecorView of the surviving
     * activity is always measured, so the button reliably appears.
     */
    private static ViewGroup resolveContentParent(Activity activity) {
        // 1) The activity's own content view, when it is a properly measured container.
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup && ((ViewGroup) content).getWidth() > 0
                && ((ViewGroup) content).getHeight() > 0) {
            return (ViewGroup) content;
        }
        // 2) DecorView — always laid out, and survives the two-stage transition.
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor instanceof ViewGroup) return (ViewGroup) decor;
        } catch (Throwable ignored) {
        }
        // 3) Last resort: content view even if unmeasured, so the layout listener can fire.
        if (content instanceof ViewGroup) return (ViewGroup) content;
        try {
            View root = activity.getWindow() != null
                    ? activity.getWindow().getDecorView().getRootView() : null;
            if (root instanceof ViewGroup) return (ViewGroup) root;
        } catch (Throwable ignored) {
        }
        return null;
    }


    private boolean injectButton(Activity activity, ViewGroup parent, String pkg) {
        try {
            // Guarantee a usable container: fall back to the DecorView when the caller could
            // not resolve one, so a null parent can never silently drop the button.
            if (parent == null) {
                parent = resolveContentParent(activity);
            }
            if (parent == null) {
                XposedBridge.log(TAG + ": injectButton has no parent for " + pkg);
                return false;
            }

            // CROSS-PARENT de-duplication.
            //
            // Two independent injection paths exist (ViewRootImpl.performTraversals targeting the
            // DecorView, and the retry loop targeting android.R.id.content). They look at
            // DIFFERENT containers, so a plain parent.findViewWithTag() check reports "absent"
            // on the other one and BOTH end up injecting — which is how two identical bars ended
            // up stacked on screen. Always check the whole window before adding anything.
            if (isBarAlreadyInWindow(activity, parent) || isBarHost(activity)) {
                XposedBridge.log(TAG + ": bar already present (window="
                        + isBarAlreadyInWindow(activity, parent)
                        + ", host=" + isBarHost(activity) + "), skipping duplicate for " + pkg);
                return true;
            }

            final PackageManager pm = activity.getPackageManager();
            int state = safeEnabledState(activity, pm, pkg);
            final boolean frozen = isFrozen(state);

            // Horizontal bar: [ freeze/unfreeze ] [ frozen-apps list ]
            // A LinearLayout is used instead of two sibling views so the pair is always centred
            // together and never overlaps OEM bottom controls.
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setTag(FROZEN_BAR_TAG);

            // Use plain TextViews rather than Buttons: OEM themes (ColorOS/OPlus) restyle
            // Button backgrounds and paddings so aggressively that the label ends up invisible
            // (you see a coloured blob with no text). Self-drawn backgrounds are theme-independent.
            TextView btn = new TextView(activity);
            btn.setTag(FROZEN_TAG);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setTextColor(Color.WHITE);
            btn.setGravity(Gravity.CENTER);
            btn.setSingleLine(false);
            btn.setMaxLines(2);
            btn.setIncludeFontPadding(false);
            btn.setPadding(dp(activity, 22), dp(activity, 12),
                    dp(activity, 22), dp(activity, 12));
            btn.setElevation(dp(activity, 10));
            applyPill(btn, frozen);

            // Resolve which Android user this page describes BEFORE the label is built, so the
            // "[分身]" prefix and the user-scoped state read are both correct from the first
            // frame. On OPlus MultiApp the clone's page runs in the owner's settings process, so
            // this per-Activity lookup is the only thing that keeps a clone freeze from
            // landing on the phone's original app.
            resolveTargetUser(activity, pkg);
            int scopedState = readEnabledStateFor(activity, pm, pkg);
            updateLabel(btn, scopedState, pkg);

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bar.addView(btn, btnLp);

            // Entry point to the frozen-apps list. Shows a live count badge so the user can
            // tell at a glance whether anything is frozen at all.
            final TextView listBtn = buildListButton(activity);
            LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            listLp.leftMargin = dp(activity, 10);
            listBtn.setLayoutParams(listLp);
            bar.addView(listBtn, listLp);
            refreshListBadge(listBtn);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(activity, 150); // clear of the gesture nav bar
            bar.setLayoutParams(lp);
            bar.setContentDescription(tr("冻结操作栏", "Freeze action bar"));

            final String target = pkg;
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(activity, (TextView) v, target);
                }
            });
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showDiagnostics(activity, target);
                    return true;
                }
            });
            listBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFrozenList(activity);
                }
            });

            parent.addView(bar);
            markBarHost(activity);
            bar.post(new Runnable() {
                @Override
                public void run() {
                    // Log the real measured size so a "coloured blob with no text" report can be
                    // distinguished from a text-rendering issue.
                    android.util.Log.i(TAG, "bar measured " + bar.getWidth() + "x" + bar.getHeight()
                            + " btn='" + btn.getText() + "'"
                            + " btnSize=" + btn.getWidth() + "x" + btn.getHeight()
                            + " listBtn='" + listBtn.getText() + "'"
                            + " listSize=" + listBtn.getWidth() + "x" + listBtn.getHeight());
                }
            });
            XposedBridge.log(TAG + ": button bar injected for " + pkg
                    + " (state=" + stateToName(state) + ") into " + parent.getClass().getName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": injectButton failed for " + pkg + ": " + t);
            return false;
        }
    }

    /** Builds the "frozen apps" entry button; the label is filled by {@link #refreshListBadge}. */
    private static TextView buildListButton(Activity activity) {
        TextView t = new TextView(activity);
        t.setTag(FROZEN_LIST_TAG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12));
        t.setElevation(dp(activity, 10));
        t.setBackground(solidWith(LIST_BADGE));
        t.setContentDescription(tr("查看已冻结应用列表", "View frozen apps"));
        return t;
    }

    /**
     * Cached frozen-count, so the badge does not trigger a full
     * {@code getInstalledApplications()} scan on every tap.
     *
     * <p>{@code getInstalledApplications} costs roughly 100ms on a device with a few hundred
     * apps and runs on the UI thread, which made freezing feel laggy. The cache is only
     * consulted by the badge; opening the list always performs a fresh scan, so the user can
     * never act on stale data. {@link #invalidateFrozenCount()} is called after every state
     * change, and a short TTL guards against other apps freezing themselves.
     */
    private static volatile int cachedFrozenCount = -1;
    private static volatile long cachedCountAt = 0L;
    private static final long COUNT_CACHE_MS = 1500L;

    private static void invalidateFrozenCount() {
        cachedFrozenCount = -1;
        cachedCountAt = 0L;
    }

    private static int frozenCountFor(Context ctx) {
        long now = System.currentTimeMillis();
        int c = cachedFrozenCount;
        if (c >= 0 && now - cachedCountAt < COUNT_CACHE_MS) return c;
        int fresh = queryFrozenPackages(ctx).size();
        cachedFrozenCount = fresh;
        cachedCountAt = now;
        return fresh;
    }

    /** Updates the list button label with the current frozen-app count. */
    private static void refreshListBadge(TextView listBtn) {
        try {
            int n = frozenCountFor(listBtn.getContext());
            listBtn.setText(n > 0
                    ? (tr("❄ 已冻结 ", "❄ Frozen ") + n)
                    : tr("❄ 已冻结 0", "❄ Frozen 0"));
            listBtn.setAlpha(n > 0 ? 1f : 0.6f);
            // Never rely on colour alone — the label text already carries the state, and this
            // description makes the badge distinguishable for screen readers next to the
            // primary freeze button.
            listBtn.setContentDescription(tr("查看已冻结应用列表，当前 " + n + " 个",
                    "View frozen apps, " + n + " frozen"));
        } catch (Throwable t) {
            listBtn.setText(tr("❄ 已冻结", "❄ Frozen"));
        }
    }


    // ------------------------------------------------------------------
    // Frozen-apps list
    // ------------------------------------------------------------------

    /**
     * @return packages whose enabled state is exactly {@code DISABLED_USER}, i.e. frozen by the
     * user (or by this module) — deliberately excluding factory/system-disabled packages so the
     * list stays short and meaningful.
     */
    private static List<String> queryFrozenPackages(Context ctx) {
        // Keyed by package so we can sort by "most recently touched" below.
        java.util.Map<String, Long> frozen = new java.util.LinkedHashMap<>();
        if (ctx == null) return new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        // Scoped to the user this Settings process belongs to, so a clone only ever lists (and
        // therefore only ever unfreezes) its OWN copies — never the owner's.
        final int user = ctx instanceof Activity
                ? targetUserFor((Activity) ctx) : currentProcessUser();
        try {
            // getInstalledApplications already filters by the calling user's context.
            List<ApplicationInfo> apps =
                    pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            if (apps == null) return new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (ai.packageName.equals(MODULE_PKG)) continue;
                try {
                    if (enabledStateForUser(ctx, pm, ai.packageName, user)
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                        // Sort key = the moment we froze it. Packages frozen before this
                        // session (or by other means) have no record and sink to the bottom,
                        // ordered by name.
                        Long remembered = freezeTimeOf(ai.packageName);
                        frozen.put(ai.packageName, remembered == null ? Long.MIN_VALUE : remembered);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            logOnce("queryFrozenPackages failed: " + t);
        }
        // Most recently frozen/updated first — matches "which one did I just freeze?".
        List<String> out = new ArrayList<>(frozen.keySet());
        java.util.Collections.sort(out, (a, b) -> {
            long ta = frozen.get(a), tb = frozen.get(b);
            int c = Long.compare(tb, ta);          // descending
            return c != 0 ? c : a.compareToIgnoreCase(b);
        });
        return out;
    }

    /** In-memory record of when we froze a package, used only for list ordering. */
    private static final java.util.Map<String, Long> FREEZE_TIMES =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Long>());

    private static void markFrozenAt(String pkg) {
        try {
            FREEZE_TIMES.put(pkg, System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    private static void clearFrozenAt(String pkg) {
        try {
            FREEZE_TIMES.remove(pkg);
        } catch (Throwable ignored) {
        }
    }

    private static Long freezeTimeOf(String pkg) {
        try {
            return FREEZE_TIMES.get(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Shows a dialog listing every frozen app. Tapping a row unfreezes it immediately and
     * refreshes the list in place.
     */
    /**
     * The single live list dialog, kept as a field so it can be dismissed and rebuilt instead
     * of stacked. Without this, unfreezing an item called {@link #showFrozenList} again and
     * left the previous dialog alive underneath — the user then saw a stale list whose rows no
     * longer matched reality, and repeated taps kept piling up dialogs.
     */
    private android.app.AlertDialog activeListDialog;

    /**
     * Opens (or re-opens) the frozen-apps list.
     *
     * <p>Strictly single-instance: any previously open list dialog is dismissed before a new
     * one is created, so at most one list is ever visible.
     */
    private void showFrozenList(final Activity activity) {
        try {
            final PackageManager pm = activity.getPackageManager();
            invalidateFrozenCount();          // the list must never render cached data
            final List<String> frozen = queryFrozenPackages(activity);

            // Dismiss first in every branch, so an empty list can never leave a stale dialog.
            dismissListDialog();

            if (frozen.isEmpty()) {
                showEmptyState(activity);
                return;
            }

            final LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 8);
            list.setPadding(pad, pad, pad, pad);

            final TextView header = new TextView(activity);
            header.setTag(HEADER_TAG);
            header.setText(tr("已冻结应用（" + frozen.size() + "）",
                    "Frozen apps (" + frozen.size() + ")"));
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setTextColor(0xFF5A5A5A);
            header.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 4));
            list.addView(header);

            final TextView hint = new TextView(activity);
            hint.setText(tr("点击任意一行即可解冻", "Tap any row to unfreeze"));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            hint.setTextColor(0xFF707070);
            hint.setPadding(dp(activity, 12), dp(activity, 0), dp(activity, 12), dp(activity, 8));
            list.addView(hint);

            final Refresh refresh = new Refresh();
            for (final String pkg : frozen) {
                list.addView(buildFrozenRow(activity, pm, pkg, refresh));
            }

            ScrollView scroll = new ScrollView(activity);
            scroll.addView(list);

            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(scroll)
                    .setPositiveButton(tr("关闭", "Close"), null)
                    .create();
            dialog.setCanceledOnTouchOutside(true);
            activeListDialog = dialog;
            // Clear the field when the user closes it, so a later check does not
            // try to dismiss a dead window.
            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface d) {
                    if (activeListDialog == d) activeListDialog = null;
                }
            });
            dialog.show();

            XposedBridge.log(TAG + ": frozen list shown, " + frozen.size() + " entries");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showFrozenList failed: " + t);
            toast(activity, tr("打开列表失败：", "Could not open the list: ") + t.getMessage(), Toast.LENGTH_LONG);
        }
    }

    /** Dismisses the list dialog if one is showing. Safe to call repeatedly. */
    private void dismissListDialog() {
        try {
            if (activeListDialog != null) {
                if (activeListDialog.isShowing()) activeListDialog.dismiss();
                activeListDialog = null;
            }
        } catch (Throwable t) {
            logOnce("dismissListDialog failed: " + t);
            activeListDialog = null;
        }
    }

    /**
     * Empty state shown INSIDE the dialog rather than as a transient Toast, so the user has a
     * persistent, readable explanation instead of a message that disappears before it lands.
     */
    private void showEmptyState(Activity activity) {
        try {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int pad = dp(activity, 32);
            box.setPadding(pad, pad, pad, pad);

            TextView icon = new TextView(activity);
            icon.setText("❄");
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
            icon.setGravity(Gravity.CENTER);
            box.addView(icon);

            TextView title = new TextView(activity);
            title.setText(tr("暂无已冻结应用", "No frozen apps"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(0xFF1A1A1A);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(activity, 12), 0, 0);
            box.addView(title);

            TextView sub = new TextView(activity);
            sub.setText(tr("在应用信息页点击「冻结此应用」\n被冻结的应用会出现在这里",
                    "Tap \u201CFreeze this app\u201D on an app-info page\nFrozen apps will show up here"));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            sub.setTextColor(0xFF707070);
            sub.setGravity(Gravity.CENTER);
            sub.setLineSpacing(dp(activity, 4), 1f);
            sub.setPadding(0, dp(activity, 8), 0, 0);
            box.addView(sub);

            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(box)
                    .setPositiveButton(tr("知道了", "Got it"), null)
                    .create();
            dialog.setCanceledOnTouchOutside(true);
            activeListDialog = dialog;
            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface d) {
                    if (activeListDialog == d) activeListDialog = null;
                }
            });
            dialog.show();
            XposedBridge.log(TAG + ": empty state shown");
        } catch (Throwable t) {
            logOnce("showEmptyState failed: " + t);
            toast(activity, tr("当前没有已冻结的应用", "No frozen apps yet"), Toast.LENGTH_SHORT);
        }
    }

    /**
     * Undo affordance after unfreezing: a short Toast with an "撤销" action that re-freezes the
     * package. Unfreezing is reversible, so a one-tap undo is cheaper than a confirmation
     * dialog would be.
     */
    private void toastWithUndo(final Activity activity, final String pkg, final String label) {
        try {
            final android.widget.Toast t = android.widget.Toast.makeText(
                    activity, tr("已解冻 " + label + "　撤销", "Unfrozen " + label + "  Undo"), Toast.LENGTH_LONG);
            // Toast#setAction is a hidden API; reach it reflectively and degrade gracefully
            // to a plain toast (with an "撤销" hint) when the ROM does not expose it.
            try {
                java.lang.reflect.Method setAction = t.getClass().getMethod(
                        "setAction", android.view.View.OnClickListener.class, CharSequence.class);
                setAction.invoke(t, new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        undoFreeze(activity, pkg, label);
                    }
                }, tr("撤销", "Undo"));
            } catch (Throwable hidden) {
                XposedBridge.log(TAG + ": Toast.setAction unavailable (" + hidden
                        + "), falling back to plain toast");
            }
            t.show();
        } catch (Throwable t) {
            logOnce("toastWithUndo failed: " + t);
            toast(activity, tr("已解冻 " + label, "Unfrozen " + label), Toast.LENGTH_SHORT);
        }
    }

    /** Re-freezes a package from the undo affordance. */
    private void undoFreeze(final Activity activity, final String pkg, final String label) {
        try {
            pmsSetEnabled(activity, pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    targetUserFor(activity));
            int st = safeEnabledState(activity, activity.getPackageManager(), pkg);
            if (isFrozen(st)) {
                invalidateFrozenCount();
                markFrozenAt(pkg);
                toast(activity, tr("已恢复冻结 " + label, "Re-frozen " + label), Toast.LENGTH_SHORT);
                showFrozenList(activity);
            } else {
                toast(activity, tr("撤销失败，系统拒绝了停用请求",
                    "Undo failed: the system rejected the disable request"), Toast.LENGTH_LONG);
            }
            XposedBridge.log(TAG + ": undo freeze for " + pkg + " -> " + st);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": undo failed for " + pkg + ": " + t);
            toast(activity, tr("撤销失败", "Undo failed"), Toast.LENGTH_LONG);
        }
    }

    /** Handle used by rows to re-render the list in place after a state change. */
    private final class Refresh {
        void refresh(Activity a) {
            showFrozenList(a);
        }
    }

    /** One row in the frozen list: icon + label + package, tappable to unfreeze. */
    private View buildFrozenRow(final Activity activity, final PackageManager pm,
                                       final String pkg, final Refresh refresh) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        row.setBackground(rippleBg(0xFFF2F5F7));
        row.setClickable(true);
        row.setFocusable(true);
        // Minimum 48dp touch target for accessibility.
        row.setMinimumHeight(dp(activity, 56));

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(activity, 40),
                dp(activity, 40));
        iconLp.rightMargin = dp(activity, 12);
        row.addView(icon, iconLp);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(pkg));
        } catch (Throwable t) {
            icon.setBackground(solidWith(0xFFDDDDDD));
        }

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String label = safeAppLabel(activity, pm, pkg);
        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xFF1A1A1A);
        col.addView(name);

        TextView sub = new TextView(activity);
        sub.setText(pkg);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setTextColor(0xFF707070);
        col.addView(sub);

        // Screen readers announce the whole row, not three disconnected fragments.
        row.setContentDescription(tr(label + "，" + pkg + "，已冻结，点击解冻",
                    label + ", " + pkg + ", frozen, tap to unfreeze"));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unfreezeFromList(activity, pm, pkg, refresh, (View) v);
            }
        });
        return row;
    }

    /**
     * Unfreezes one package and re-renders the list.
     *
     * <p>Guards against double-tap: the row is disabled the moment it is handled, so a rapid
     * second tap cannot fire two {@code setApplicationEnabledSetting} calls (which would leave
     * the list and the system state disagreeing).
     */
    private void unfreezeFromList(final Activity activity, final PackageManager pm,
                                  final String pkg, final Refresh refresh, final View row) {
        if (row != null && !row.isEnabled()) return;   // debounce
        if (row != null) row.setEnabled(false);

        try {
            pmsSetEnabled(activity, pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    targetUserFor(activity));

            int now = safeEnabledState(activity, pm, pkg);
            if (isFrozen(now)) {
                if (row != null) row.setEnabled(true);
                toast(activity, tr("解冻失败（可能被系统拦截）",
                        "Unfreeze failed (blocked by the system)"), Toast.LENGTH_LONG);
                XposedBridge.log(TAG + ": unfreeze rejected for " + pkg + ", state=" + now);
                return;
            }
            invalidateFrozenCount();
            clearFrozenAt(pkg);
            toastWithUndo(activity, pkg, safeAppLabel(activity, pm, pkg));
            XposedBridge.log(TAG + ": unfrozen from list: " + pkg);

            // Re-query and rebuild. The previous dialog is dismissed inside showFrozenList(),
            // so exactly one list is ever on screen.
            refresh.refresh(activity);
        } catch (Throwable t) {
            if (row != null) row.setEnabled(true);   // allow retry
            XposedBridge.log(TAG + ": unfreezeFromList failed for " + pkg + ": " + t);
            toast(activity, tr("解冻失败：", "Unfreeze failed: ") + t.getMessage(), Toast.LENGTH_LONG);
        }
    }

    private static String safeAppLabel(Context ctx, PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    /** Short alias used in toasts so the user sees the app name, not the package. */
    private static String labelOf(PackageManager pm, String pkg) {
        return safeAppLabel(null, pm, pkg);
    }

    /** A light rounded background that darkens on press, for list rows. */
    private static android.graphics.drawable.Drawable rippleBg(final int color) {
        try {
            android.graphics.drawable.StateListDrawable sl =
                    new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, solidWith(0xFFE0E4E7));
            sl.addState(new int[]{}, solidWith(color));
            return sl;
        } catch (Throwable t) {
            return solidWith(color);
        }
    }


    // ------------------------------------------------------------------
    // Action
    // ------------------------------------------------------------------

    private void toggle(Activity activity, TextView btn, String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();

            String block = criticalReason(activity, pm, pkg);
            if (block != null) {
                toast(activity, tr("已阻止冻结：", "Freeze blocked: ") + block, Toast.LENGTH_LONG);
                XposedBridge.log(TAG + ": blocked freeze of " + pkg + " (" + block + ")");
                return;
            }

            int s = readEnabledStateFor(activity, pm, pkg);
            boolean wasFrozen = isFrozen(s);
            boolean wantFreeze = !wasFrozen;          // intent, not enum value
            int next = wantFreeze
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

            pmsSetEnabled(activity, pkg, next, targetUserFor(activity));

            // Read-back is the ONLY source of truth: setApplicationEnabledSetting() can silently
            // no-op when the caller lacks the signature permission, so we must verify the actual
            // state instead of assuming the call succeeded.
            int newState = readEnabledStateFor(activity, pm, pkg);
            boolean nowFrozen = isFrozen(newState);
            boolean ok = (nowFrozen == wantFreeze);

            invalidateFrozenCount();
            if (nowFrozen) markFrozenAt(pkg); else clearFrozenAt(pkg);
            updateLabel(btn, newState, pkg);
            refreshSiblingListBadge(btn);
            String msg = ok
                    ? (wantFreeze
                            ? tr("已冻结 ", "Frozen ") + labelOf(pm, pkg)
                            : tr("已解冻 ", "Unfrozen ") + labelOf(pm, pkg))
                    : tr("操作未生效（可能被系统/安全软件拦截）",
                            "No effect (blocked by the system or a security app)");
            toast(activity, msg, ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG);
            XposedBridge.log(TAG + ": " + msg + " want=" + (wantFreeze ? "FREEZE" : "ENABLE")
                    + " readback=" + newState);
        } catch (SecurityException se) {
            toast(activity, tr("无权限停用该应用（需系统签名权限）",
                        "Not permitted to disable this app (needs system signature permission)"),
                    Toast.LENGTH_LONG);
            XposedBridge.log(TAG + ": SecurityException for " + pkg + ": " + se);
        } catch (Throwable t) {
            toast(activity, tr("操作失败：", "Operation failed: ") + t.getMessage(), Toast.LENGTH_LONG);
            XposedBridge.log(TAG + ": toggle failed for " + pkg + ": " + t);
        }
    }

    // ======================================================================
    // Clone / secondary-user awareness
    //
    // App clones (App Twin / 分身, OPlus MultiApp, Samsung Secure Folder) are NOT separate
    // packages: the clone shares the SAME package name but is installed for a DIFFERENT
    // Android user (observed on this device: 0 = owner, 998/999 = MultiApp clones).
    //
    // PackageManager.setApplicationEnabledSetting(pkg, state, userId) only affects the given
    // user. Passing a hard-coded 0 therefore ALWAYS targets the owner copy — which is why
    // freezing "the clone" from inside the clone's app-info page silently froze the phone's
    // original app instead.
    //
    // Fix: resolve the user id of the settings process we are running in, and use THAT.
    // ======================================================================

    /** Cached clone user for the current Settings process. */
    private static volatile int cachedProcessUser = -1;

    /**
     * Per-Activity override resolved from the app-info Intent.
     *
     * <p>Keyed by Activity identity: on OPlus MultiApp the clone's app-info page is rendered by
     * the OWNER's settings process (user 0), so {@link #currentProcessUser()} alone always
     * returns 0 and every freeze lands on the original app. The target user therefore has to
     * come from the page itself, which is exactly what this map stores.
     */
    private static final java.util.Map<Activity, Integer> ACTIVITY_USER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Activity, Integer>());

    /** The user id this Activity's freeze actions should target; -1 = not resolved yet. */
    private static int targetUserFor(Activity a) {
        if (a == null) return currentProcessUser();
        try {
            Integer v = ACTIVITY_USER.get(a);
            if (v != null) return v;
        } catch (Throwable ignored) {
        }
        return currentProcessUser();
    }

    private static void setTargetUserFor(Activity a, int user) {
        try {
            if (a != null) ACTIVITY_USER.put(a, user);
        } catch (Throwable ignored) {
        }
    }

    /** Discovers which Android user the app-info page is describing. */
    private static void resolveTargetUser(Activity activity, String pkg) {
        if (activity == null) return;
        int found = -1;
        String how = "none";

        // 1) Explicit user id in the Intent (some OEM builds pass EXTRA_USER_ID).
        try {
            Intent i = activity.getIntent();
            if (i != null) {
                android.os.Bundle b = i.getExtras();
                if (b != null) {
                    for (String key : new String[]{
                            "android.intent.extra.USER_ID", "user_id", "userId",
                            "user_handle", "android.intent.extra.USER"}) {
                        Object v = b.get(key);
                        if (v instanceof Integer) {
                            int u = (Integer) v;
                            if (u > 0) { found = u; how = "extra:" + key; break; }
                        } else if (v instanceof android.os.UserHandle) {
                            int u = userHandleToId((android.os.UserHandle) v);
                            if (u > 0) { found = u; how = "extra:" + key; break; }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) Declared user-serial field on the Activity (OEM shells often keep one).
        if (found < 0) {
            try {
                for (Class<?> c = activity.getClass(); c != null && c != Activity.class;
                     c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        String n = f.getName().toLowerCase();
                        if (!n.contains("user") || n.contains("userid") || n.contains("userhandle")
                                || n.contains("usermgr") || n.contains("usercache")) {
                            continue;
                        }
                        f.setAccessible(true);
                        Object v = f.get(activity);
                        int u = -1;
                        if (v instanceof Integer) {
                            u = (Integer) v;
                        } else if (v instanceof android.os.UserHandle) {
                            u = userHandleToId((android.os.UserHandle) v);
                        }
                        if (u > 0) { found = u; how = "field:" + c.getSimpleName() + "." + f.getName(); break; }
                    }
                    if (found > 0) break;
                }
            } catch (Throwable ignored) {
            }
        }

        // 3) The current process may itself be a clone user after all.
        if (found < 0) {
            int u = currentProcessUser();
            if (u > 0) { found = u; how = "process"; }
        }

        if (found >= 0) {
            setTargetUserFor(activity, found);
            XposedBridge.log(TAG + ": target user for " + pkg + " = " + found + " (" + how + ")");
        } else {
            XposedBridge.log(TAG + ": target user for " + pkg
                    + " = 0 (owner) — no clone marker found in this page");
        }
    }

    private static int userHandleToId(android.os.UserHandle uh) {
        try {
            java.lang.reflect.Method m =
                    android.os.UserHandle.class.getMethod("getIdentifier");
            Object v = m.invoke(uh);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * @return the Android user id owning the current process, or 0 when unknown.
     * Never throws; falls back to the owner user so behaviour stays predictable.
     */
    private static int currentProcessUser() {
        int c = cachedProcessUser;
        if (c >= 0) return c;
        int resolved = 0;
        try {
            // android.os.UserHandle.myUserId() is not in the public android.jar stub, and this
            // module compiles against the stub only, so reach it reflectively.
            Class<?> uh = Class.forName("android.os.UserHandle");
            java.lang.reflect.Method m = uh.getMethod("myUserId");
            Object v = m.invoke(null);
            if (v instanceof Integer) resolved = (Integer) v;
        } catch (Throwable t) {
            resolved = 0;
        }
        cachedProcessUser = resolved;
        return resolved;
    }

    /** @return true when actions should target a secondary (clone) user. */
    private static boolean isCloneUser() {
        return currentProcessUser() != 0;
    }

    /**
     * Returns a {@link PackageManager} bound to {@code userId}, or {@code null} if one cannot be
     * created.
     *
     * <p>WHY THIS EXISTS — the clone-freeze bug:
     *
     * <p>The public {@code PackageManager} read/write APIs take <b>no user argument</b>. They
     * always act on the user that owns the {@code Context} behind the manager. The public
     * {@code setApplicationEnabledSetting} overload is
     * {@code (String packageName, int newState, int flags)} — so the "obvious" cross-user call
     *
     * <pre>  pm.setApplicationEnabledSetting(pkg, state, cloneUserId)   // WRONG</pre>
     *
     * compiles cleanly, silently passes the user id in the <b>flags</b> slot, and still targets
     * the caller's user. The result is the worst possible outcome: the tap reports a failure on
     * the clone while the <b>owner's</b> copy of the app is disabled.
     *
     * <p>There is also no public {@code getApplicationEnabledSetting(String, int)}; the
     * user-aware reader lives on {@code IPackageManager}, whose signature drifts between
     * releases. Creating a per-user {@code Context} is the stable route: its manager resolves the
     * user internally, so the plain public APIs then do the right thing.
     *
     * <p>Returns {@code null} — never a caller-scoped fallback — when {@code userId} is non-zero
     * and the context cannot be created, so callers are forced to abort instead of writing to the
     * wrong user.
     */
    private static PackageManager pmForUser(Context ctx, int userId) {
        if (ctx == null) return null;
        if (userId == 0 || userId == currentProcessUser()) {
            try {
                return ctx.getPackageManager();
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            Class<?> uhClass = Class.forName("android.os.UserHandle");
            Object uh = uhClass.getMethod("of", int.class).invoke(null, userId);
            java.lang.reflect.Method m = Context.class.getMethod(
                    "createPackageContextAsUser", String.class, int.class, uhClass);
            Context userCtx = (Context) m.invoke(ctx, ctx.getPackageName(), 0, uh);
            if (userCtx != null) {
                PackageManager userPm = userCtx.getPackageManager();
                if (userPm != null) return userPm;
            }
        } catch (Throwable t) {
            logOnce("pmForUser(" + userId + ") failed: " + t);
        }
        return null;
    }

    /**
     * Reads the enabled state of {@code pkg} for a specific user.
     *
     * <p>Uses a user-scoped {@link PackageManager} so the plain public reader returns that user's
     * state; see {@link #pmForUser} for why the user must come from the context.
     */
    private static int enabledStateForUser(Context ctx, PackageManager fallbackPm,
                                           String pkg, int userId) {
        PackageManager pm = pmForUser(ctx, userId);
        if (pm == null && userId == 0) pm = fallbackPm;     // owner user: any manager will do
        if (pm == null) {
            // Non-owner user without a user-scoped manager: report DEFAULT rather than the
            // owner's state, which would make the button lie about the clone.
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
        try {
            return pm.getApplicationEnabledSetting(pkg);
        } catch (Throwable t) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    /** User-aware replacement for the old state reader. */
    private int readEnabledState(Context ctx, PackageManager pm, String pkg) {
        return enabledStateForUser(ctx, pm, pkg, currentProcessUser());
    }

    /** User-aware read bound to a specific page (clone-aware). */
    private int readEnabledStateFor(Activity a, PackageManager pm, String pkg) {
        return enabledStateForUser(a, pm, pkg, targetUserFor(a));
    }

    /**
     * setApplicationEnabledSetting is a one-arg-hidden-API on some releases; go through
     * reflection so a signature change on Android 16 cannot crash the tap handler.
     *
     * <p>{@code userId} is what makes clone-scoped freezing possible — passing 0 (the previous
     * hard-coded value) would always target the owner's copy.
     */
    private void pmsSetEnabled(Context ctx, String pkg, int state, int userId) {
        PackageManager pm = pmForUser(ctx, userId);
        if (pm == null) {
            // Refuse to continue. Falling back to the caller-scoped manager here is what made
            // "freeze the clone" disable the OWNER's app; a hard failure is always better than
            // mutating the wrong user.
            throw new IllegalStateException(
                    "no PackageManager for user " + userId + "; refusing to freeze " + pkg);
        }
        // Third argument is FLAGS (not a user id) — the user comes from the context behind
        // `pm`, which pmForUser() has already bound to `userId`.
        pm.setApplicationEnabledSetting(pkg, state, 0);
    }

    private int safeEnabledState(Context ctx, PackageManager pm, String pkg) {
        return readEnabledState(ctx, pm, pkg);
    }


    // ------------------------------------------------------------------
    // Safety + diagnostics
    // ------------------------------------------------------------------

    /** @return human-readable reason if the package must not be frozen, else null. */
    private String criticalReason(Activity activity, PackageManager pm, String pkg) {
        if (CRITICAL_PKGS.contains(pkg)) return tr("系统关键包 ", "Critical system package ") + pkg;

        // Current launcher(s) — freezing the launcher would leave the user with no home.
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = pm.queryIntentActivities(home, 0);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                    return tr("桌面启动器", "current launcher");
                }
            }
        } catch (Throwable ignored) {
        }

        // Enabled input methods — freezing the IME breaks text input everywhere.
        try {
            InputMethodManager imm =
                    (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<android.view.inputmethod.InputMethodInfo> list = imm.getEnabledInputMethodList();
                if (list != null) {
                    for (android.view.inputmethod.InputMethodInfo info : list) {
                        if (info != null && pkg.equals(info.getPackageName())) {
                            return tr("当前输入法", "active input method");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Never freeze something currently on screen in the foreground.
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
            if (am != null) {
                // NOTE: the android.jar stub returns a raw List, so iterate by index
                // (a for-each fails to compile against the public stub).
                java.util.List procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (int i = 0; i < procs.size(); i++) {
                        android.app.ActivityManager.RunningAppProcessInfo p =
                                (android.app.ActivityManager.RunningAppProcessInfo) procs.get(i);
                        if (p != null
                                && p.importance
                                == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                && pkg.equals(p.processName)) {
                            return tr("前台运行中", "running in the foreground");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void showDiagnostics(Activity activity, String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            int s = safeEnabledState(activity, pm, pkg);
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            StringBuilder sb = new StringBuilder();
            sb.append(pkg).append("\n");
            sb.append(tr("状态: ", "State: ")).append(stateToName(s)).append("\n");
            sb.append(tr("系统应用: ", "System app: "))
                    .append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0).append("\n");
            String block = criticalReason(activity, pm, pkg);
            sb.append(tr("保护: ", "Protection: "))
                    .append(block == null ? tr("无（可冻结）", "none (freezable)") : block);
            XposedBridge.log(TAG + ": DIAG\n" + sb);
            toast(activity, sb.toString(), Toast.LENGTH_LONG);
        } catch (Throwable t) {
            toast(activity, tr("诊断失败：", "Diagnostics failed: ") + t.getMessage(), Toast.LENGTH_LONG);
        }
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void updateLabel(TextView btn, int state, String pkg) {
        boolean frozen = isFrozen(state);
        String block = null;
        try {
            // The button context is the Activity we injected into, but never assume it: a
            // ContextThemeWrapper (dialogs use one) would make a blind cast throw.
            Context ctx = btn.getContext();
            if (ctx instanceof Activity) {
                block = criticalReason((Activity) ctx, ctx.getPackageManager(), pkg);
            } else if (ctx instanceof ContextWrapper
                    && ((ContextWrapper) ctx).getBaseContext() instanceof Activity) {
                Activity a = (Activity) ((ContextWrapper) ctx).getBaseContext();
                block = criticalReason(a, a.getPackageManager(), pkg);
            }
        } catch (Throwable ignored) {
        }

        // Text carries the state, so the UI never depends on colour alone (WCAG 1.4.1).
        // In a clone user the label is prefixed so the user can never mistake the clone's
        // button for the owner app's one.
        String label = frozen
                    ? tr("❄ 已冻结 · 点此解冻", "❄ Frozen · tap to unfreeze")
                    : tr("❄ 冻结此应用", "❄ Freeze this app");
        if (targetUserFor((Activity) (btn.getContext() instanceof Activity
                ? btn.getContext() : null)) != 0) {
            label = frozen
                        ? tr("[分身] 已冻结 · 点此解冻", "[Clone] ❄ Frozen · tap to unfreeze")
                        : tr("[分身] 冻结此应用", "[Clone] ❄ Freeze this app");
        }
        btn.setText(label);
        applyPill(btn, frozen);
        btn.setAlpha(block != null ? 0.55f : 1f);

        // Screen readers must hear both the action and the state, plus any block reason.
        Activity act = (btn.getContext() instanceof Activity) ? (Activity) btn.getContext() : null;
        String scope = (act != null && targetUserFor(act) != 0)
                    ? tr("分身应用，", "clone app, ") : "";
        String desc;
        if (block != null) {
            desc = scope
                        + (frozen ? tr("已冻结，点此解冻", "frozen, tap to unfreeze")
                                  : tr("冻结此应用", "freeze this app"))
                        + tr("。当前不可冻结：", ". Currently cannot freeze: ") + block;
        } else {
            desc = scope
                        + (frozen ? tr("已冻结，点此解冻", "frozen, tap to unfreeze")
                                  : tr("冻结此应用", "freeze this app"));
        }
        btn.setContentDescription(desc);
    }

    /**
     * Rounded pill background that darkens while pressed. Frozen pills get a lighter tint so
     * the state is readable at a glance without relying on the label alone.
     */
    /**
     * Window-wide de-duplication for the injected bar.
     *
     * <p>Walks the whole DecorView instead of a single container, because the two injection
     * paths use different parents. Returns true when a bar (or its freeze button) is already
     * attached anywhere in this window.
     */
    private static boolean isBarAlreadyInWindow(Activity activity, ViewGroup proposedParent) {
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) decor;
                if (root.findViewWithTag(FROZEN_BAR_TAG) != null) return true;
                if (root.findViewWithTag(FROZEN_TAG) != null) return true;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: also inspect the parent we are about to use.
        try {
            if (proposedParent != null) {
                if (proposedParent.findViewWithTag(FROZEN_BAR_TAG) != null) return true;
                if (proposedParent.findViewWithTag(FROZEN_TAG) != null) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Last-resort guard: the Activity that currently hosts the bar.
     *
     * <p>The two injection paths use different containers, so in the rare case where a stale
     * view is detached but its parent is still reachable through neither DecorView nor the
     * proposed parent, this identity check still prevents a second bar. Keyed by Activity and
     * cleared in {@link #hookActivityDestroy()}.
     */
    private static final java.util.Set<Activity> BAR_HOSTS =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Activity, Boolean>());

    private static boolean isBarHost(Activity a) {
        try {
            synchronized (BAR_HOSTS) {
                return BAR_HOSTS.contains(a);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markBarHost(Activity a) {
        try {
            synchronized (BAR_HOSTS) {
                BAR_HOSTS.add(a);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearBarHost(Activity a) {
        try {
            synchronized (BAR_HOSTS) {
                BAR_HOSTS.remove(a);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Rounded pill background with pressed AND focused states.
     *
     * <p>Colour alone never encodes the state (the label text does too), and the focus ring
     * keeps the control visible when navigating with a keyboard/trackball or switch device,
     * which OEM themes would otherwise strip from a plain TextView.
     */
    private static void applyPill(TextView btn, boolean frozen) {
        int idle = frozen ? ACCENT_FROZEN : ACCENT;
        int pressed = frozen ? ACCENT_FROZEN_PRESSED : ACCENT_PRESSED;
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, ring(pressed));
        bg.addState(new int[]{android.R.attr.state_pressed}, solidWith(pressed));
        bg.addState(new int[]{}, solidWith(idle));
        btn.setBackground(bg);
        btn.setFocusable(true);
        btn.setClickable(true);
    }

    /** Pill with a visible focus ring so keyboard navigation stays legible. */
    private static android.graphics.drawable.Drawable ring(int color) {
        try {
            GradientDrawable outer = new GradientDrawable();
            outer.setShape(GradientDrawable.RECTANGLE);
            outer.setCornerRadius(999);
            outer.setColor(0x00000000);
            outer.setStroke(6, color);

            GradientDrawable inner = new GradientDrawable();
            inner.setShape(GradientDrawable.RECTANGLE);
            inner.setCornerRadius(999);
            inner.setColor(color);

            return new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{outer, inner});
        } catch (Throwable t) {
            return solidWith(color);
        }
    }

    private static android.graphics.drawable.Drawable solidWith(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(999);
        g.setColor(color);
        return g;
    }

    private static int dp(Activity a, int value) {
        try {
            float d = a.getResources().getDisplayMetrics().density;
            return Math.round(value * d);
        } catch (Throwable t) {
            return Math.round(value * 3f);   // sensible fallback for a 3x display
        }
    }

    private static void toast(Activity a, String msg, int len) {
        try {
            Toast.makeText(a, msg, len).show();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFrozen(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    /** Refresh the frozen-list counter sitting next to the freeze button, if present. */
    private static void refreshSiblingListBadge(TextView btn) {
        try {
            View bar = (View) btn.getParent();
            if (bar == null) return;
            View badge = bar.findViewWithTag(FROZEN_LIST_TAG);
            if (badge instanceof TextView) refreshListBadge((TextView) badge);
        } catch (Throwable ignored) {
        }
    }

    private static String stateToName(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: return "ENABLED";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER: return "DISABLED_USER";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED: return "DISABLED";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "DISABLED_UNTIL_USED";
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT: return "DEFAULT";
            default: return "UNKNOWN(" + state + ")";
        }
    }
}
