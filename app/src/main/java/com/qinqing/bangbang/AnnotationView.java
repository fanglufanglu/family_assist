package com.qinqing.bangbang;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

final class AnnotationView extends View {
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x = -1f;
    private float y = -1f;
    private float radius = 0.08f;
    private String label = "请点这里";

    AnnotationView(Context context) {
        super(context);
        circlePaint.setColor(Color.rgb(220, 38, 38));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(8f);

        fillPaint.setColor(Color.argb(42, 220, 38, 38));
        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(42f);
        textPaint.setFakeBoldText(true);
        setWillNotDraw(false);
    }

    void setAnnotation(float normalizedX, float normalizedY, float normalizedRadius, String text) {
        x = normalizedX;
        y = normalizedY;
        radius = Math.max(0.04f, normalizedRadius);
        label = text == null || text.isEmpty() ? "请点这里" : text;
        invalidate();
    }

    void clear() {
        x = -1f;
        y = -1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (x < 0f || y < 0f) {
            return;
        }
        float cx = x * getWidth();
        float cy = y * getHeight();
        WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            int[] location = new int[2];
            getLocationOnScreen(location);
            cx = x * metrics.widthPixels - location[0];
            cy = y * metrics.heightPixels - location[1];
        }
        float r = radius * Math.min(getWidth(), getHeight());
        canvas.drawCircle(cx, cy, r, fillPaint);
        canvas.drawCircle(cx, cy, r, circlePaint);

        float textWidth = textPaint.measureText(label);
        float left = Math.max(20f, Math.min(cx - textWidth / 2f, getWidth() - textWidth - 20f));
        float top = Math.max(60f, cy - r - 24f);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.rgb(220, 38, 38));
        canvas.drawRoundRect(left - 20f, top - 48f, left + textWidth + 20f, top + 16f, 16f, 16f, bg);
        canvas.drawText(label, left, top, textPaint);
    }
}
