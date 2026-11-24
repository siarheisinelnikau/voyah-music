package app.voyahmusic.shared.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public final class MusicPreferences {
    private final SharedPreferences values;

    public MusicPreferences(Context context) {
        values = context.getSharedPreferences("music_settings", Context.MODE_PRIVATE);
    }

    public String language() {
        return values.getString("language", "system");
    }

    public void language(String value) {
        values.edit().putString("language", value).apply();
    }

    public boolean amber() {
        return values.getBoolean("amber", false);
    }

    public void amber(boolean enabled) {
        values.edit().putBoolean("amber", enabled).apply();
    }

    public boolean startupUpdates() {
        return values.getBoolean("startup_updates", true);
    }

    public void startupUpdates(boolean enabled) {
        values.edit().putBoolean("startup_updates", enabled).apply();
    }

    public boolean startupAnimation() {
        return values.getBoolean("startup_animation", true);
    }

    public void startupAnimation(boolean enabled) {
        values.edit().putBoolean("startup_animation", enabled).apply();
    }

    /** The player's visualization: {@code spectrum}, {@code wave} or {@code off}, as in Voyah Radio. */
    public String visualization() {
        return values.getString("visualization", "spectrum");
    }

    public void visualization(String mode) {
        values.edit().putString("visualization", mode).apply();
    }

    public boolean smoothVisualization() {
        return values.getBoolean("smooth_visualization", true);
    }

    public void smoothVisualization(boolean enabled) {
        values.edit().putBoolean("smooth_visualization", enabled).apply();
    }

    public static Context localized(Context context) {
        String language = new MusicPreferences(context).language();
        if ("system".equals(language)) return context;
        Configuration override = new Configuration();
        override.setLocales(new LocaleList(Locale.forLanguageTag(language)));
        return context.createConfigurationContext(override);
    }
}
