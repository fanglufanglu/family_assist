import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class GenerateLogoAssets {
    public static void main(String[] args) throws Exception {
        write("brand/qinqing-bangbang-app-icon-1024.png", render(1024));
        write("app/src/main/res/mipmap-mdpi/ic_launcher.png", render(48));
        write("app/src/main/res/mipmap-mdpi/ic_launcher_round.png", render(48));
        write("app/src/main/res/mipmap-hdpi/ic_launcher.png", render(72));
        write("app/src/main/res/mipmap-hdpi/ic_launcher_round.png", render(72));
        write("app/src/main/res/mipmap-xhdpi/ic_launcher.png", render(96));
        write("app/src/main/res/mipmap-xhdpi/ic_launcher_round.png", render(96));
        write("app/src/main/res/mipmap-xxhdpi/ic_launcher.png", render(144));
        write("app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png", render(144));
        write("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", render(192));
        write("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png", render(192));
    }

    private static BufferedImage render(int size) {
        double s = size / 1024.0;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setPaint(new GradientPaint(120f * (float) s, 90f * (float) s, c(0xff7a59), 900f * (float) s, 930f * (float) s, c(0x2f80ed)));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 232 * s, 232 * s));

        GeneralPath heart = new GeneralPath();
        heart.moveTo(512 * s, 824 * s);
        heart.curveTo(352 * s, 749 * s, 254 * s, 629 * s, 254 * s, 462 * s);
        heart.curveTo(254 * s, 350 * s, 335 * s, 262 * s, 441 * s, 262 * s);
        heart.curveTo(478 * s, 262 * s, 512 * s, 274 * s, 540 * s, 297 * s);
        heart.curveTo(568 * s, 274 * s, 602 * s, 262 * s, 639 * s, 262 * s);
        heart.curveTo(745 * s, 262 * s, 826 * s, 350 * s, 826 * s, 462 * s);
        heart.curveTo(826 * s, 629 * s, 728 * s, 749 * s, 568 * s, 824 * s);
        heart.curveTo(550 * s, 832 * s, 530 * s, 832 * s, 512 * s, 824 * s);
        heart.closePath();
        g.setColor(c(0xfff7ed));
        g.fill(heart);

        drawPhone(g, 312 * s, 360 * s, 188 * s, 292 * s, 48 * s, c(0x2563eb), c(0xdbeafe));
        drawPhone(g, 524 * s, 360 * s, 188 * s, 292 * s, 48 * s, c(0xef4444), c(0xffe4e6));

        g.setColor(c(0xfff7ed));
        g.fill(new RoundRectangle2D.Double(476 * s, 468 * s, 72 * s, 96 * s, 36 * s, 36 * s));

        g.setColor(c(0x12b981));
        g.fill(new Ellipse2D.Double(462 * s, 460 * s, 100 * s, 100 * s));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(492 * s, 490 * s, 40 * s, 40 * s));
        g.dispose();
        return image;
    }

    private static void drawPhone(Graphics2D g, double x, double y, double w, double h, double r, Color body, Color screen) {
        g.setColor(body);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r, r));
        g.setColor(screen);
        g.fill(new RoundRectangle2D.Double(x + 26 * w / 158, y + 44 * h / 286, w - 52 * w / 158, h - 108 * h / 286, 18 * w / 158, 18 * w / 158));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(x + w / 2 - 13 * w / 158, y + h - 34 * h / 286, 26 * w / 158, 26 * w / 158));
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
        ImageIO.write(image, "png", file);
    }
}
