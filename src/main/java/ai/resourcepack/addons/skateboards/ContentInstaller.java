package ai.resourcepack.addons.skateboards;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Unpacks the content folder carried in this jar into the engine's
 * {@code content/} directory.
 *
 * <p>A version stamp ({@code .version}) beside the files says which build of
 * this plugin last wrote them, so a restart with the same jar copies nothing
 * and reloads nothing, and a new build overwrites its own files. Only its own:
 * a file the server owner added to the folder is left alone, because the
 * folder is theirs to extend — a second skateboard skin, a different sound.
 *
 * <p>Files are written whole or not at all (a temp file moved into place),
 * so a server that dies mid-install has either the old file or the new one,
 * never half of one for the engine to choke on.
 */
final class ContentInstaller {

    private static final String STAMP = ".version";

    private ContentInstaller() {
    }

    /**
     * @param jar     this plugin's jar
     * @param prefix  the entry prefix inside it, ending in a slash
     * @param into    the engine's folder for this namespace
     * @param version this plugin's version, the stamp to compare against
     * @return whether anything was written, which is whether a reload is owed
     */
    static boolean install(File jar, String prefix, Path into, String version, Logger log) throws IOException {
        Path stamp = into.resolve(STAMP);
        if (Files.isRegularFile(stamp)
                && version.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim())) {
            return false;
        }
        int written = 0;
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                String relative = entry.getName().substring(prefix.length());
                if (relative.isEmpty() || relative.contains("..")) {
                    continue;
                }
                Path target = into.resolve(relative);
                Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                written++;
            }
        }
        Files.createDirectories(into);
        Files.writeString(stamp, version + "\n", StandardCharsets.UTF_8);
        log.info("Wrote " + written + " content file(s) into " + into + " (version " + version + ").");
        return written > 0;
    }
}
