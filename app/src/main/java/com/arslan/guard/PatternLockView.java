package com.arslan.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Herhangi bir harici kütüphane kullanmadan, tamamen Canvas ile çizilen
 * 3x3 desen kilidi View'ı. APK boyutunu şişirmemek için AndroidX
 * "patternlockview" gibi hazır kütüphaneler yerine bu minimal
 * implementasyon tercih edilmiştir.
 *
 * Stealth (Anti-Peeping) modu açıkken çizgiler ve dokunulan noktalar
 * EKRANDA GÖSTERİLMEZ; dokunuş algılama mantığı aynen çalışmaya devam eder.
 */
public class PatternLockView extends View {

    public interface PatternListener {
        /** Kullanıcı parmağını kaldırdığında (en az 4 nokta seçilmişse) çağrılır. */
        void onPatternComplete(String patternCode);
    }

    private static final int GRID_SIZE = 3; // 3x3
    private static final int MIN_DOTS = 4;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] dotXs = new float[GRID_SIZE * GRID_SIZE];
    private final float[] dotYs = new float[GRID_SIZE * GRID_SIZE];
    private float dotRadius;
    private float touchRadius;

    private final List<Integer> selectedDots = new ArrayList<>();
    private float currentX, currentY;
    private boolean tracking = false;

    private boolean stealthMode = false;
    private PatternListener listener;

    public PatternLockView(Context context) {
        super(context);
        init();
    }

    public PatternLockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        dotPaint.setColor(Color.parseColor("#3A3A3A"));
        dotPaint.setStyle(Paint.Style.FILL);

        dotSelectedPaint.setColor(Color.parseColor("#00E676")); // accent renk
        dotSelectedPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(Color.parseColor("#00E676"));
        linePaint.setStrokeWidth(8f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        setClickable(true);
        setFocusable(true);
    }

    public void setStealthMode(boolean stealthMode) {
        this.stealthMode = stealthMode;
    }

    public void setPatternListener(PatternListener listener) {
        this.listener = listener;
    }

    /** Kullanıcı yeniden denemek için ekranı temizlemek istediğinde çağrılır. */
    public void reset() {
        selectedDots.clear();
        tracking = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cellW = w / (float) GRID_SIZE;
        float cellH = h / (float) GRID_SIZE;
        dotRadius = Math.min(cellW, cellH) * 0.12f;
        touchRadius = Math.min(cellW, cellH) * 0.35f;

        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                int index = row * GRID_SIZE + col;
                dotXs[index] = cellW * col + cellW / 2f;
                dotYs[index] = cellH * row + cellH / 2f;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (stealthMode) {
            // Anti-peeping: hiçbir görsel ipucu çizilmez, sadece dokunuş algılanır
            return;
        }

        // Seçili noktalar arası çizgiler
        for (int i = 0; i < selectedDots.size() - 1; i++) {
            int from = selectedDots.get(i);
            int to = selectedDots.get(i + 1);
            canvas.drawLine(dotXs[from], dotYs[from], dotXs[to], dotYs[to], linePaint);
        }
        // Son noktadan parmağın anlık konumuna doğru çizgi (sürükleme hissi)
        if (tracking && !selectedDots.isEmpty()) {
            int last = selectedDots.get(selectedDots.size() - 1);
            canvas.drawLine(dotXs[last], dotYs[last], currentX, currentY, linePaint);
        }

        // Noktalar
        for (int i = 0; i < dotXs.length; i++) {
            boolean selected = selectedDots.contains(i);
            canvas.drawCircle(dotXs[i], dotYs[i], dotRadius, selected ? dotSelectedPaint : dotPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        currentX = event.getX();
        currentY = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selectedDots.clear();
                tracking = true;
                checkDotHit(currentX, currentY);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    checkDotHit(currentX, currentY);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                if (selectedDots.size() >= MIN_DOTS && listener != null) {
                    listener.onPatternComplete(buildPatternCode());
                }
                // Stealth modda kısa süre sonra otomatik temizle; normal modda
                // kullanıcı görsel geri bildirimi görsün diye çağıran taraf
                // (LockService/MainActivity) reset() çağırır.
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void checkDotHit(float x, float y) {
        for (int i = 0; i < dotXs.length; i++) {
            if (selectedDots.contains(i)) continue;
            float dx = x - dotXs[i];
            float dy = y - dotYs[i];
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance <= touchRadius) {
                selectedDots.add(i);
                break; // aynı hareket içinde birden fazla nokta eklenmesin
            }
        }
    }

    private String buildPatternCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedDots.size(); i++) {
            if (i > 0) sb.append("-");
            sb.append(selectedDots.get(i));
        }
        return sb.toString();
    }
}
