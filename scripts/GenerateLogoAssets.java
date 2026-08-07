import javax.imageio.ImageIO;
import java.awt.BasicStroke;
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

        drawPhone(g, 318 * s, 398 * s, 206 * s, 282 * s, 42 * s, c(0x356ae6));
        drawPhone(g, 500 * s, 398 * s, 206 * s, 282 * s, 42 * s, c(0xf2556f));
        g.setColor(c(0xf2a23a));
        g.fill(new Ellipse2D.Double(465 * s, 515 * s, 94 * s, 94 * s));
        g.setColor(c(0xfff8f6));
        g.fill(new Ellipse2D.Double(494 * s, 544 * s, 36 * s, 36 * s));
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
                                  double strokeWidth, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke((float) strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 48 * (w / 206.0), 48 * (w / 206.0)));
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
