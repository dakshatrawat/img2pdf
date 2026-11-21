package com.dehsh4t.img2pdf;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final int MAX_DIM = 2400;
    // A4 in PDF points
    private static final int PAGE_W = 595, PAGE_H = 842, MARGIN = 20;

    private final ArrayList<Uri> images = new ArrayList<>();
    private TextView status;
    private LinearLayout thumbs;
    private Button makeBtn;
    private Uri lastPdf;
    private Button openBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Pick images, then make a PDF. One image per page.");
        title.setTextSize(16);
        root.addView(title);

        Button pick = new Button(this);
        pick.setText("Pick images");
        pick.setOnClickListener(v -> pickImages());
        root.addView(pick);

        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> { images.clear(); refresh(); });
        root.addView(clear);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        thumbs = new LinearLayout(this);
        thumbs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(thumbs);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));

        makeBtn = new Button(this);
        makeBtn.setText("Make PDF");
        makeBtn.setOnClickListener(v -> askWhereToSave());
        root.addView(makeBtn);

        openBtn = new Button(this);
        openBtn.setText("Open PDF");
        openBtn.setOnClickListener(v -> openPdf());
        root.addView(openBtn);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
        handleShare(getIntent());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
        refresh();
    }

    private void handleShare(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) images.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) images.addAll(list);
        }
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    private void askWhereToSave() {
        if (images.isEmpty()) {
            toast("Pick at least one image first");
            return;
        }
        String name = "images_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".pdf";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) images.add(clip.getItemAt(k).getUri());
            } else if (data.getData() != null) {
                images.add(data.getData());
            }
            refresh();
        } else if (requestCode == REQ_SAVE && data.getData() != null) {
            buildPdf(data.getData());
        }
    }

    private void buildPdf(Uri out) {
        makeBtn.setEnabled(false);
        final ArrayList<Uri> list = new ArrayList<>(images);
        status.setText("Making PDF…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            PdfDocument doc = new PdfDocument();
            try {
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                for (int n = 0; n < list.size(); n++) {
                    final int page = n + 1;
                    runOnUiThread(() -> status.setText("Adding page " + page + " of " + list.size()));
                    Bitmap bmp = loadBitmap(list.get(n));
                    if (bmp == null) continue;
                    // Landscape images get landscape pages
                    boolean landscape = bmp.getWidth() > bmp.getHeight();
                    int pw = landscape ? PAGE_H : PAGE_W;
                    int ph = landscape ? PAGE_W : PAGE_H;
                    PdfDocument.Page p = doc.startPage(
                            new PdfDocument.PageInfo.Builder(pw, ph, page).create());
                    Canvas c = p.getCanvas();
                    c.drawColor(Color.WHITE);
                    float availW = pw - 2f * MARGIN, availH = ph - 2f * MARGIN;
                    float scale = Math.min(availW / bmp.getWidth(), availH / bmp.getHeight());
                    float w = bmp.getWidth() * scale, h = bmp.getHeight() * scale;
                    float left = (pw - w) / 2f, top = (ph - h) / 2f;
                    c.drawBitmap(bmp, null, new RectF(left, top, left + w, top + h), paint);
                    doc.finishPage(p);
                    bmp.recycle();
                }
                try (OutputStream os = getContentResolver().openOutputStream(out)) {
                    doc.writeTo(os);
                }
                ok = true;
                msg = "Saved PDF with " + list.size() + " page(s)";
            } catch (Throwable t) {
                msg = "Failed: " + t.getMessage();
            } finally {
                doc.close();
            }
            final String m = msg;
            final boolean success = ok;
            runOnUiThread(() -> {
                status.setText(m);
                makeBtn.setEnabled(true);
                if (success) {
                    lastPdf = out;
                    openBtn.setEnabled(true);
                    toast(m);
                }
            });
        }).start();
    }

    private Bitmap loadBitmap(Uri uri) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, b);
            }
            int sample = 1;
            while (b.outWidth / sample > MAX_DIM || b.outHeight / sample > MAX_DIM) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) return null;
            int rotate = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int ori = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
            } catch (Exception ignored) { }
            if (rotate != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotate);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void openPdf() {
        if (lastPdf == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastPdf, "application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("No PDF viewer installed");
        }
    }

    private void refresh() {
        thumbs.removeAllViews();
        for (Uri u : images) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(120));
            lp.setMargins(0, 0, dp(8), 0);
            iv.setLayoutParams(lp);
            iv.setImageBitmap(thumb(u));
            thumbs.addView(iv);
        }
        status.setText(images.size() + " image(s) selected");
        makeBtn.setEnabled(!images.isEmpty());
        openBtn.setEnabled(lastPdf != null);
    }

    private Bitmap thumb(Uri uri) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, b);
            }
            int sample = 1;
            while (b.outWidth / sample > 300 || b.outHeight / sample > 300) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
