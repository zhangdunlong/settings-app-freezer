package com.zdl.freezer;

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
    private static final String MODULE_PKG = "com.zdl.freezer";
    private static final int FROZEN_TAG = 0x5f465252; // "FRZR"
    private static final int FROZEN_BAR_TAG = 0x5f465242;  // "FRRB" - the horizontal bar
    private static final int FROZEN_LIST_TAG = 0x5f464c53; // "FRLS" - frozen-list entry button
    private static final int HEADER_TAG = 0x5f484452;      // "HRDR" - list header (count)

    private static final String ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS";

    /** Extra laid-out retry attempts after the first shot (delays grow adaptively). */
    private static final int MAX_ATTEMPTS = 20;

    private static final int ACCENT = 0xFF1AA6C4;   // teal, matches the author's theme
    private static final int ACCENT_PRESSED = 0xFF15849B;
    private static final int ACCENT_FROZEN = 0xFF3FBFA8;          // mint = currently frozen
    private static final int ACCENT_FROZEN_PRESSED = 0xFF33998A;
    private static final int LIST_BADGE = 0xFF3A6EA5;       // entry button for the frozen list

    // CRITICAL: do NOT create a static Handler here. The class is initialised during
    // Zygote.specializeAppProcess(), before the main Looper exists, so
    // `new Handler(Looper.getMainLooper())` throws NPE (Looper.mQueue on a null Looper),
    // which aborts <clinit> and makes LSPosed silently drop the whole module with
    // "Failed to load class com.zdl.freezer.FreezerModule". Resolve it lazily instead.
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
        XposedBridge.log(TAG + ": initZygote, module loaded, API=" + android.os.Build.VERSION.SDK_INT);
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
                        XposedBridge.log(TAG + ": TRACE   -> no pkg (rejected or unresolvable)");
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
                                Activity activity = (Activity) param.thisObject;
                                final String pkg = resolvePkgForLifecycle(activity);
                                if (pkg == null) return;
                                if (activity.isFinishing() || activity.isDestroyed()) return;
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
        return false;
    }

    /**
     * Content-based fallback for app-info pages hosted by a generic shell (e.g. ColorOS may use
     * {@code SubSettings} for the detail screen). The AOSP app-info page always exposes the
     * "强行停止 / Force stop" action, so the presence of that button in the visible tree is a
     * reliable signal.
     */
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
                            String s = (String) v;
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
                                String p = got.replaceAll("^.*(->|=) ", "");
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
                        String p = got.replaceAll("^.*(->|=) ", "");
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
                    if (v instanceof String && looksLikePackage((String) v)
                            && pm.getApplicationInfo((String) v, 0) != null) {
                        return (String) v;
                    }
                }
            }
            if (fr.getView() != null) {
                String got = describeViewForPackage(fr.getView(), pm);
                if (got != null) {
                    String p = got.replaceAll("^.*(->|=) ", "");
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
                    Activity activity = (Activity) param.thisObject;
                    if (!Boolean.TRUE.equals(param.args[0])) return;   // only focus GAINED
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    final String pkg = resolvePkgForLifecycle(activity);
                    if (pkg == null) return;
                    ViewGroup parent = resolveContentParent(activity);
                    if (parent != null && parent.findViewWithTag(FROZEN_TAG) != null) return;
                    XposedBridge.log(TAG + ": windowFocus gained for " + pkg
                            + " activity=" + activity.getClass().getName()
                            + " -> injecting now");
                    injectButton(activity, parent, pkg);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": focus hook error: " + t);
                }
            }
        };
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
                        s = (String) v;
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
                    if (v instanceof String && looksLikePackage((String) v)
                            && pm.getApplicationInfo((String) v, 0) != null) {
                        return "FRAGMENT " + cls + " arg[" + k + "]=" + v;
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
            if (tag instanceof String && looksLikePackage((String) tag)
                    && pm.getApplicationInfo((String) tag, 0) != null) {
                return "VIEW tag=" + tag;
            }
            if (v instanceof android.widget.TextView) {
                CharSequence t2 = ((android.widget.TextView) v).getText();
                if (t2 != null && looksLikePackage(t2.toString())
                        && pm.getApplicationInfo(t2.toString(), 0) != null) {
                    return "VIEW text=" + t2;
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
                String seg = data.getLastPathSegment();
                if (looksLikePackage(seg)) pkg = seg;
            }
            if (isNull(pkg) && looksLikePackage(data.getPath())) {
                String path = data.getPath();
                pkg = path.startsWith("/") ? path.substring(1) : path;
            }
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
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 3 || t.length() > 255) return false;
        if (t.contains("/") || t.contains(" ")) return false;
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
                        XposedBridge.log(TAG + ": activity gone before inject attempt "
                                + attempt + " for " + pkg);
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
                        XposedBridge.log(TAG + ": GAVE UP injecting button for " + pkg
                                + " after " + MAX_ATTEMPTS + " attempts");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": inject task error for " + pkg + ": " + t);
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
            final PackageManager pm = activity.getPackageManager();
            int state = safeEnabledState(pm, pkg);
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
            updateLabel(btn, state, pkg);

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
            bar.setContentDescription("冻结操作栏");

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
        t.setContentDescription("查看已冻结应用列表");
        return t;
    }

    /** Updates the list button label with the current frozen-app count. */
    private static void refreshListBadge(TextView listBtn) {
        try {
            int n = queryFrozenPackages(listBtn.getContext()).size();
            listBtn.setText(n > 0 ? ("❄ 已冻结 " + n) : "❄ 已冻结 0");
            listBtn.setAlpha(n > 0 ? 1f : 0.6f);
        } catch (Throwable t) {
            listBtn.setText("❄ 已冻结");
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
        try {
            List<ApplicationInfo> apps =
                    pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            if (apps == null) return new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (ai.packageName.equals(MODULE_PKG)) continue;
                try {
                    if (pm.getApplicationEnabledSetting(ai.packageName)
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
            header.setText("已冻结应用（" + frozen.size() + "）");
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setTextColor(0xFF5A5A5A);
            header.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 4));
            list.addView(header);

            final TextView hint = new TextView(activity);
            hint.setText("点击任意一行即可解冻");
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
                    .setPositiveButton("关闭", null)
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
            toast(activity, "打开列表失败：" + t.getMessage(), Toast.LENGTH_LONG);
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
            title.setText("暂无已冻结应用");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(0xFF1A1A1A);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(activity, 12), 0, 0);
            box.addView(title);

            TextView sub = new TextView(activity);
            sub.setText("在应用信息页点击「冻结此应用」\n被冻结的应用会出现在这里");
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            sub.setTextColor(0xFF707070);
            sub.setGravity(Gravity.CENTER);
            sub.setLineSpacing(dp(activity, 4), 1f);
            sub.setPadding(0, dp(activity, 8), 0, 0);
            box.addView(sub);

            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(box)
                    .setPositiveButton("知道了", null)
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
            toast(activity, "当前没有已冻结的应用", Toast.LENGTH_SHORT);
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
                    activity, "已解冻 " + label + "　撤销", Toast.LENGTH_LONG);
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
                }, "撤销");
            } catch (Throwable hidden) {
                XposedBridge.log(TAG + ": Toast.setAction unavailable (" + hidden
                        + "), falling back to plain toast");
            }
            t.show();
        } catch (Throwable t) {
            logOnce("toastWithUndo failed: " + t);
            toast(activity, "已解冻 " + label, Toast.LENGTH_SHORT);
        }
    }

    /** Re-freezes a package from the undo affordance. */
    private void undoFreeze(final Activity activity, final String pkg, final String label) {
        try {
            pmsSetEnabled(activity.getPackageManager(), pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
            int st = safeEnabledState(activity.getPackageManager(), pkg);
            if (isFrozen(st)) {
                markFrozenAt(pkg);
                toast(activity, "已恢复冻结 " + label, Toast.LENGTH_SHORT);
                showFrozenList(activity);
            } else {
                toast(activity, "撤销失败，系统拒绝了停用请求", Toast.LENGTH_LONG);
            }
            XposedBridge.log(TAG + ": undo freeze for " + pkg + " -> " + st);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": undo failed for " + pkg + ": " + t);
            toast(activity, "撤销失败", Toast.LENGTH_LONG);
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
        row.setContentDescription(label + "，" + pkg + "，已冻结，点击解冻");

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
            pmsSetEnabled(pm, pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

            int now = safeEnabledState(pm, pkg);
            if (isFrozen(now)) {
                if (row != null) row.setEnabled(true);
                toast(activity, "解冻失败（可能被系统拦截）", Toast.LENGTH_LONG);
                XposedBridge.log(TAG + ": unfreeze rejected for " + pkg + ", state=" + now);
                return;
            }
            clearFrozenAt(pkg);
            toastWithUndo(activity, pkg, safeAppLabel(activity, pm, pkg));
            XposedBridge.log(TAG + ": unfrozen from list: " + pkg);

            // Re-query and rebuild. The previous dialog is dismissed inside showFrozenList(),
            // so exactly one list is ever on screen.
            refresh.refresh(activity);
        } catch (Throwable t) {
            if (row != null) row.setEnabled(true);   // allow retry
            XposedBridge.log(TAG + ": unfreezeFromList failed for " + pkg + ": " + t);
            toast(activity, "解冻失败：" + t.getMessage(), Toast.LENGTH_LONG);
        }
    }

    private void unfreezeFromList(final Activity activity, final PackageManager pm,
                                         final String pkg, final LinearLayout parentList) {
        try {
            pm.setApplicationEnabledSetting(pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            int now = safeEnabledState(pm, pkg);
            if (isFrozen(now)) {
                toast(activity, "解冻失败（可能被系统拦截）", Toast.LENGTH_LONG);
                return;
            }
            clearFrozenAt(pkg);
            toastWithUndo(activity, pkg, safeAppLabel(activity, pm, pkg));
            XposedBridge.log(TAG + ": unfrozen from list: " + pkg);
            // Remove the row and refresh the header count.
            try {
                parentList.removeView((View) parentList.getParent());
            } catch (Throwable ignored) {
            }
            // Simplest robust refresh: rebuild the whole list.
            showFrozenList(activity);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unfreezeFromList failed for " + pkg + ": " + t);
            toast(activity, "解冻失败：" + t.getMessage(), Toast.LENGTH_LONG);
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
                toast(activity, "已阻止冻结：" + block, Toast.LENGTH_LONG);
                XposedBridge.log(TAG + ": blocked freeze of " + pkg + " (" + block + ")");
                return;
            }

            int s = safeEnabledState(pm, pkg);
            boolean frozen = isFrozen(s);
            int next = frozen
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;

            pmsSetEnabled(pm, pkg, next);

            int newState = safeEnabledState(pm, pkg);
            boolean ok = (next == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    ? !isFrozen(newState)
                    : isFrozen(newState);

            if (isFrozen(newState)) markFrozenAt(pkg); else clearFrozenAt(pkg);
            updateLabel(btn, newState, pkg);
            refreshSiblingListBadge(btn);
            String msg = ok
                    ? ((next == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                        ? "已解冻 " + pkg : "已冻结 " + pkg)
                    : "操作未生效（可能被系统/安全软件拦截）";
            toast(activity, msg, ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG);
            XposedBridge.log(TAG + ": " + msg + " target=" + next + " readback=" + newState);
        } catch (SecurityException se) {
            toast(activity, "无权限停用该应用（需系统签名权限）", Toast.LENGTH_LONG);
            XposedBridge.log(TAG + ": SecurityException for " + pkg + ": " + se);
        } catch (Throwable t) {
            toast(activity, "操作失败：" + t.getMessage(), Toast.LENGTH_LONG);
            XposedBridge.log(TAG + ": toggle failed for " + pkg + ": " + t);
        }
    }

    /**
     * setApplicationEnabledSetting is a one-arg-hidden-API on some releases; go through
     * reflection so a signature change on Android 16 cannot crash the tap handler.
     */
    private void pmsSetEnabled(PackageManager pm, String pkg, int state) {
        try {
            pm.setApplicationEnabledSetting(pkg, state, 0);
        } catch (NoSuchMethodError | AbstractMethodError err) {
            XposedBridge.log(TAG + ": direct setApplicationEnabledSetting missing, using reflection");
            try {
                java.lang.reflect.Method m = PackageManager.class.getMethod(
                        "setApplicationEnabledSetting", String.class, int.class, int.class);
                m.invoke(pm, pkg, state, 0);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    private int safeEnabledState(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationEnabledSetting(pkg);
        } catch (Throwable t) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    // ------------------------------------------------------------------
    // Safety + diagnostics
    // ------------------------------------------------------------------

    /** @return human-readable reason if the package must not be frozen, else null. */
    private String criticalReason(Activity activity, PackageManager pm, String pkg) {
        if (CRITICAL_PKGS.contains(pkg)) return "系统关键包 " + pkg;

        // Current launcher(s) — freezing the launcher would leave the user with no home.
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = pm.queryIntentActivities(home, 0);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                    return "桌面启动器";
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
                            return "当前输入法";
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
                            return "前台运行中";
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
            int s = safeEnabledState(pm, pkg);
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            StringBuilder sb = new StringBuilder();
            sb.append(pkg).append("\n");
            sb.append("状态: ").append(stateToName(s)).append("\n");
            sb.append("系统应用: ").append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0).append("\n");
            String block = criticalReason(activity, pm, pkg);
            sb.append("保护: ").append(block == null ? "无（可冻结）" : block);
            XposedBridge.log(TAG + ": DIAG\n" + sb);
            toast(activity, sb.toString(), Toast.LENGTH_LONG);
        } catch (Throwable t) {
            toast(activity, "诊断失败：" + t.getMessage(), Toast.LENGTH_LONG);
        }
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void updateLabel(TextView btn, int state, String pkg) {
        boolean frozen = isFrozen(state);
        String block = null;
        try {
            block = criticalReason((Activity) btn.getContext(), btn.getContext().getPackageManager(), pkg);
        } catch (Throwable ignored) {
        }
        btn.setText(frozen ? "❄ 已冻结 · 点此解冻" : "❄ 冻结此应用");
        applyPill(btn, frozen);
        btn.setAlpha(block != null ? 0.55f : 1f);
    }

    /**
     * Rounded pill background that darkens while pressed. Frozen pills get a lighter tint so
     * the state is readable at a glance without relying on the label alone.
     */
    private static void applyPill(TextView btn, boolean frozen) {
        int idle = frozen ? ACCENT_FROZEN : ACCENT;
        int pressed = frozen ? ACCENT_FROZEN_PRESSED : ACCENT_PRESSED;
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, solidWith(pressed));
        bg.addState(new int[]{}, solidWith(idle));
        btn.setBackground(bg);
    }

    private static android.graphics.drawable.Drawable solidWith(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(999);
        g.setColor(color);
        return g;
    }

    private static int dp(Activity a, int value) {
        return Math.round(a.getResources().getDisplayMetrics().density * value);
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
