package app.morphed.extension.twitch.musicfilter;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Reads a downloaded song (Opus or AAC) into 16-bit stereo at 48 kHz with Android's own decoders. */
public final class AndroidDecoder implements SongLibrary.Decoder {
    private static final long TIMEOUT_US = 10_000;
    /** A song's copy is at most this long (at the file's own rate): longer won't fit in memory. */
    private static final int MAX_FRAMES = 11 * 60 * 48000;
    private static final String TOO_LONG = "the download is too long for a song's copy";

    @Override
    public SongCopy decode(String videoId, File file) throws SongProblem {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getPath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0) throw new SongProblem("the download has no sound in it");
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean floats = false;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            int guess = (int) Math.max(48000L * 10, durationUs * 48 / 1000 + 48000);
            ShortGrowable left = new ShortGrowable(guess), right = new ShortGrowable(guess);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            float[] floatChunk = new float[0];
            short[] shortChunk = new short[0];
            boolean inputDone = false, outputDone = false, waitNext = false;
            while (!outputDone) {
                // Every free input slot is filled at once, and the output is waited for only when nothing is
                // ready: a song is thousands of 20 ms pieces, and a wait per piece adds up to minutes.
                while (!inputDone) {
                    int in = codec.dequeueInputBuffer(0);
                    if (in < 0) break;
                    ByteBuffer buffer = codec.getInputBuffer(in);
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
                int out = codec.dequeueOutputBuffer(info, waitNext ? TIMEOUT_US : 0);
                waitNext = out < 0;
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floats = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset).limit(info.offset + info.size);
                        ByteBuffer pcm = buffer.slice().order(ByteOrder.nativeOrder());
                        // In bulk: a song is millions of samples, one call each would take most of the time.
                        if (floats) {
                            java.nio.FloatBuffer samples = pcm.asFloatBuffer();
                            int count = samples.remaining();
                            int frames = count / channels;
                            if (left.size + frames > MAX_FRAMES) throw new SongProblem(TOO_LONG);
                            if (floatChunk.length < count) floatChunk = new float[count];
                            samples.get(floatChunk, 0, count);
                            left.reserve(frames);
                            right.reserve(frames);
                            for (int f = 0, i = 0; f < frames; f++, i += channels) {
                                left.data[left.size++] = toShort(floatChunk[i]);
                                right.data[right.size++] = toShort(floatChunk[channels > 1 ? i + 1 : i]);
                            }
                        } else {
                            ShortBuffer samples = pcm.asShortBuffer();
                            int count = samples.remaining();
                            int frames = count / channels;
                            if (left.size + frames > MAX_FRAMES) throw new SongProblem(TOO_LONG);
                            if (shortChunk.length < count) shortChunk = new short[count];
                            samples.get(shortChunk, 0, count);
                            left.reserve(frames);
                            right.reserve(frames);
                            for (int f = 0, i = 0; f < frames; f++, i += channels) {
                                left.data[left.size++] = shortChunk[i];
                                right.data[right.size++] = shortChunk[channels > 1 ? i + 1 : i];
                            }
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (left.size == 0) throw new SongProblem("the download has no sound in it");
            short[] l = left.toArray(), r = right.toArray();
            if (rate != Canceller.RATE) {
                l = Resampler.resample(l, rate, Canceller.RATE);
                r = Resampler.resample(r, rate, Canceller.RATE);
            }
            return new SongCopy(videoId, l, r);
        } catch (SongProblem problem) {
            throw problem;
        } catch (Exception | OutOfMemoryError error) {
            throw new SongProblem("the download couldn't be read: " + Reasons.of(error), error);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // already stopped by its failure
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static short toShort(float value) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768f)));
    }

    /** A growing array of samples (a song is millions of them: no boxing). */
    static final class ShortGrowable {
        short[] data;
        int size;

        ShortGrowable(int capacity) {
            data = new short[Math.max(capacity, 1024)];
        }

        void reserve(int more) {
            if (size + more > data.length) data = java.util.Arrays.copyOf(data, Math.max(size + more, data.length + data.length / 2));
        }

        void add(short value) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, data.length + data.length / 2);
            data[size++] = value;
        }

        short[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
