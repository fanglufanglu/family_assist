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

        drawPerson(g, 427 * s, 455 * s, 47 * s, 332 * s, 512 * s, 180 * s, 170 * s, c(0xb72c49), true);
        drawPerson(g, 597 * s, 455 * s, 47 * s, 512 * s, 512 * s, 180 * s, 170 * s, c(0xf2a23a), false);
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

    private static void drawPerson(Graphics2D g, double cx, double cy, double radius,
                                   double x, double y, double w, double h, Color color, boolean left) {
        g.setColor(color);
        g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
        Path2D body = new Path2D.Double();
        if (left) {
            body.moveTo(x, y + h);
            body.curveTo(x, y + 42, x + 38, y, x + 90, y);
            body.curveTo(x + 145, y, x + w, y + 48, x + w, y + 104);
            body.lineTo(x + w, y + h);
            body.curveTo(x + 122, y + 130, x + 58, y + 130, x, y + h);
        } else {
            body.moveTo(x + w, y + h);
            body.curveTo(x + w, y + 42, x + w - 38, y, x + 90, y);
            body.curveTo(x + 35, y, x, y + 48, x, y + 104);
            body.lineTo(x, y + h);
            body.curveTo(x + 58, y + 130, x + 122, y + 130, x + w, y + h);
        }
        body.closePath();
        g.fill(body);
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
