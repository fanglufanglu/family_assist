import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
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
        g.setColor(c(0x176b5b));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 232 * s, 232 * s));

        Path2D home = new Path2D.Double();
        home.moveTo(512 * s, 190 * s);
        home.lineTo(842 * s, 438 * s);
        home.lineTo(780 * s, 438 * s);
        home.lineTo(780 * s, 790 * s);
        home.quadTo(780 * s, 836 * s, 734 * s, 836 * s);
        home.lineTo(290 * s, 836 * s);
        home.quadTo(244 * s, 836 * s, 244 * s, 790 * s);
        home.lineTo(244 * s, 438 * s);
        home.lineTo(182 * s, 438 * s);
        home.closePath();
        g.setColor(c(0xfff9f3));
        g.fill(home);

        drawPerson(g, 402 * s, 408 * s, 58 * s, 322 * s, 520 * s, 198 * s, 218 * s, c(0xe85d4a));
        drawPerson(g, 622 * s, 408 * s, 58 * s, 542 * s, 520 * s, 198 * s, 218 * s, c(0x315fd4));
        drawPerson(g, 512 * s, 588 * s, 47 * s, 447 * s, 658 * s, 130 * s, 142 * s, c(0x2a8f6f));
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
                                   double x, double y, double w, double h, Color color) {
        g.setColor(color);
        g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, w * 0.27, w * 0.27));
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
