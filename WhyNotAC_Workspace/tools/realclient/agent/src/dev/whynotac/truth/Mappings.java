package dev.whynotac.truth;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Mojang's official ProGuard mappings (client.txt, published with every version): Mojang names to the
 * obfuscated names in the shipped client jar. Only the classes asked for are kept.
 */
final class Mappings {

    record ClassMap(String obf, Map<String, String> fields, Map<String, String> methods) {}

    private final Map<String, ClassMap> classes = new HashMap<>();

    static Mappings read(Path file, java.util.Set<String> wanted) throws IOException {
        Mappings mappings = new Mappings();
        ClassMap current = null;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith(" ")) {
                    current = null;
                    int arrow = line.indexOf(" -> ");
                    if (arrow > 0 && line.endsWith(":")) {
                        String name = line.substring(0, arrow);
                        if (wanted.contains(name)) {
                            current = new ClassMap(line.substring(arrow + 4, line.length() - 1), new HashMap<>(), new HashMap<>());
                            mappings.classes.put(name, current);
                        }
                    }
                    continue;
                }
                if (current == null) {
                    continue;
                }
                String member = line.strip();
                if (member.startsWith("#")) {
                    continue;
                }
                int arrow = member.indexOf(" -> ");
                String obf = member.substring(arrow + 4);
                String left = member.substring(0, arrow);
                if (left.indexOf('(') >= 0) {
                    // [a:b:]returnType name(args)[:c:d]
                    String withoutLines = left.replaceFirst("^\\d+:\\d+:", "");
                    String signature = withoutLines.substring(withoutLines.indexOf(' ') + 1).replaceFirst(":\\d+:\\d+$", "");
                    current.methods.putIfAbsent(signature, obf);
                } else {
                    current.fields.put(left.substring(left.indexOf(' ') + 1), obf);
                }
            }
        }
        return mappings;
    }

    ClassMap get(String mojangName) {
        ClassMap map = classes.get(mojangName);
        if (map == null) {
            throw new IllegalStateException("no mapping for " + mojangName);
        }
        return map;
    }

    String field(String mojangClass, String field) {
        String obf = get(mojangClass).fields().get(field);
        if (obf == null) {
            throw new IllegalStateException("no mapping for field " + mojangClass + "." + field);
        }
        return obf;
    }

    /** {@code signature} is e.g. {@code "tick()"} or {@code "isSprinting()"}. */
    String method(String mojangClass, String signature) {
        String obf = get(mojangClass).methods().get(signature);
        if (obf == null) {
            throw new IllegalStateException("no mapping for method " + mojangClass + "." + signature);
        }
        return obf;
    }
}
