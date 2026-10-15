package com.cramdungeon.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.cramdungeon.CardMedia;
import com.github.luben.zstd.ZstdInputStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Takes an AnkiDroid deck export (.apkg / .colpkg with media), either shared to
 * the game or picked from storage, and copies its images and audio into the
 * game's card media. Runs as its own small dialog so sharing works whether or
 * not the game is open.
 */
public class MediaImportActivity extends Activity {

    private static final String TAG = "CramDungeon";
    private static final int PICK_FILE = 1;

    private static final int TITLE_COLOR = 0xFFFFFF44;
    private static final int TEXT_COLOR = 0xFFF0ECE0;
    private static final int PANEL_COLOR = 0xFF2B2C27;
    private static final int EDGE_COLOR = 0xFF7B8073;

    private TextView message;
    private ProgressBar bar;
    private Button ok;
    private boolean running;

    static CardMedia store(Context context) {
        return new CardMedia(new File(context.getFilesDir(), "card_media"));
    }

    /** Opens the file picker; the import dialog shows once a file is chosen. */
    static void pick(Context context) {
        Intent intent = new Intent(context, MediaImportActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        buildViews();

        if (savedInstanceState != null) {
            return;
        }
        Uri uri = sharedUri(getIntent());
        if (uri != null) {
            startImport(uri);
        } else {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            try {
                startActivityForResult(pick, PICK_FILE);
                message.setText("Choose an .apkg or .colpkg exported from AnkiDroid with media.");
            } catch (ActivityNotFoundException e) {
                finishWith("This device has no file picker. Share the export from AnkiDroid instead.");
            }
        }
    }

    private static Uri sharedUri(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return stream instanceof Uri ? (Uri) stream : null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            return intent.getData();
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        startImport(data.getData());
    }

    private void startImport(Uri uri) {
        running = true;
        ok.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        message.setText("Reading the export…");

        CardMedia media = store(this);
        new Thread(() -> {
            String text;
            try {
                CardMedia.Result r = media.importPackage(
                        () -> open(uri),
                        ZstdInputStream::new,
                        (done, total) -> runOnUiThread(() -> showProgress(done, total)));
                text = summary(r);
            } catch (Exception e) {
                Log.e(TAG, "media import failed", e);
                text = "Couldn't read this file. Share an .apkg or .colpkg export from AnkiDroid"
                        + " (Export → Include media).";
            }
            final String result = text;
            runOnUiThread(() -> finishWith(result));
        }, "media-import").start();
    }

    private InputStream open(Uri uri) throws FileNotFoundException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new FileNotFoundException(uri.toString());
        }
        return in;
    }

    private void showProgress(int done, int total) {
        if (!running) {
            return;
        }
        bar.setIndeterminate(false);
        bar.setMax(Math.max(1, total));
        bar.setProgress(done);
        message.setText(String.format(Locale.ROOT, "Copying media… %d / %d", done, total));
    }

    private static String summary(CardMedia.Result r) {
        if (r.total() == 0) {
            return "This export has no media. In AnkiDroid, export again with “Include media” ticked.";
        }
        StringBuilder sb = new StringBuilder();
        if (r.added + r.updated == 0) {
            sb.append("All ").append(r.unchanged).append(" media files were already here.");
        } else {
            sb.append("Added ").append(r.added).append(r.added == 1 ? " file" : " files");
            if (r.updated > 0) {
                sb.append(", updated ").append(r.updated);
            }
            sb.append(" (").append(megabytes(r.bytes)).append(").");
            if (r.unchanged > 0) {
                sb.append(' ').append(r.unchanged).append(" were already here.");
            }
        }
        if (r.skipped > 0) {
            sb.append(' ').append(r.skipped).append(" couldn't be used.");
        }
        sb.append("\n\nThey show up on your cards right away.");
        return sb.toString();
    }

    static String megabytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb < 0.1 && bytes > 0 ? "<0.1 MB" : String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private void finishWith(String text) {
        running = false;
        bar.setVisibility(View.GONE);
        message.setText(text);
        ok.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // leaving mid-import would hide the result; the copy itself carries on regardless
        if (!running) {
            super.onBackPressed();
        }
    }

    // --- a small panel in the game's colours

    private void buildViews() {
        Typeface font;
        try {
            font = Typeface.createFromAsset(getAssets(), "fonts/pixel_font.ttf");
        } catch (RuntimeException e) {
            font = Typeface.DEFAULT;
        }
        int pad = dp(16);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL_COLOR);
        bg.setStroke(dp(3), EDGE_COLOR);
        panel.setBackground(bg);
        panel.setMinimumWidth(dp(280));

        TextView title = new TextView(this);
        title.setText("Card media");
        title.setTypeface(font);
        title.setTextColor(TITLE_COLOR);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        panel.addView(title);

        message = new TextView(this);
        message.setTypeface(font);
        message.setTextColor(TEXT_COLOR);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setPadding(0, dp(10), 0, dp(10));
        panel.addView(message);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        panel.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ok = new Button(this);
        ok.setText("OK");
        ok.setTypeface(font);
        ok.setTextColor(TEXT_COLOR);
        GradientDrawable btn = new GradientDrawable();
        btn.setColor(0xFFA03A2E);
        btn.setStroke(dp(2), 0xFF5E1F18);
        ok.setBackground(btn);
        if (Build.VERSION.SDK_INT >= 21) {
            ok.setStateListAnimator(null);
        }
        ok.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(dp(120), dp(44));
        okParams.gravity = Gravity.END;
        panel.addView(ok, okParams);

        setContentView(panel);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
