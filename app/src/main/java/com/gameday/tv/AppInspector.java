package com.gameday.tv;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * "What links does this app accept?" Reads the app's own install file (its AndroidManifest) on the box and lists the
 * links and actions its screens accept from other apps. That tells us whether Game Day could open a channel directly.
 */
final class AppInspector {
    private AppInspector() {}

    // android: attribute resource ids (used when the attribute names were stripped)
    private static final int A_NAME = 0x01010003, A_EXPORTED = 0x01010010, A_MIME = 0x01010026, A_SCHEME = 0x01010027,
            A_HOST = 0x01010028, A_PORT = 0x01010029, A_PATH = 0x0101002a, A_PREFIX = 0x0101002b, A_PATTERN = 0x0101002c;

    static List<String> inspect(Context c, String pkg) {
        List<String> out = new ArrayList<>();
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            byte[] xml;
            try (ZipFile z = new ZipFile(ai.sourceDir)) {
                ZipEntry e = z.getEntry("AndroidManifest.xml");
                try (InputStream in = z.getInputStream(e)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                    xml = b.toByteArray();
                }
            }
            parse(xml, out);
        } catch (Exception e) {
            out.add("Couldn't read this app's install file: " + e);
        }
        if (out.isEmpty()) out.add("This app doesn't accept any links or actions from other apps (only its home screen icon).");
        return out;
    }

    // ---------- Android binary XML ----------
    private static void parse(byte[] data, List<String> out) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        List<String> strings = new ArrayList<>();
        int[] resIds = new int[0];
        String component = null;
        boolean exported = false;
        Set<String> actions = new LinkedHashSet<>(), cats = new LinkedHashSet<>(), datas = new LinkedHashSet<>();
        boolean inFilter = false;
        int pos = 8;   // after the file header
        while (pos + 8 <= data.length) {
            int type = b.getShort(pos) & 0xffff, headerSize = b.getShort(pos + 2) & 0xffff, size = b.getInt(pos + 4);
            if (size <= 0) break;
            if (type == 0x0001) strings = stringPool(b, pos);
            else if (type == 0x0180) {
                int n = (size - headerSize) / 4;
                resIds = new int[n];
                for (int i = 0; i < n; i++) resIds[i] = b.getInt(pos + headerSize + i * 4);
            } else if (type == 0x0102) {   // start element
                String tag = str(strings, b.getInt(pos + 20));
                int attrStart = b.getShort(pos + 24) & 0xffff, attrSize = b.getShort(pos + 26) & 0xffff, count = b.getShort(pos + 28) & 0xffff;
                int base = pos + 16 + attrStart;
                java.util.Map<String, String> attrs = new java.util.HashMap<>();
                for (int i = 0; i < count; i++) {
                    int a = base + i * attrSize;
                    int nameIdx = b.getInt(a + 4), raw = b.getInt(a + 8), dataType = b.get(a + 15) & 0xff, value = b.getInt(a + 16);
                    String name = str(strings, nameIdx);
                    int rid = nameIdx >= 0 && nameIdx < resIds.length ? resIds[nameIdx] : 0;
                    if (name.isEmpty()) name = rid == A_NAME ? "name" : rid == A_EXPORTED ? "exported" : rid == A_MIME ? "mimeType" : rid == A_SCHEME ? "scheme"
                            : rid == A_HOST ? "host" : rid == A_PORT ? "port" : rid == A_PATH ? "path" : rid == A_PREFIX ? "pathPrefix" : rid == A_PATTERN ? "pathPattern" : "";
                    String v = raw >= 0 ? str(strings, raw) : dataType == 0x12 ? (value != 0 ? "true" : "false") : String.valueOf(value);
                    attrs.put(name, v);
                }
                switch (tag) {
                    case "activity": case "activity-alias": case "service": case "receiver":
                        component = tag + " " + attrs.getOrDefault("name", "?");
                        exported = "true".equals(attrs.get("exported"));
                        break;
                    case "intent-filter": inFilter = true; actions.clear(); cats.clear(); datas.clear(); break;
                    case "action": if (inFilter) actions.add(attrs.getOrDefault("name", "")); break;
                    case "category": if (inFilter) cats.add(attrs.getOrDefault("name", "")); break;
                    case "data":
                        if (inFilter) {
                            StringBuilder d = new StringBuilder();
                            if (attrs.containsKey("scheme")) d.append(attrs.get("scheme")).append("://");
                            if (attrs.containsKey("host")) d.append(attrs.get("host"));
                            for (String k : new String[]{"path", "pathPrefix", "pathPattern"}) if (attrs.containsKey(k)) d.append(attrs.get(k)).append(k.equals("path") ? "" : "*");
                            if (attrs.containsKey("mimeType")) d.append(d.length() > 0 ? " " : "").append("type ").append(attrs.get("mimeType"));
                            if (d.length() > 0) datas.add(d.toString());
                        }
                        break;
                    default: break;
                }
                if (component != null && tag.equals("intent-filter")) exported = true;   // a filter makes it reachable
            } else if (type == 0x0103) {   // end element
                String tag = str(strings, b.getInt(pos + 20));
                if (tag.equals("intent-filter") && component != null) {
                    inFilter = false;
                    boolean onlyLauncher = actions.size() == 1 && actions.contains("android.intent.action.MAIN") && datas.isEmpty();
                    if (!onlyLauncher && !actions.isEmpty()) {
                        out.add(short_(component) + (exported ? "" : " (not open to other apps)") + " accepts " + short_(String.join(", ", actions))
                                + (datas.isEmpty() ? "" : " with " + String.join(" | ", datas)));
                    }
                }
                if (tag.equals("activity") || tag.equals("activity-alias") || tag.equals("service") || tag.equals("receiver")) component = null;
            }
            pos += size;
        }
    }

    private static String short_(String s) { return s.replace("android.intent.action.", "").replace("android.intent.category.", ""); }

    private static String str(List<String> s, int i) { return i >= 0 && i < s.size() ? s.get(i) : ""; }

    private static List<String> stringPool(ByteBuffer b, int pos) {
        int count = b.getInt(pos + 8), flags = b.getInt(pos + 16), stringsStart = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int p = pos + stringsStart + b.getInt(pos + 28 + i * 4);
            try {
                if (utf8) {
                    int n = b.get(p) & 0xff;
                    p += (n & 0x80) != 0 ? 2 : 1;                     // char count
                    int len = b.get(p) & 0xff;
                    if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b.get(p + 1) & 0xff); p += 2; } else p += 1;
                    byte[] bytes = new byte[len];
                    for (int k = 0; k < len; k++) bytes[k] = b.get(p + k);
                    out.add(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    int len = b.getShort(p) & 0xffff;
                    if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(p + 2) & 0xffff); p += 4; } else p += 2;
                    char[] ch = new char[len];
                    for (int k = 0; k < len; k++) ch[k] = (char) (b.getShort(p + k * 2) & 0xffff);
                    out.add(new String(ch));
                }
            } catch (Exception e) { out.add(""); }
        }
        return out;
    }
}
