package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.leanback.widget.PlaybackControlsRow.MultiAction;
import com.liskovsoft.smartyoutubetv2.tv.R;

public class VoiceOverTranslateAction extends MultiAction {
    public static final int INDEX_OFF = 0;
    public static final int INDEX_ON = 1;
    public static final int INDEX_WAITING = 2;

    public VoiceOverTranslateAction(Context context) {
        super(R.id.action_voice_over_translate);
        Drawable base = ContextCompat.getDrawable(context, R.drawable.action_voice_over_translate);
        if (base == null) base = ContextCompat.getDrawable(context, R.drawable.action_chat);
        Drawable off = base;
        Drawable on = null;
        Drawable waiting = null;
        if (base != null && base.getConstantState() != null) {
            Drawable m1 = base.getConstantState().newDrawable().mutate();
            Drawable w1 = DrawableCompat.wrap(m1);
            DrawableCompat.setTint(w1, ActionHelpers.getIconHighlightColor(context));
            on = w1;
            Drawable m2 = base.getConstantState().newDrawable().mutate();
            Drawable w2 = DrawableCompat.wrap(m2);
            DrawableCompat.setTint(w2, ActionHelpers.getIconHighlightColor(context));
            waiting = w2;
        } else {
            on = off;
            waiting = off;
        }
        if (waiting == null) waiting = on;
        setDrawables(new Drawable[]{off, on, waiting});
        String label = context.getString(R.string.action_voice_over_translate);
        setLabels(new String[]{label, label, label});
        setIndex(INDEX_OFF);
    }
}
