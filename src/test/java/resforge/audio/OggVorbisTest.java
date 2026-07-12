package resforge.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggVorbisTest {
    private static final String FIXTURE = "resforge/audio/vorbis-sample.ogg";

    @Test
    void decodesCc0VorbisFixtureToPcm() throws Exception {
        OggVorbis.Pcm pcm = OggVorbis.decode(fixtureBytes());

        assertTrue(pcm.data.length > 0);
        assertTrue(nonZeroSamples(pcm.data) > 200_000);
    }

    @Test
    void preservesExpectedMetadataAndPcmOutput() throws Exception {
        OggVorbis.Pcm pcm = OggVorbis.decode(fixtureBytes());

        assertEquals(44_100, pcm.rate);
        assertEquals(2, pcm.channels);
        assertEquals(104_370, pcm.frames());
        assertEquals(417_480, pcm.data.length);
        assertEquals(2.3666666666666667, pcm.seconds());
        assertEquals("2dc1485746453333318fece0c3192648c7d6a1347c5b57c717945cab02c6cb71",
                sha256(pcm.data));
    }

    private static byte[] fixtureBytes() throws IOException {
        try(InputStream in = OggVorbisTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, FIXTURE);
            return in.readAllBytes();
        }
    }

    private static long nonZeroSamples(byte[] pcm) {
        long count = 0;
        for(int i = 0; i < pcm.length; i += 2)
            if(pcm[i] != 0 || pcm[i + 1] != 0)
                count++;
        return count;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
