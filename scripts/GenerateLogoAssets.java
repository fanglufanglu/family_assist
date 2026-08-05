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

        g.setColor(c(0xfff7ed));
        g.fill(new Ellipse2D.Double(214 * s, 214 * s, 596 * s, 596 * s));

        drawPhone(g, 300 * s, 352 * s, 200 * s, 320 * s, 50 * s, c(0x2563eb), c(0xdbeafe));
        drawPhone(g, 524 * s, 352 * s, 200 * s, 320 * s, 50 * s, c(0xef4444), c(0xffe4e6));

        g.setColor(c(0xfff7ed));
        g.fill(new RoundRectangle2D.Double(472 * s, 462 * s, 80 * s, 116 * s, 40 * s, 40 * s));

        g.setColor(c(0x12b981));
        g.fill(new Ellipse2D.Double(454 * s, 450 * s, 116 * s, 116 * s));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(494 * s, 490 * s, 36 * s, 36 * s));
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
