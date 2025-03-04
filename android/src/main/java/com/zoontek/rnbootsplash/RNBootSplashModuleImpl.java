package com.zoontek.rnbootsplash;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.window.SplashScreen;
import android.window.SplashScreenView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.uimanager.PixelUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class RNBootSplashModuleImpl {

  public static final String NAME = "RNBootSplash";

  private static final RNBootSplashQueue<Promise> mPromiseQueue = new RNBootSplashQueue<>();
  private static boolean mShouldKeepOnScreen = false;

  @StyleRes
  private static int mThemeResId = -1;

  @Nullable
  private static RNBootSplashDialog mInitialDialog = null;
  @Nullable
  private static RNBootSplashDialog mFadeOutDialog = null;

  protected static void init(@Nullable final Activity activity, @StyleRes int themeResId) {
    if (mThemeResId != -1) {
      FLog.w(ReactConstants.TAG, NAME + ": Ignored initialization, module is already initialized.");
      return;
    }

    mThemeResId = themeResId;

    if (activity == null) {
      FLog.w(ReactConstants.TAG, NAME + ": Ignored initialization, current activity is null.");
      return;
    }

    // Apply postBootSplashTheme
    TypedValue typedValue = new TypedValue();
    Resources.Theme currentTheme = activity.getTheme();

    if (currentTheme
      .resolveAttribute(R.attr.postBootSplashTheme, typedValue, true)) {
      int finalThemeId = typedValue.resourceId;

      if (finalThemeId != 0) {
        activity.setTheme(finalThemeId);
      }
    }

    // Keep the splash screen on-screen until Dialog is shown
    final View contentView = activity.findViewById(android.R.id.content);
    mShouldKeepOnScreen = true;

    contentView
      .getViewTreeObserver()
      .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
      @Override
      public boolean onPreDraw() {
        if (mShouldKeepOnScreen) {
          return false;
        }

        contentView
          .getViewTreeObserver()
          .removeOnPreDrawListener(this);

        return true;
      }
    });

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // This is not called on Android 12 when activity is started using intent
      // (Android studio / CLI / notification / widget…)
      SplashScreen.OnExitAnimationListener listener = new SplashScreen.OnExitAnimationListener() {
        @Override
        public void onSplashScreenExit(@NonNull SplashScreenView view) {
          view.remove(); // Remove it immediately, without animation

          activity
            .getSplashScreen()
            .clearOnExitAnimationListener();
        }
      };

      activity
        .getSplashScreen()
        .setOnExitAnimationListener(listener);
    }

    mInitialDialog = new RNBootSplashDialog(activity, mThemeResId, false);

    mInitialDialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override
      public void onShow(DialogInterface dialog) {
        mShouldKeepOnScreen = false;
      }
    });

    UiThreadUtil.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        mInitialDialog.show();
      }
    });
  }

  private static void clearPromiseQueue() {
    while (!mPromiseQueue.isEmpty()) {
      Promise promise = mPromiseQueue.shift();

      if (promise != null) {
        promise.resolve(true);
      }
    }
  }

  private static void hideAndClearPromiseQueue(final ReactApplicationContext reactContext,
                                               final boolean fade) {
    UiThreadUtil.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        final Activity activity = reactContext.getCurrentActivity();

        if (mShouldKeepOnScreen || activity == null || activity.isFinishing()) {
          final Timer timer = new Timer();

          timer.schedule(new TimerTask() {
            @Override
            public void run() {
              timer.cancel();
              hideAndClearPromiseQueue(reactContext, fade);
            }
          }, 100);

          return;
        }

        if (mInitialDialog == null && mFadeOutDialog == null) {
          clearPromiseQueue();
          return;
        }

        if (mFadeOutDialog != null) {
          return; // wait until fade out end, as it will run clearPromiseQueue
        }

        if (fade) {
          // Create a new Dialog instance with fade out animation
          mFadeOutDialog = new RNBootSplashDialog(activity, mThemeResId, true);

          mFadeOutDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
              mInitialDialog.dismiss();
            }
          });

          mInitialDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
              mInitialDialog = null;
              mFadeOutDialog.dismiss();
            }
          });

          mFadeOutDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
              mFadeOutDialog = null;
              clearPromiseQueue();
            }
          });

          mFadeOutDialog.show();
        } else {
          mInitialDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
              mInitialDialog = null;
              clearPromiseQueue();
            }
          });

          mInitialDialog.dismiss();
        }
      }
    });
  }

  public static Map<String, Object> getConstants(final ReactApplicationContext reactContext) {
    final Resources resources = reactContext.getResources();
    HashMap<String, Object> constants = new HashMap<>();

    @SuppressLint({"InternalInsetResource", "DiscouragedApi"}) final int statusBarHeightResId =
      resources.getIdentifier("status_bar_height", "dimen", "android");

    @SuppressLint({"InternalInsetResource", "DiscouragedApi"}) final int navigationBarHeightResId =
      resources.getIdentifier("navigation_bar_height", "dimen", "android");

    float statusBarHeight = statusBarHeightResId > 0
      ? PixelUtil.toDIPFromPixel(resources.getDimensionPixelSize(statusBarHeightResId))
      : 0;

    float navigationBarHeight = navigationBarHeightResId > 0 && !ViewConfiguration.get(reactContext).hasPermanentMenuKey()
      ? PixelUtil.toDIPFromPixel(resources.getDimensionPixelSize(navigationBarHeightResId))
      : 0;

    constants.put("statusBarHeight", statusBarHeight);
    constants.put("navigationBarHeight", navigationBarHeight);

    return constants;
  }

  public static void hide(final ReactApplicationContext reactContext,
                          final boolean fade,
                          final Promise promise) {
    mPromiseQueue.push(promise);
    hideAndClearPromiseQueue(reactContext, fade);
  }

  public static void isVisible(final Promise promise) {
    promise.resolve(mShouldKeepOnScreen || mInitialDialog != null || mFadeOutDialog != null);
  }
}
