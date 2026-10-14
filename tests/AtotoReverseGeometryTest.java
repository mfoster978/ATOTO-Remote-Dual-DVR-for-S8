package com.codex.hn7camerastream;

import java.util.Arrays;

public final class AtotoReverseGeometryTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String info = "CAM_Info[0]:tp2856_mipi_yuv;\nPre: TgGrab_w,h,x_,y= 1920, 1080, 4, 0\n" +
                "CAM_Info[1]:tp9951_mipi_yuv;\nPre: TgGrab_w,h,x_,y= 960, 240, 0, 0\n";
        check(Arrays.equals(AtotoReverseGeometry.parseSize(info), new int[]{960, 240}), "Wrong reverse decoder selected");
        check(AtotoReverseGeometry.parseSize(info.replace("960, 240", "961, 240")) == null, "Odd NV21 dimension accepted");
        check(AtotoReverseGeometry.parseSize(info.replace("tp9951", "other_sensor")) == null, "Unknown driver must retain fallback");
        check(AtotoReverseGeometry.parseSize("CAM_Info[0]:tp9951_mipi_yuv;") == null, "Front driver cannot select rear dimensions");
        check(Arrays.equals(AtotoReverseGeometry.parseSize(info.replace("960, 240", "1280, 720")), new int[]{1280, 720}), "Progressive camera detection failed");
        check(AtotoReverseGeometry.isAnalogField(960, 240) && AtotoReverseGeometry.isAnalogField(960, 288), "NTSC/PAL field detection failed");
        check(!AtotoReverseGeometry.isAnalogField(1280, 720), "Progressive frame marked interlaced");

        // Distinct rows and edge pixels detect cropping, stride errors, and lost UV pairs.
        int width = 960, height = 240, target = 540;
        byte[] frame = new byte[width * height * 3 / 2];
        for (int y = 0; y < height; y++) {
            Arrays.fill(frame, y * width, (y + 1) * width, (byte)y);
            frame[y * width] = 11; frame[(y + 1) * width - 1] = 29;
        }
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width; x += 2) {
                frame[width * height + y * width + x] = (byte)(y + 17);
                frame[width * height + y * width + x + 1] = (byte)(y + 43);
            }
        }
        byte[] full = AtotoReverseGeometry.normalize(frame, width, height, target);
        check(full.length == width * target * 3 / 2, "Output buffer size wrong");
        check(full[1] == 0 && full[(target - 1) * width + 1] == (byte)(height - 1), "Top or bottom cropped");
        for (int y = 0; y < target; y++) check(full[y * width] == 11 && full[(y + 1) * width - 1] == 29, "Left or right cropped");
        int lastUv = width * target + (target / 2 - 1) * width;
        check(full[lastUv] == (byte)(height / 2 - 1 + 17) && full[lastUv + 1] == (byte)(height / 2 - 1 + 43), "Chroma pairs or final row lost");
        check(Arrays.equals(frame, AtotoReverseGeometry.normalize(frame, width, height, height)), "Progressive pixels changed");
        check(AtotoReverseGeometry.normalize(new byte[10], width, height, target) == null, "Short camera buffer accepted");
        System.out.println("PASS detected NTSC/PAL and progressive geometry; full-frame edges and NV21 chroma retained");
    }
}
