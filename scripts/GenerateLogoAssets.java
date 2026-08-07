import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
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
                new Color[]{c(0xff6b5f), c(0xf0447d), c(0x6758c8)}));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 232 * s, 232 * s));

        Path2D heart = new Path2D.Double();
        heart.moveTo(512 * s, 769 * s);
        heart.curveTo(458 * s, 722 * s, 248 * s, 590 * s, 248 * s, 419 * s);
        heart.curveTo(248 * s, 302 * s, 333 * s, 240 * s, 434 * s, 240 * s);
        heart.curveTo(481 * s, 240 * s, 512 * s, 271 * s, 512 * s, 318 * s);
        heart.curveTo(512 * s, 271 * s, 543 * s, 240 * s, 590 * s, 240 * s);
        heart.curveTo(691 * s, 240 * s, 776 * s, 302 * s, 776 * s, 419 * s);
        heart.curveTo(776 * s, 590 * s, 566 * s, 722 * s, 512 * s, 769 * s);
        heart.closePath();
        g.setColor(c(0xfff8f6));
        g.fill(heart);

        drawPhone(g, 344 * s, 365 * s, 136 * s, 246 * s, c(0x356ae6), s);
        drawPhone(g, 544 * s, 365 * s, 136 * s, 246 * s, c(0xf2556f), s);

        Path2D linkHeart = new Path2D.Double();
        linkHeart.moveTo(512 * s, 564 * s);
        linkHeart.curveTo(495 * s, 549 * s, 470 * s, 532 * s, 470 * s, 507 * s);
        linkHeart.curveTo(470 * s, 490 * s, 482 * s, 480 * s, 495 * s, 480 * s);
        linkHeart.curveTo(505 * s, 480 * s, 512 * s, 488 * s, 512 * s, 497 * s);
        linkHeart.curveTo(512 * s, 488 * s, 519 * s, 480 * s, 529 * s, 480 * s);
        linkHeart.curveTo(542 * s, 480 * s, 554 * s, 490 * s, 554 * s, 507 * s);
        linkHeart.curveTo(554 * s, 532 * s, 529 * s, 549 * s, 512 * s, 564 * s);
        linkHeart.closePath();
        g.setColor(c(0xf2a23a));
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
        g.setColor(color);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 42 * scale, 42 * scale));
        g.setColor(c(0xfff8f6));
        g.fill(new RoundRectangle2D.Double(
                x + 28 * scale, y + 38 * scale, w - 56 * scale, h - 112 * scale,
                8 * scale, 8 * scale));
        g.fill(new Ellipse2D.Double(
                x + (w / 2) - 12 * scale, y + h - 50 * scale, 24 * scale, 24 * scale));
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
