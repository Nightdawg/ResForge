package resforge;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ArtifactMetadataTest {
    private static final List<String> RESOURCES = List.of(
            "META-INF/licenses/ResForge-MIT.txt",
            "META-INF/licenses/THIRD-PARTY-NOTICES.md",
            "META-INF/licenses/JOrbis-LGPL-2.0.txt",
            "META-INF/licenses/JOrbis-SOURCE.txt",
            "META-INF/licenses/JNA-LICENSE.txt",
            "META-INF/licenses/FlatLaf-Apache-2.0.txt");

    @Test
    void canonicalLicenseMetadataIsOnTheRuntimeClasspath() throws Exception {
        ClassLoader loader = ArtifactMetadataTest.class.getClassLoader();
        for(String resource : RESOURCES) {
            try(InputStream in = loader.getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(content.isBlank(), resource);
            }
        }
    }

    @Test
    void packagedProjectLicenseAndNoticesMatchRepositoryCopies() throws Exception {
        assertResourceEquals("META-INF/licenses/ResForge-MIT.txt", Path.of("LICENSE"));
        assertResourceEquals("META-INF/licenses/THIRD-PARTY-NOTICES.md",
                Path.of("THIRD-PARTY-NOTICES.md"));
    }

    private static void assertResourceEquals(String resource, Path source) throws Exception {
        try(InputStream in = ArtifactMetadataTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            assertArrayEquals(Files.readAllBytes(source), in.readAllBytes(), resource);
        }
    }
}
