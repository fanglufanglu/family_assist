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
        heart.moveTo(512 * s, 790 * s);
        heart.curveTo(455 * s, 733 * s, 256 * s, 607 * s, 256 * s, 436 * s);
        heart.curveTo(256 * s, 322 * s, 341 * s, 256 * s, 436 * s, 256 * s);
        heart.curveTo(483 * s, 256 * s, 512 * s, 284 * s, 512 * s, 332 * s);
        heart.curveTo(512 * s, 284 * s, 550 * s, 256 * s, 597 * s, 256 * s);
        heart.curveTo(692 * s, 256 * s, 768 * s, 322 * s, 768 * s, 436 * s);
        heart.curveTo(768 * s, 607 * s, 569 * s, 733 * s, 512 * s, 790 * s);
        heart.closePath();
        g.setColor(c(0xfff8f6));
        g.fill(heart);

        drawPhone(g, 322 * s, 389 * s, 171 * s, 303 * s, c(0x356ae6), s);
        drawPhone(g, 531 * s, 389 * s, 171 * s, 303 * s, c(0xf2556f), s);

        Path2D linkHeart = new Path2D.Double();
        linkHeart.moveTo(512 * s, 607 * s);
        linkHeart.curveTo(493 * s, 588 * s, 465 * s, 569 * s, 465 * s, 541 * s);
        linkHeart.curveTo(465 * s, 522 * s, 479 * s, 512 * s, 493 * s, 512 * s);
        linkHeart.curveTo(504 * s, 512 * s, 512 * s, 522 * s, 512 * s, 531 * s);
        linkHeart.curveTo(512 * s, 522 * s, 520 * s, 512 * s, 531 * s, 512 * s);
        linkHeart.curveTo(545 * s, 512 * s, 559 * s, 522 * s, 559 * s, 541 * s);
        linkHeart.curveTo(559 * s, 569 * s, 531 * s, 588 * s, 512 * s, 607 * s);
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
                x + 38 * scale, y + 47 * scale, w - 76 * scale, h - 132 * scale,
                8 * scale, 8 * scale));
        g.fill(new Ellipse2D.Double(
                x + (w / 2) - 14 * scale, y + h - 62 * scale, 28 * scale, 28 * scale));
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
