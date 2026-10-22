package com.zin.robot;

import java.text.Normalizer;
import java.util.Locale;

/** Turns a spoken Vietnamese sentence into a robot command (diacritics are ignored). */
final class VoiceCommands {
    static final int STOP = 1, FORWARD = 2, BACK = 3, LEFT = 4, RIGHT = 5, SPIN_LEFT = 6, SPIN_RIGHT = 7,
            U_TURN = 8, HORN = 9, LIGHT_ON = 10, LIGHT_OFF = 11, MODE = 12, DANCE = 13, RADAR = 14,
            TRACK_COLOR = 15, TRACK_FACE = 16, SPEED = 17;

    private static final class Rule {
        final int cmd, arg;
        final String[] words;
        Rule(int cmd, int arg, String... words) { this.cmd = cmd; this.arg = arg; this.words = words; }
    }

    // first match wins, so specific phrases come before the single words they contain
    private static final Rule[] RULES = {
        new Rule(STOP, 0, "dung lai", "dung", "dung xe", "stop", "thoi", "khoan"),
        new Rule(LIGHT_OFF, 0, "tat den", "tat flash", "tat den pha"),
        new Rule(LIGHT_ON, 0, "bat den", "mo den", "bat flash", "sang den", "bat den pha"),
        new Rule(HORN, 0, "bop coi", "bam coi", "coi", "bim bim", "tin tin"),
        new Rule(TRACK_COLOR, 0, "bam mau", "theo mau", "duoi mau"),
        new Rule(TRACK_FACE, 0, "bam mat", "bam nguoi", "tim nguoi", "theo nguoi", "nhin toi"),
        new Rule(MODE, 1, "tranh vat", "ne vat", "tranh vat can"),
        new Rule(MODE, 2, "do line", "do vach", "do duong", "theo vach"),
        new Rule(MODE, 3, "bam theo", "di theo"),
        new Rule(MODE, 4, "canh gac", "bao ve", "gac"),
        new Rule(MODE, 0, "lai tay", "thu cong", "dieu khien tay"),
        new Rule(DANCE, 0, "nhay", "khieu vu", "bat nhac", "nhay di"),
        new Rule(RADAR, 0, "quet", "radar", "quet radar"),
        new Rule(U_TURN, 0, "quay dau", "quay xe", "quay lai"),
        new Rule(SPIN_LEFT, 0, "xoay trai", "quay trai"),
        new Rule(SPIN_RIGHT, 0, "xoay phai", "quay phai"),
        new Rule(LEFT, 0, "re trai", "queo trai", "sang trai", "trai"),
        new Rule(RIGHT, 0, "re phai", "queo phai", "sang phai", "phai"),
        new Rule(BACK, 0, "lui", "di lui", "de lui", "lui lai"),
        new Rule(FORWARD, 0, "tien", "di thang", "chay", "di toi", "tien len", "di"),
        new Rule(SPEED, 2, "nhanh", "nhanh len"),
        new Rule(SPEED, 0, "cham", "tu tu", "cham lai"),
        new Rule(SPEED, 1, "vua", "binh thuong"),
    };

    private VoiceCommands() { }

    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replace('đ', 'd').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return " " + n + " ";
    }

    /** {command, argument}, or null when nothing was recognised. */
    static int[] parse(String spoken) {
        String t = normalize(spoken);
        for (Rule r : RULES)
            for (String w : r.words)
                if (t.contains(" " + w + " ")) return new int[] { r.cmd, r.arg };
        return null;
    }
}
