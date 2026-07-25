package com.arslan.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/**
 * Kilitli uygulama açıldığında gerçek PIN/Desen ekranı yerine önce
 * sistemin verdiği türden sahte bir "Uygulama durduruldu" hatası gösteren
 * overlay'i inşa eder.
 *
 * Gizli geçiş: "TAMAM" butonuna 3 saniye basılı tutulursa gerçek kilit
 * ekranına (PIN/Desen) geçilir. Kısa dokunuşta ise sahte çökme tamamlanmış
 * gibi davranılır (çağıran taraf genelde kullanıcıyı ana ekrana yönlendirir).
 */
public class FakeCrashDialog {

    public interface Callback {
        /** Kısa dokunuş: sahte çökme "tamamlandı", gerçek ekran GÖSTERİLMEZ. */
        void onDismissed();

        /** 3 saniye basılı tutuldu: gizli kapı açıldı, gerçek kilit ekranı gösterilmeli. */
        void onRevealed();
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable holdRunnable;
    private boolean revealTriggered = false;

    public FakeCrashDialog(Context context) {
        this.context = context;
    }

    /**
     * Overlay View'ını oluşturur. displayAppName ekranda "X durduruldu"
     * şeklinde gösterilecek uygulama adıdır.
     */
    public View build(CharSequence displayAppName, final Callback callback) {
        View view = LayoutInflater.from(context).inflate(R.layout.fake_crash_overlay, null);

        TextView txtAppName = view.findViewById(R.id.txtCrashAppName);
        View btnOk = view.findViewById(R.id.btnFakeCrashOk);

        if (txtAppName != null && displayAppName != null) {
            txtAppName.setText(displayAppName + " durduruldu");
        }

        btnOk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        revealTriggered = false;
                        holdRunnable = new Runnable() {
                            @Override
                            public void run() {
                                revealTriggered = true;
                                if (callback != null) callback.onRevealed();
                            }
                        };
                        handler.postDelayed(holdRunnable, PrefsHelper.getFakeCrashHoldMs());
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (holdRunnable != null) handler.removeCallbacks(holdRunnable);
                        if (!revealTriggered) {
                            v.performClick();
                            if (callback != null) callback.onDismissed();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        if (holdRunnable != null) handler.removeCallbacks(holdRunnable);
                        return true;

                    default:
                        return false;
                }
            }
        });

        return view;
    }

    /** Overlay kaldırılırken bekleyen "hold" callback'ini iptal etmek için. */
    public void cancelPendingReveal() {
        if (holdRunnable != null) {
            handler.removeCallbacks(holdRunnable);
        }
    }
}
