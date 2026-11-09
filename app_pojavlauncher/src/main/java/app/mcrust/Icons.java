package app.mcrust;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;

/** Значки модов: скачиваются в фоне, кэшируются в памяти и на диске. */
final class Icons {
    private Icons() {}

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(120);

    static void load(final Host a, final String url, final ImageView view) {
        view.setTag(url);
        view.setImageBitmap(null);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = MEMORY.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        Async.run(a, new Async.Work<Bitmap>() {
            @Override
            public Bitmap run() throws Exception {
                File dir = new File(a.scratch, "icons");
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(url.hashCode()));
                if (!f.isFile() || f.length() == 0) {
                    File part = new File(f.getPath() + ".part");
                    a.net.get(url, part);
                    if (!part.renameTo(f)) {
                        part.delete();
                        return null;
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 96) sample *= 2;
                BitmapFactory.Options real = new BitmapFactory.Options();
                real.inSampleSize = sample;
                return BitmapFactory.decodeFile(f.getPath(), real);
            }
        }, new Async.Done<Bitmap>() {
            @Override
            public void ok(Bitmap b) {
                if (b == null) return;
                MEMORY.put(url, b);
                // строка списка могла уже показывать другой мод
                if (url.equals(view.getTag())) view.setImageBitmap(b);
            }
        }, new Async.Fail() {
            @Override
            public void err(Exception e) {
                // значок не обязателен
            }
        });
    }
}
