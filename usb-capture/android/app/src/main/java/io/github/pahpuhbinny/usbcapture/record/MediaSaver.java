package io.github.pahpuhbinny.usbcapture.record;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/** Saves recordings to Movies/USB Capture and screenshots to Pictures/USB Capture. */
public final class MediaSaver {
    private static final String TAG = "Saver";
    public static final String FOLDER = "USB Capture";
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** An output file being written. */
    public static final class Output {
        public Uri uri;
        public File file;
        public ParcelFileDescriptor pfd;
        public String name;
        public boolean video;

        public String location() {
            return (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + FOLDER + "/" + name;
        }
    }

    private MediaSaver() {
    }

    public static String timestampName(String prefix, String ext) {
        return prefix + "_" + LocalDateTime.now().format(NAME_TIME) + "." + ext;
    }

    public static long freeBytes() {
        try {
            File dir = Environment.getExternalStorageDirectory();
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    public static Output createVideo(Context ctx, String name) throws IOException {
        Output o = new Output();
        o.name = name;
        o.video = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            o.uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (o.uri == null) throw new IOException("Could not create a video in the gallery");
            o.pfd = cr.openFileDescriptor(o.uri, "rw");
            if (o.pfd == null) throw new IOException("Could not open the new video file");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            o.file = new File(dir, name);
            o.pfd = ParcelFileDescriptor.open(o.file, ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return o;
    }

    public static void finish(Context ctx, Output o, boolean keep) {
        try {
            if (o.pfd != null) o.pfd.close();
        } catch (IOException ignored) {
        }
        try {
            if (o.uri != null) {
                ContentResolver cr = ctx.getContentResolver();
                if (keep) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(o.uri, v, null, null);
                } else {
                    cr.delete(o.uri, null, null);
                }
            } else if (o.file != null) {
                if (keep) {
                    MediaScannerConnection.scanFile(ctx, new String[]{o.file.getAbsolutePath()}, null, null);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    o.file.delete();
                }
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "Finishing " + o.name + " failed", t);
        }
    }

    /** Saves a picture; returns its location or throws. */
    public static String saveImage(Context ctx, Bitmap bmp, boolean png) throws IOException {
        String name = timestampName("Capture", png ? "png" : "jpg");
        Bitmap.CompressFormat fmt = png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, png ? "image/png" : "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException("Could not create a picture in the gallery");
            boolean ok = false;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("Could not open the new picture");
                ok = bmp.compress(fmt, 95, os);
            } finally {
                if (ok) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                } else {
                    cr.delete(uri, null, null);
                }
            }
            if (!ok) throw new IOException("Could not encode the picture");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (OutputStream os = new FileOutputStream(f)) {
                if (!bmp.compress(fmt, 95, os)) throw new IOException("Could not encode the picture");
            }
            MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
        }
        return Environment.DIRECTORY_PICTURES + "/" + FOLDER + "/" + name;
    }
}
