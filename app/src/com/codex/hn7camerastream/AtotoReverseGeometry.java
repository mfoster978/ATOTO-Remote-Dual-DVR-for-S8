package com.codex.hn7camerastream;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads capture geometry only; never writes decoder, MCU, or power settings. */
final class AtotoReverseGeometry {
    static int[] detectedSize() {
        try (BufferedReader input = new BufferedReader(new FileReader("/proc/driver/camera_info"))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = input.readLine()) != null && text.length() < 32768) text.append(line).append('\n');
            return parseSize(text.toString());
        } catch (Exception unavailable) { return null; }
    }

    static int[] parseSize(String text) {
        Matcher section = Pattern.compile("(?s)CAM_Info\\[1\\]:([^;]+);(.*?)(?:CAM_Info\\[|\\z)").matcher(text);
        if (!section.find() || !section.group(1).contains("tp9951")) return null;
        Matcher dimensions = Pattern.compile("Pre:.*?TgGrab_w,h,x_,y=\\s*(\\d+),\\s*(\\d+)").matcher(section.group(2));
        if (!dimensions.find()) return null;
        int width = Integer.parseInt(dimensions.group(1));
        int height = Integer.parseInt(dimensions.group(2));
        if (width < 160 || width > 1920 || height < 96 || height > 1080 || ((width | height) & 1) != 0) return null;
        return new int[]{width, height};
    }

    static boolean isAnalogField(int width, int height) {
        return width >= 720 && height <= 288;
    }

    /** Preserve every source row and chroma pair when expanding an analog field. */
    static byte[] normalize(byte[] source, int width, int height, int outputHeight) {
        if (source == null || source.length < width * height * 3 / 2) return null;
        byte[] output = new byte[width * outputHeight * 3 / 2];
        for (int y = 0; y < outputHeight; y++) {
            int sourceY = (int) ((long) y * height / outputHeight);
            System.arraycopy(source, sourceY * width, output, y * width, width);
        }
        int inputChroma = width * height;
        int outputChroma = width * outputHeight;
        for (int y = 0; y < outputHeight / 2; y++) {
            int sourceY = (int) ((long) y * (height / 2) / (outputHeight / 2));
            System.arraycopy(source, inputChroma + sourceY * width, output, outputChroma + y * width, width);
        }
        return output;
    }
}
