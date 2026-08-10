import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class GenerateLogoAssets {
    public static void main(String[] args) throws Exception {
        write("brand/qinqing-bangbang-app-icon-1024.png", render(1024));
        write("app/src/main/res/mipmap-mdpi/ic_launcher.png", render(48));
        write("app/src/main/res/mipmap-mdpi/ic_launcher_round.png", renderRound(48));
        write("app/src/main/res/mipmap-hdpi/ic_launcher.png", render(72));
        write("app/src/main/res/mipmap-hdpi/ic_launcher_round.png", renderRound(72));
        write("app/src/main/res/mipmap-xhdpi/ic_launcher.png", render(96));
        write("app/src/main/res/mipmap-xhdpi/ic_launcher_round.png", renderRound(96));
        write("app/src/main/res/mipmap-xxhdpi/ic_launcher.png", render(144));
        write("app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png", renderRound(144));
        write("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", render(192));
        write("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png", renderRound(192));
    }

    private static BufferedImage render(int size) {
        double s = size / 1024.0;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setPaint(new LinearGradientPaint(
                0, 0, size, size,
                new float[]{0f, 0.52f, 1f},
                new Color[]{c(0xff6f74), c(0xf04b83), c(0x8a5fd1)}));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 232 * s, 232 * s));

        drawPhone(g, 256 * s, 275 * s, 228 * s, 448 * s, c(0x356ae6), s);
        drawPhone(g, 540 * s, 275 * s, 228 * s, 448 * s, c(0xf2556f), s);

        java.awt.geom.Path2D linkHeart = new java.awt.geom.Path2D.Double();
        linkHeart.moveTo(512 * s, 620 * s);
        linkHeart.curveTo(481 * s, 593 * s, 437 * s, 563 * s, 437 * s, 521 * s);
        linkHeart.curveTo(437 * s, 492 * s, 458 * s, 475 * s, 481 * s, 475 * s);
        linkHeart.curveTo(498 * s, 475 * s, 512 * s, 488 * s, 512 * s, 505 * s);
        linkHeart.curveTo(512 * s, 488 * s, 526 * s, 475 * s, 543 * s, 475 * s);
        linkHeart.curveTo(566 * s, 475 * s, 587 * s, 492 * s, 587 * s, 521 * s);
        linkHeart.curveTo(587 * s, 563 * s, 543 * s, 593 * s, 512 * s, 620 * s);
        linkHeart.closePath();
        g.setPaint(new LinearGradientPaint(
                (float) (437 * s), (float) (475 * s),
                (float) (587 * s), (float) (620 * s),
                new float[]{0f, 0.52f, 1f},
                new Color[]{c(0xff6b5f), c(0xf0447d), c(0x6758c8)}));
        g.fill(linkHeart);
        g.dispose();
        return image;
    }

    private static BufferedImage renderRound(int size) {
        BufferedImage square = render(size);
        BufferedImage round = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = round.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new Ellipse2D.Double(0, 0, size, size));
        g.drawImage(square, 0, 0, null);
        g.dispose();
        return round;
    }

    private static void drawPhone(Graphics2D g, double x, double y, double w, double h,
                                  Color color, double scale) {
        g.setColor(c(0xfff8f6));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 56 * scale, 56 * scale));
        g.setColor(color);
        g.fill(new RoundRectangle2D.Double(
                x + 38 * scale, y + 58 * scale, w - 76 * scale, h - 164 * scale,
                8 * scale, 8 * scale));
        g.fill(new Ellipse2D.Double(
                x + (w / 2) - 19 * scale, y + h - 73 * scale, 38 * scale, 38 * scale));
    }

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    private static void write(String path, BufferedImage image) throws Exception {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (!ImageIO.write(image, "png", file)) {
            throw new IllegalStateException("PNG writer is unavailable");
        }
        System.out.println("Wrote " + file.getCanonicalPath());
    }
}
