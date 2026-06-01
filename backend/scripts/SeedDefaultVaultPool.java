import com.lianyu.security.util.JasyptUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-encrypt DEFAULT platform keys with current LIANYU_MASTER_KEY and emit SQL updates.
 * Usage: SeedDefaultVaultPool <keys-file> <output.sql>
 */
public class SeedDefaultVaultPool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SeedDefaultVaultPool <keys-file> <output.sql>");
            System.exit(2);
        }
        if (System.getenv("LIANYU_MASTER_KEY") == null || System.getenv("LIANYU_MASTER_KEY").isBlank()) {
            System.err.println("LIANYU_MASTER_KEY not set");
            System.exit(3);
        }
        List<String> keys = readKeys(Path.of(args[0]));
        if (keys.isEmpty()) {
            System.err.println("No API keys found in file");
            System.exit(4);
        }
        JasyptUtil util = new JasyptUtil();
        String version = util.getCurrentVersion();
        StringBuilder sql = new StringBuilder();
        sql.append("USE lianyu;\n");
        String baseUrl = System.getenv().getOrDefault("PLATFORM_VAULT_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("PLATFORM_VAULT_MODEL", "deepseek-v4-flash");
        int id = 1;
        for (String key : keys) {
            String enc = util.encrypt(key.trim());
            sql.append("UPDATE api_key_vault SET api_key_encrypted='")
                    .append(esc(enc))
                    .append("', key_version='")
                    .append(esc(version))
                    .append("', enabled=1, base_url='")
                    .append(esc(baseUrl))
                    .append("', model_default='")
                    .append(esc(model))
                    .append("' WHERE id=")
                    .append(id)
                    .append(" AND vault_scope='DEFAULT';\n");
            id++;
        }
        Files.writeString(Path.of(args[1]), sql.toString());
        System.out.println("keys=" + keys.size() + " sql=" + args[1]);
    }

    private static List<String> readKeys(Path file) throws Exception {
        List<String> keys = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            keys.add(t);
        }
        return keys;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }
}
