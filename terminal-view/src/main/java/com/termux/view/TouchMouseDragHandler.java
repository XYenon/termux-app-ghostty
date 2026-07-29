package com.termux.view;

import android.view.MotionEvent;

import com.termux.terminal.GhosttyTerminal;

final class TouchMouseDragHandler {

    interface MouseEventSender {
        void send(MotionEvent event, int action);
    }

    private final MouseEventSender mSender;
    private boolean mActive;

    TouchMouseDragHandler(MouseEventSender sender) {
        mSender = sender;
    }

    void start(MotionEvent event) {
        if (mActive) return;

        mActive = true;
        mSender.send(event, GhosttyTerminal.MOUSE_ACTION_PRESS);
    }

    boolean onTouchEvent(MotionEvent event) {
        if (!mActive) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mSender.send(event, GhosttyTerminal.MOUSE_ACTION_MOTION);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mSender.send(event, GhosttyTerminal.MOUSE_ACTION_RELEASE);
                mActive = false;
                return true;
            default:
                return false;
        }
    }

    boolean isActive() {
        return mActive;
    }
}
