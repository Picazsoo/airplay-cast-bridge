package io.github.picazsoo.airplaybridge;

/** aircast settings the app exposes. values[0] is the default; labels match values. */
enum Setting {
    CODEC("codec", "Codec to Chromecast",
        new String[]{"flac", "wav", "mp3", "aac"},
        new String[]{"FLAC (lossless)", "WAV (uncompressed)", "MP3", "AAC"}),
    LATENCY("latency", "Buffer / delay",
        new String[]{"", "500:500", "1000:1000", "2000:2000", "1000:1000:f"},
        new String[]{"Default", "Short (0.5 s)", "Medium (1 s)", "Long (2 s)", "Medium + fill gaps"}),
    VOLUME("media_volume", "Volume scale",
        new String[]{"0.5", "0.25", "0.75", "1.0"},
        new String[]{"50 %", "25 %", "75 %", "100 %"}),
    DRIFT("drift", "Timing",
        new String[]{"0", "1"},
        new String[]{"Corrected", "Let drift (no clicks)"}),
    METADATA("metadata", "Song info to Chromecast",
        new String[]{"1", "0"},
        new String[]{"On", "Off"}),
    STOP_RECEIVER("stop_receiver", "On stop",
        new String[]{"0", "1"},
        new String[]{"Leave Cast app open", "Close Cast app"});

    final String key, title;
    final String[] values, labels;

    Setting(String key, String title, String[] values, String[] labels) {
        this.key = key; this.title = title; this.values = values; this.labels = labels;
    }

    String label(String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return labels[i];
        return v;
    }

    String next(String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return values[(i + 1) % values.length];
        return values[0];
    }
}
