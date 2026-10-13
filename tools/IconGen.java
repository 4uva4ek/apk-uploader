import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Generates app/icons/app-icon.png and app-icon.ico. Usage: java tools/IconGen.java app/icons */
public class IconGen {
    public static void main(String[] args) throws Exception {
        File directory = new File(args.length > 0 ? args[0] : "app/icons");
        directory.mkdirs();
        int[] sizes = {256, 64, 48, 32, 16};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            BufferedImage image = draw(size);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            images.add(bytes.toByteArray());
            if (size == 256) ImageIO.write(image, "png", new File(directory, "app-icon.png"));
        }
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = header.capacity();
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            byte[] data = images.get(i);
            header.put((byte) (size == 256 ? 0 : size)).put((byte) (size == 256 ? 0 : size)).put((byte) 0).put((byte) 0);
            header.putShort((short) 1).putShort((short) 32).putInt(data.length).putInt(offset);
            offset += data.length;
        }
        try (FileOutputStream out = new FileOutputStream(new File(directory, "app-icon.ico"))) {
            out.write(header.array());
            for (byte[] data : images) out.write(data);
        }
    }

    static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size;
        float radius = s * 0.24f;
        g.setColor(new Color(0x6650A4));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, radius * 2, radius * 2));
        g.setColor(new Color(0xD0BCFF));
        g.fill(new RoundRectangle2D.Float(s * 0.08f, s * 0.08f, s * 0.84f, s * 0.84f, radius * 1.6f, radius * 1.6f));
        g.setColor(new Color(0x2B1B5A));
        // Upload arrow
        Path2D.Float arrow = new Path2D.Float();
        arrow.moveTo(s * 0.50f, s * 0.20f);
        arrow.lineTo(s * 0.74f, s * 0.46f);
        arrow.lineTo(s * 0.60f, s * 0.46f);
        arrow.lineTo(s * 0.60f, s * 0.68f);
        arrow.lineTo(s * 0.40f, s * 0.68f);
        arrow.lineTo(s * 0.40f, s * 0.46f);
        arrow.lineTo(s * 0.26f, s * 0.46f);
        arrow.closePath();
        g.fill(arrow);
        // Tray line
        g.setStroke(new BasicStroke(Math.max(1f, s * 0.07f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(Math.round(s * 0.26f), Math.round(s * 0.80f), Math.round(s * 0.74f), Math.round(s * 0.80f));
        g.dispose();
        return image;
    }
}
