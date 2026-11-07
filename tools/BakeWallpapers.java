import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

// Bakes the ten photo wallpapers of the home screen ONCE, so the TV no longer does it at every choice.
//
// What the app used to do at runtime (MainActivity.photoBackground), and what this does instead:
//   1. scale the photo to cover the 1920x1080 screen and crop it (BIAS: 0 = keep the top, 1 = keep the bottom)
//   2. black over the top 38% (fading out, for the clock and icons), the bottom 50% (fading in, for the apps) and a little
//      over everything (a projector in a dark room is bright enough)
// plus a small square picture (centre of the UNdarkened photo) for the wallpaper list.
// Output: wallpaper_*.webp (1920x1080, sRGB, the app only has to decode it) and wallpaper_*_thumb.webp (96x96).
// WebP (lossy, quality 80) is about half the size of the JPEG (quality 90) these used to be, with no visible difference;
// Java can't write WebP, so each picture goes through a lossless PNG and Google's cwebp (macOS: brew install webp).
// The shading is done per pixel with a little noise before rounding (dithering), as the TV's Skia did, so the dark
// gradients don't band.
//
// Run:  javac -d /tmp/bake tools/BakeWallpapers.java
//       java -cp /tmp/bake BakeWallpapers wallpaper-originals res/drawable-nodpi
public class BakeWallpapers {
    static final String[] RES = {"wallpaper_nature", "wallpaper_mist_trees", "wallpaper_mist_valley", "wallpaper_snow",
            "wallpaper_aurora", "wallpaper_waterfall", "wallpaper_tokyo", "wallpaper_amsterdam", "wallpaper_nyhavn",
            "wallpaper_honfleur"};
    // The unchanged file each is made from (Misty Trees: the Adobe RGB original, converted to sRGB by the reader here).
    static final String[] INPUT = {"wallpaper_nature.jpg", "wallpaper_mist_trees_AdobeRGB-original.jpg", "wallpaper_mist_valley.jpg",
            "wallpaper_snow.jpg", "wallpaper_aurora.jpg", "wallpaper_waterfall.jpg", "wallpaper_tokyo.jpg",
            "wallpaper_amsterdam.jpg", "wallpaper_nyhavn.jpg", "wallpaper_honfleur.jpg"};
    // Same numbers the app had (PHOTO_BIAS, PHOTO_TOP, PHOTO_BOTTOM, PHOTO_DIM).
    static final float[] BIAS = {0.30f, 0.35f, 0.40f, 0.50f, 0.40f, 0.40f, 0.50f, 0.30f, 0.40f, 0.35f};
    static final int[] TOP = {0xA0, 0xB8, 0xA0, 0xC8, 0x80, 0xB0, 0x70, 0x80, 0x90, 0xC0};
    static final int[] BOTTOM = {0xB8, 0xB0, 0xB8, 0xD0, 0x98, 0xC0, 0xC0, 0xC0, 0xB0, 0xB0};
    static final int[] DIM = {0x26, 0x26, 0x20, 0x3C, 0x00, 0x30, 0x20, 0x28, 0x20, 0x38};
    static final int W = 1920, H = 1080, THUMB = 96;
    static final String QUALITY = "80"; // cwebp -q

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        Random noise = new Random(7);
        for (int p = 0; p < RES.length; p++) {
            BufferedImage src = ImageIO.read(new File(in, INPUT[p]));
            int sw = src.getWidth(), sh = src.getHeight();

            // 1. cover + crop
            double s = Math.max((double) W / sw, (double) H / sh);
            BufferedImage fit = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = fit.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double dw = sw * s, dh = sh * s;
            double top = -(dh - H) * BIAS[p];
            if (Math.abs(s - 1) < 1e-9) { // the usual case (all ten are 1920 wide): a plain crop, no resampling blur
                g.drawImage(src, (int) Math.round((W - dw) / 2), (int) Math.round(top), null);
            } else {
                g.drawImage(src, (int) Math.round((W - dw) / 2), (int) Math.round(top), (int) Math.round(dw), (int) Math.round(dh), null);
            }
            g.dispose();

            // 2. shade, with dithering
            double aDim = DIM[p] / 255.0;
            BufferedImage baked = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[W];
            for (int y = 0; y < H; y++) {
                double aTop = TOP[p] / 255.0 * Math.max(0, 1 - y / (0.38 * H));
                double aBot = BOTTOM[p] / 255.0 * Math.max(0, Math.min(1, (y - 0.5 * H) / (0.5 * H)));
                double f = (1 - aTop) * (1 - aBot) * (1 - aDim);
                fit.getRGB(0, y, W, 1, row, 0, W);
                for (int x = 0; x < W; x++) {
                    int c = row[x];
                    int r = clamp(((c >> 16) & 255) * f + noise.nextDouble() - 0.5);
                    int gr = clamp(((c >> 8) & 255) * f + noise.nextDouble() - 0.5);
                    int b = clamp((c & 255) * f + noise.nextDouble() - 0.5);
                    row[x] = (r << 16) | (gr << 8) | b;
                }
                baked.setRGB(0, y, W, 1, row, 0, W);
            }
            File bakedFile = new File(out, RES[p] + ".webp");
            writeWebp(baked, bakedFile);

            // 3. the list picture: centre square of the untouched photo
            int side = Math.min(sw, sh);
            BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = square.createGraphics();
            sg.drawImage(src, -(sw - side) / 2, -(sh - side) / 2, null);
            sg.dispose();
            Image small = square.getScaledInstance(THUMB, THUMB, Image.SCALE_AREA_AVERAGING);
            BufferedImage thumb = new BufferedImage(THUMB, THUMB, BufferedImage.TYPE_INT_RGB);
            Graphics2D tg = thumb.createGraphics();
            tg.drawImage(small, 0, 0, null);
            tg.dispose();
            File thumbFile = new File(out, RES[p] + "_thumb.webp");
            writeWebp(thumb, thumbFile);

            System.out.printf("%-22s %4dx%-4d crop-y %5.1f  ->  %s %d KB, thumb %d KB%n", RES[p], sw, sh, -top,
                    bakedFile.getName(), bakedFile.length() / 1024, thumbFile.length() / 1024);
        }
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    // Lossless PNG first, then cwebp: -m 6 = slowest/best compression, -sharp_yuv keeps colour edges crisp.
    static void writeWebp(BufferedImage img, File f) throws Exception {
        File png = File.createTempFile("bake", ".png");
        try {
            ImageIO.write(img, "png", png);
            Process c = new ProcessBuilder("cwebp", "-quiet", "-q", QUALITY, "-m", "6", "-sharp_yuv",
                    png.getPath(), "-o", f.getPath()).inheritIO().start();
            if (c.waitFor() != 0) throw new IllegalStateException("cwebp failed for " + f);
        } finally {
            png.delete();
        }
    }
}
