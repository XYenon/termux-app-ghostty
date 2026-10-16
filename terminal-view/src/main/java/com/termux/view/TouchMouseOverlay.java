package com.termux.view;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Magnifier;

/** Local aiming feedback and actions; none of these controls forward touch to the TUI. */
final class TouchMouseOverlay {
    private final TerminalView mView;
    private final TouchMouseDragHandler mHandler;
    private final Drawable mTarget = new Crosshair();
    private final Crosshair mMagnifiedTarget = new Crosshair();
    private final Magnifier mMagnifier;
    private ActionMode mActionMode;

    TouchMouseOverlay(TerminalView view, TouchMouseDragHandler handler) {
        mView = view;
        mHandler = handler;
        mMagnifier = new Magnifier.Builder(view)
            .setInitialZoom(2f).setOverlay(mMagnifiedTarget).build();
    }

    void update() {
        if (!mHandler.isActive()) {
            mMagnifier.dismiss();
            mView.getOverlay().remove(mTarget);
            finishToolbar();
            return;
        }
        int x = Math.round(mHandler.getX());
        int y = Math.round(mHandler.getY());
        int radius = Math.round(10 * mView.getResources().getDisplayMetrics().density);
        mTarget.setBounds(x - radius, y - radius, x + radius, y + radius);
        mView.getOverlay().add(mTarget);
        Rect visible = new Rect(0, 0, mView.getWidth(), mView.getHeight());
        mView.getLocalVisibleRect(visible);
        Rect magnifierBounds = magnifierBounds(x, y, mMagnifier.getWidth(),
            mMagnifier.getHeight(), Math.round(64 * mView.getResources().getDisplayMetrics().density),
            visible);
        if (mView.getHolder().getSurface().isValid()) {
            mMagnifier.show(x, y, magnifierBounds.exactCenterX(), magnifierBounds.exactCenterY());
            // The sampled region is clamped at surface edges; its center is not always the target.
            Point source = mMagnifier.getSourcePosition();
            if (source != null) {
                mMagnifiedTarget.mX = (x - source.x) * mMagnifier.getZoom();
                mMagnifiedTarget.mY = (y - source.y) * mMagnifier.getZoom();
                mMagnifiedTarget.invalidateSelf();
            }
        }

        if (!mHandler.isReady()) {
            finishToolbar();
        } else if (mActionMode == null) {
            mView.announceForAccessibility(mView.getContext().getString(R.string.mouse_aim_ready));
            mActionMode = mView.startActionMode(new ActionMode.Callback2() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    ClipboardManager clipboard = (ClipboardManager) mView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    menu.add(Menu.NONE, 1, 0, R.string.mouse_aim_click)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    menu.add(Menu.NONE, 4, 1, R.string.paste_text)
                        .setEnabled(clipboard != null && clipboard.hasPrimaryClip())
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    menu.add(Menu.NONE, 2, 2, R.string.text_selection_more)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    menu.add(Menu.NONE, 3, 3, android.R.string.cancel)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    if (item.getItemId() == 1) mHandler.click();
                    else mHandler.cancel();
                    update();
                    if (item.getItemId() == 4) mView.mTermSession.onPasteTextFromClipboard();
                    if (item.getItemId() == 2) mView.showContextMenu();
                    return true;
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    if (mActionMode == mode) {
                        mActionMode = null;
                        mHandler.cancel();
                        update();
                    }
                }

                @Override
                public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                    // Reserve the actual placement, including when the magnifier is below the target.
                    outRect.set(magnifierBounds);
                    outRect.union(x - radius, y - radius, x + radius, y + radius);
                }
            }, ActionMode.TYPE_FLOATING);
        }
    }

    static Rect magnifierBounds(int x, int y, int width, int height, int gap, Rect visible) {
        int left = Math.max(visible.left, Math.min(visible.right - width, x - width / 2));
        int top = y - gap - height;
        if (top < visible.top) top = y + gap;
        top = Math.max(visible.top, Math.min(visible.bottom - height, top));
        return new Rect(left, top, left + width, top + height);
    }

    private void finishToolbar() {
        if (mActionMode == null) return;
        ActionMode mode = mActionMode;
        mActionMode = null;
        mode.finish();
    }

    private static final class Crosshair extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mX = Float.NaN, mY = Float.NaN;

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float x = Float.isNaN(mX) ? bounds.exactCenterX() : mX;
            float y = Float.isNaN(mY) ? bounds.exactCenterY() : mY;
            float radius = Math.min(bounds.width(), bounds.height()) / 4f;
            mPaint.setColor(Color.BLACK);
            mPaint.setStrokeWidth(4);
            drawCross(canvas, x, y, radius);
            mPaint.setColor(Color.WHITE);
            mPaint.setStrokeWidth(2);
            drawCross(canvas, x, y, radius);
        }

        private void drawCross(Canvas canvas, float x, float y, float radius) {
            canvas.drawLine(x - radius, y, x + radius, y, mPaint);
            canvas.drawLine(x, y - radius, x, y + radius, mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { mPaint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
