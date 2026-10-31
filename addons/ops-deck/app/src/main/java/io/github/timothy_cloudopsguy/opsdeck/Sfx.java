package io.github.timothy_cloudopsguy.opsdeck;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The deck's noises, synthesised at start so the app ships no sound files. Apps get a blip on a pentatonic scale: each
 * app has its own note, so scrolling the row plays a little tune.
 */
final class Sfx {
    private static final int RATE = 22050;
    private static final int[] PENTATONIC = {0, 2, 4, 7, 9, 12, 14, 16};
    private static final String PREF_ON = "sound_on";

    private final SoundPool pool;
    private final SharedPreferences prefs;
    private int blip;
    private int tick;
    private int confirm;
    private int alarm;
    private int gameOver;
    private boolean on;

    Sfx(Context context, SharedPreferences prefs) {
        this.prefs = prefs;
        on = prefs.getBoolean(PREF_ON, true);
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        File dir = context.getCacheDir();
        try {
            blip = load(dir, "blip", tone(new float[]{660f}, 0.09f, 0.55f, 1.25f));
            tick = load(dir, "tick", tone(new float[]{1400f}, 0.025f, 0.3f, 1f));
            confirm = load(dir, "confirm", tone(new float[]{523f, 784f, 1047f}, 0.07f, 0.5f, 1f));
            alarm = load(dir, "alarm", tone(new float[]{220f, 165f}, 0.13f, 0.6f, 1f));
            gameOver = load(dir, "over", tone(new float[]{392f, 330f, 262f, 196f}, 0.16f, 0.6f, 1f));
        } catch (IOException e) {
            // Without a cache to write to the deck is simply silent: every id stays 0, which SoundPool ignores.
        }
    }

    boolean isOn() {
        return on;
    }

    void toggle() {
        on = !on;
        prefs.edit().putBoolean(PREF_ON, on).apply();
        confirm();
    }

    /** The noise for the nth app in the row: the note wraps around the scale, an octave stays in tune. */
    void app(int index) {
        play(blip, 0.8f, noteRate(index));
    }

    void tick() {
        play(tick, 0.45f, 1f);
    }

    void confirm() {
        play(confirm, 0.7f, 1f);
    }

    void alarm() {
        play(alarm, 0.7f, 1f);
    }

    void gameOver() {
        play(gameOver, 0.8f, 1f);
    }

    /** Fix sounds climb with the combo. */
    void fixed(int combo) {
        play(blip, 0.8f, noteRate(combo));
    }

    void release() {
        pool.release();
    }

    /** SoundPool's playback rate for a step on the scale, within its 0.5 to 2 range. */
    static float noteRate(int index) {
        int step = PENTATONIC[Math.floorMod(index, PENTATONIC.length)];
        return (float) Math.pow(2, (step - 8) / 12.0);
    }

    private void play(int sound, float volume, float rate) {
        if (on) pool.play(sound, volume, volume, 1, 0, rate);
    }

    private int load(File dir, String name, short[] samples) throws IOException {
        File file = new File(dir, "sfx-" + name + ".wav");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(wav(samples));
        }
        return pool.load(file.getPath(), 1);
    }

    /** One note after another: a sine with a touch of triangle for bite, with a quick attack and an even decay. */
    static short[] tone(float[] freqs, float secondsEach, float gain, float slide) {
        int each = (int) (RATE * secondsEach);
        short[] out = new short[each * freqs.length];
        for (int n = 0; n < freqs.length; n++) {
            double phase = 0;
            for (int i = 0; i < each; i++) {
                float t = i / (float) each;
                phase += 2 * Math.PI * freqs[n] * (1 + (slide - 1) * t) / RATE;
                double triangle = 2 / Math.PI * Math.asin(Math.sin(phase));
                double attack = Math.min(1, i / (RATE * 0.003));
                double value = (0.7 * Math.sin(phase) + 0.3 * triangle) * attack * (1 - t) * gain;
                out[n * each + i] = (short) (value * Short.MAX_VALUE);
            }
        }
        return out;
    }

    /** 16-bit mono PCM in a WAV container. */
    static byte[] wav(short[] samples) {
        int data = samples.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + data).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(data);
        for (short s : samples) b.putShort(s);
        return b.array();
    }
}
