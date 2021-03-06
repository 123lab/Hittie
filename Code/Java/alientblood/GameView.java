// Copyright 2008 and onwards Matthew Burkhart.
//
// This program is free software; you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation; version 3 of the License.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.

package android.com.abb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import java.lang.Math;
import java.lang.System;
import java.lang.Thread;
import junit.framework.Assert;


public class GameView extends SurfaceView implements SurfaceHolder.Callback {
  class GameThread extends Thread {
    public GameThread(Context context,
                      Handler handler,
                      SurfaceHolder surface_holder) {
      mContext = context;
      mHandler = handler;
      mPaused = false;
      mRunning = true;
      mSurfaceHolder = surface_holder;
    }

    @Override
    public void run() {
      Log.d("GameThread::run", "Starting game thread...");
      synchronized (this) {
        while (mGame == null && mRunning) {
          try {
            wait();  // Sleep thread until notification.
          } catch (java.lang.InterruptedException ex) {
            continue;
          }
        }

        Assert.assertEquals(
            "GameView thread must only be run once.", mGraphics, null);
        mGraphics = new Graphics();
        mGraphics.initialize(mSurfaceHolder);
        mGame.initializeGraphics(mGraphics);
      }

      // Since our target platform is a mobile device, we should do what we can
      // to save power. In the case of a game like this, we should 1) limit the
      // frame rate to something "reasonable" and 2) pause the updates as much
      // as possible. Here we define the maximum frame rate which needs to make
      // the trade off between graphics fluidity and power savings.
      final float kMaxFrameRate = 30.0f;  // Frames / second.
      final float kMinFrameRate = 6.0f;   // Frames / second.
      final float kMinTimeStep = 1.0f / kMaxFrameRate;  // Seconds.
      final float kMaxTimeStep = 1.0f / kMinFrameRate;  // Seconds.

      // The timers available through the Java APIs appear sketchy in general.
      // The following resource was useful:
      // http://blogs.sun.com/dholmes/entry/inside_the_hotspot_vm_clocks
      long time = System.nanoTime();

      //int frame = 0;
      while (mRunning) {
        synchronized (this) {
          while (mPaused && mRunning) {
            try {
              wait();  // Sleep thread until notification.
            } catch (java.lang.InterruptedException ex) {
              continue;
            }
          }
        }

        // Calculate the interval between this and the previous frame. See note
        // above regarding system timers. If we have exceeded our frame rate
        // budget, sleep.
        long current_time = System.nanoTime();
        float time_step = (float)(current_time - time) * 1.0e-9f;
        time = current_time;

        /*
        ++frame;
        if (frame % 30 == 0) {
          Log.d("GameThread::run",
                "Frame rate: " + (int)(1.0f / time_step));
        }
        */

        if (false && time_step < kMinTimeStep) {
          float remaining_time = kMinTimeStep - time_step;
          time_step = kMinTimeStep;
          try {
            long sleep_milliseconds = (long)(remaining_time * 1.0e3f);
            remaining_time -= sleep_milliseconds * 1.0e-3f;
            int sleep_nanoseconds = (int)(remaining_time * 1.0e9f);
            Thread.sleep(sleep_milliseconds, sleep_nanoseconds);
          } catch (InterruptedException ex) {
            // If someone has notified this thread, just forget about it and
            // continue on. It's not worth the cycles to handle.
          }
        } else {
          // In the case where the thread took too long, consider letting the
          // thread yield to other processes. This should usually only happen in
          // the case something "big" is happening and we don't need / want to
          // starve the more important system threads.
          // yield();
        }
        time_step = Math.max(time_step, kMinTimeStep);
        time_step = Math.min(time_step, kMaxTimeStep);

        try {
          synchronized (this) {
            if (mGraphics.beginFrame()) {
              mGame.onFrame(mGraphics, time_step);
            } else {
              mHandler.sendMessage(mHandler.obtainMessage(kKillMessage));
            }
          }
        } finally {
          mGraphics.endFrame();
        }
        hideLoadingDialog();

        // Display and block on any notifications from the game thread. While
        // the notification dialog is visible, the game continues to run.
        String notification = mGame.getPendingNotification();
        if (notification != null) {
          mHandler.sendMessage(
              mHandler.obtainMessage(kNotificationMessage, notification));
        }
      }
      Log.d("GameThread::run", "Freeing graphics resources...");
      mGraphics.destroy();
      Log.d("GameThread::run", "Finished game thread.");
    }

    synchronized public void setGame(Game game) {
      mGame = game;
      notifyAll();
    }

    synchronized public void pause(boolean pause) {
      mPaused = pause;
      notifyAll();
    }

    synchronized public void surfaceChanged(SurfaceHolder surface_holder,
                                            int width, int height) {
      if (mGraphics != null) {
        mGraphics.surfaceChanged(surface_holder, width, height);
      }
    }

    synchronized public void halt() {
      mRunning = false;
      notifyAll();
    }

    private Context mContext;
    private Game mGame;
    private Graphics mGraphics;
    private Handler mHandler;
    private boolean mPaused;
    private boolean mRunning;
    private SurfaceHolder mSurfaceHolder;
  }  // class GameThread

  public GameView(Context context, AttributeSet attrs) {
    super(context, attrs);
    mContext = context;
    getHolder().addCallback(this);
    getHolder().setType(SurfaceHolder.SURFACE_TYPE_GPU);

    /** Handle custom events and notifications generated from the game
     * thread. */
    mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
          Log.d("GameView::Handler::handleMessage",
                "Received message: " + msg.what);
          if (msg.what == kNotificationMessage) {
            Builder dialog = new AlertDialog.Builder(mContext);
            dialog.setMessage(((String)msg.obj) + " (Press back to continue.)");
            dialog.show();
          } else if (msg.what == kKillMessage) {
            Activity current_activity = (Activity)mContext;
            current_activity.finish();
          }
        }
    };
  }

  public void setGame(Game game) {
    mGame = game;
    if (mGameThread != null) {
      mGameThread.setGame(game);
    }
  }

  /** Standard override to get key-press events. */
  @Override
  public boolean onKeyDown(int key_code, KeyEvent msg) {
    /*
    if (key_code == kProfileKey) {
      if (!mProfiling) {
        Debug.startMethodTracing(kProfilePath);
        mProfiling = true;
      } else {
        Debug.stopMethodTracing();
        mProfiling = false;
      }
    }
    */

    synchronized (mGame) {
      return mGame.onKeyDown(key_code);
    }
  }

  /** Standard override for key-up. We actually care about these, so we can turn
   * off the engine or stop rotating. */
  @Override
  public boolean onKeyUp(int key_code, KeyEvent msg) {
    synchronized (mGame) {
      return mGame.onKeyUp(key_code);
    }
  }

  /** Standard window-focus override. Notice focus lost so we can pause on focus
   * lost. e.g. user switches to take a call. */
  @Override
  public void onWindowFocusChanged(boolean window_has_focus) {
    super.onWindowFocusChanged(window_has_focus);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // The mGame synchronization lock below is highly contented and therefore
    // these motion events may be *very expensive*. Because of this, we
    // fast-track ignore the ACTION_MOVE events which are never used here.
    if (event.getAction() == MotionEvent.ACTION_MOVE) {
      event.recycle();
      return true;
    }

    synchronized (mGame) {
      mGame.onMotionEvent(event);
    }

    event.recycle();
    return true;
  }

  /** Callback invoked when the Surface has been created and is ready to be
   * used. */
  public void surfaceCreated(SurfaceHolder holder) {
    Log.d("GameView::surfaceCreated", "Creating new game thread...");
    showLoadingDialog();

    // Make sure we get key events and register our interest in hearing about
    // surface changes.
    setFocusable(true);
    getHolder().addCallback(this);
    getHolder().setType(SurfaceHolder.SURFACE_TYPE_GPU);

    Assert.assertTrue(mGameThread == null);
    mGameThread = new GameThread(mContext, mHandler, holder);
    mGameThread.setGame(mGame);
    mGameThread.start();
    mGameThreadStarted = true;
  }

  /** Callback invoked when the surface dimensions change. */
  public void surfaceChanged(SurfaceHolder surface_holder, int format,
                             int width, int height) {
    mGameThread.surfaceChanged(surface_holder, width, height);
  }

  /** Callback invoked when the Surface has been destroyed and must no longer be
   * touched. */
  public void surfaceDestroyed(SurfaceHolder holder) {
    boolean retry = true;
    mGameThread.halt();
    while (retry) {
      try {
        mGameThread.join();
        mGameThread = null;
        retry = false;
      } catch (InterruptedException e) {}
    }
  }

  synchronized private void showLoadingDialog() {
    if (mLoadingDialog == null) {
      mLoadingDialog = ProgressDialog.show(
          mContext, null, "Loading...", true, false);
      mLoadingDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
    }
  }

  synchronized private void hideLoadingDialog() {
    if (mLoadingDialog != null) {
      mLoadingDialog.dismiss();
      mLoadingDialog = null;
    }
  }

  private Context        mContext;
  private Game           mGame;
  private GameThread     mGameThread;
  private boolean        mGameThreadStarted;
  private Handler        mHandler;
  private ProgressDialog mLoadingDialog;
  private boolean        mProfiling;

  private static final int    kKillMessage         = 696;
  private static final int    kNotificationMessage = 666;
  private static final int    kProfileKey          = KeyEvent.KEYCODE_T;
  private static final String kProfilePath         = "abb.trace";
}
